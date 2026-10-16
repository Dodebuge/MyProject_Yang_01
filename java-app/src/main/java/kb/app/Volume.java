package kb.app;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다 (volume.py와 같은 계산).
 *
 * 코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
 * 종목별 최근 일봉(IVS11560)을 주(월~일)별로 묶어 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
 * 휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
 * 여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.
 * 장중 10분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (Ticks, 아래 '장중 기록').
 */
final class Volume {

    private static final int CACHE_SECONDS = 600;  // 1,700번 넘게 부르는 조회라 10분 동안은 다시 부르지 않음
    private static final int DAYS = 15;            // 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KBClient client;
    private long cachedAt;
    private Map<String, Object> cache;
    /** 장중(평일 09:00~15:40, 15:40은 15:30 종가 단일가까지 담기게) 10분마다 코스닥 거래량 기록. 하루 약 4MB */
    final Ticks ticks;

    Volume(KBClient client, Path tickDb) {
        this.client = client;
        this.ticks = new Ticks("코스닥 10분", tickDb, "kosdaq_tick", ZoneId.of("Asia/Seoul"), 10,
                LocalTime.of(9, 0), LocalTime.of(15, 40), this::kosdaqRows);
    }

    /** GET /api/kosdaq-volume -> {"fetchedAt", "total", "stocks": [...]} (stocks는 전 종목, 화면에서 거름). synchronized: 동시에 와도 KB 조회는 한 번 */
    synchronized Map<String, Object> query(boolean refresh) throws IOException {
        if (!refresh && cache != null && System.currentTimeMillis() - cachedAt < CACHE_SECONDS * 1000L) {
            return cache;
        }
        List<Map<String, Object>> stocks = fetch();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fetchedAt", LocalDateTime.now().format(STAMP));
        payload.put("total", stocks.size());
        payload.put("stocks", stocks);
        cachedAt = System.currentTimeMillis();
        cache = payload;
        return payload;
    }

    private List<Map<String, Object>> fetch() throws IOException {
        List<Map<String, Object>> listed = KBClient.records(client.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
                "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2");
        client.accessToken();  // 병렬 호출 전에 토큰을 한 번만 발급
        List<Map<String, Object>> rows = Values.parallel(listed, 8, row -> {
            List<Map<String, Object>> candles = null;
            for (int attempt = 0; attempt < 3 && !sane(candles); attempt++) {  // 밀린 응답·500 오류면 다시
                try {
                    candles = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1", "chrt_clsf", "D",
                            "minute_tck_indx", "", "is_cd", Values.str(row.get("is_cd")), "inq_clsf", "2", "strt_dy", "",
                            "inq_cnt", String.valueOf(DAYS))), "out2");
                } catch (KBApiException | IOException e) {
                    candles = null;
                }
            }
            if (!sane(candles)) {
                return null;  // 거래정지 등
            }
            Map<String, Object> w = weekChange(candles);
            if (w == null) {
                return null;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", Values.str(row.get("is_cd")));
            s.put("name", Values.str(row.get("is_nm")));
            s.put("price", Values.lng(row.get("now_prc")));
            s.put("change", Values.num(row.get("up_dwn_r_p2")));
            s.put("value", Values.lng(row.get("dl_tw_amt")));
            s.putAll(w);
            return s;
        });
        rows.removeIf(Objects::isNull);
        return rows;
    }

    /** 필드가 밀리지 않은 일봉인지: 날짜가 실제 날짜이고 최신순으로 겹치지 않으며, 거래량이 숫자. */
    static boolean sane(List<Map<String, Object>> candles) {
        if (candles == null || candles.isEmpty()) {
            return false;
        }
        String before = null;
        for (Map<String, Object> r : candles) {
            String d = Values.str(r.get("dt"));
            if (!Heatmap.validDay(d) || (before != null && d.compareTo(before) >= 0) || !(r.get("vlm") instanceof Number)) {
                return false;
            }
            before = d;
        }
        return true;
    }

    /** 일봉 [{dt, vlm}] -> 이번 주·전주 하루 평균 거래량과 증가율(%). 두 주가 안 되면 null. */
    static Map<String, Object> weekChange(List<Map<String, Object>> candles) {
        TreeMap<Integer, List<Long>> weeks = new TreeMap<>();
        for (Map<String, Object> r : candles) {
            String d = Values.str(r.get("dt"));
            if (Heatmap.validDay(d)) {  // 필드가 밀린 행은 날짜 자리에 엉뚱한 숫자
                LocalDate day = LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE);
                int week = day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                weeks.computeIfAbsent(week, k -> new ArrayList<>()).add(Values.lng(r.get("vlm")));
            }
        }
        if (weeks.size() < 2) {
            return null;
        }
        List<Long> now = weeks.lastEntry().getValue();
        List<Long> prev = weeks.lowerEntry(weeks.lastKey()).getValue();
        double avg = average(now);
        double before = average(prev);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avg", Math.round(avg));
        m.put("prevAvg", Math.round(before));
        m.put("days", now.size());
        m.put("prevDays", prev.size());
        m.put("ratio", before > 0 ? Math.round((avg / before - 1) * 10000) / 100.0 : null);
        return m;
    }

    private static double average(List<Long> xs) {
        return xs.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    // ------------------------------------------------------------------ 장중 기록 (Ticks)

    /** 코스닥 전 종목 시세 (IVU10210 한 번). 휴장일(오늘 일봉이 없음)이면 빈 목록. */
    private List<List<Object>> kosdaqRows(LocalDateTime at) throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : KBClient.records(client.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
                "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2")) {
            if (Values.str(r.get("is_cd")).length() == 6 && r.get("vlm") instanceof Number) {  // 필드가 밀린 행은 버림
                rows.add(Arrays.asList(Values.str(r.get("is_cd")), Values.str(r.get("is_nm")), Values.lng(r.get("now_prc")),
                        Values.num(r.get("up_dwn_r_p2")), Values.lng(r.get("vlm")), Values.lng(r.get("dl_tw_amt"))));
            }
        }
        if (rows.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> last = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1",
                "chrt_clsf", "D", "minute_tck_indx", "", "is_cd", (String) rows.get(0).get(0), "inq_clsf", "2", "strt_dy", "",
                "inq_cnt", "1")), "out2");
        if (last.isEmpty() || !at.format(DateTimeFormatter.BASIC_ISO_DATE).equals(Values.str(last.get(0).get("dt")))) {
            return new ArrayList<>();  // 휴장일: 시세가 마지막 거래일 값 그대로
        }
        return rows;
    }
}
