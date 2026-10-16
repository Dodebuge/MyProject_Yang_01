package kb.app;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 러셀 2000 거래량 (russell.py와 같은 계산·같은 JSON). 코스닥 거래량(Volume)과 같은 두 보기: 주간 평균, 같은 시각.
 *
 * KB OpenAPI에는 미국 종목 목록·순위 TR이 없어, 구성 종목은 데이터 폴더의 russell2000.csv에서 읽습니다.
 * iShares IWM(러셀 2000 ETF) Holdings의 'Download' CSV를 그대로 넣으면 되고(Asset Class가 Equity인 행만), Ticker 열만 있는 CSV도 됩니다.
 * 주간 평균: 종목별 일봉(GSC10060). 같은 시각: 뉴욕 장중(평일 10:00~16:30 뉴욕 시간) 30분마다 상세시세(GSS10030)를 russell.db에 저장.
 * KB 미국 시세는 15분 지연이라 10:00 기록은 09:45 무렵 값이고, 16:30 기록에 16:00 종가까지 담깁니다.
 * 거래소는 CSV의 Exchange 열을 먼저 쓰고, 없거나 틀리면 NAS·NYS·AMX 순으로 찾습니다. iShares 표기(MOGA)가 KB 표기(MOG.A)와
 * 다르면 마지막 글자 앞에 점을 넣어 한 번 더 찾고, 화면·기록의 종목 코드는 KB 티커라 차트 링크로 바로 열립니다.
 */
final class Russell {

    private static final String[] EXCHANGES = {"NAS", "NYS", "AMX"};
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9.]{0,7}");
    private static final int CACHE_SECONDS = 600;  // 2,000번 가까이 부르는 조회라 10분 동안은 다시 부르지 않음
    private static final int WORKERS = 8;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KBClient client;
    private final Path csv;
    private final Map<String, String[]> found = new ConcurrentHashMap<>();  // 티커 -> {KB 거래소, KB 티커}. 한 번 찾으면 다시 찾지 않음
    private long cachedAt;
    private Map<String, Object> cache;
    /** 뉴욕 장중 30분마다 (10:00~16:30 뉴욕 시간). 하루 약 1.5MB */
    final Ticks ticks;

    Russell(KBClient client, Path csv, Path tickDb) {
        this.client = client;
        this.csv = csv;
        this.ticks = new Ticks("러셀 2000 30분", tickDb, "russell_tick", ZoneId.of("America/New_York"), 30,
                LocalTime.of(10, 0), LocalTime.of(16, 30), this::rows);
    }

    /** iShares CSV의 Exchange 값 -> KB 거래소 코드 (모르면 ""). */
    static String exchangeOf(String name) {
        String n = name.toLowerCase();
        if (n.contains("nasdaq")) {
            return "NAS";
        }
        if (n.contains("new york stock exchange") || n.trim().equals("nyse") || n.trim().equals("nys")) {
            return "NYS";
        }
        for (String k : new String[]{"mkt", "american", "arca", "cboe", "bats", "amex"}) {
            if (n.contains(k)) {
                return "AMX";
            }
        }
        return "";
    }

    /** russell2000.csv -> [{"code", "name", "exchange"}]. 파일이 없거나 Ticker 열이 없으면 IllegalArgumentException (화면에 그대로 보임). */
    static List<Map<String, String>> loadList(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(path.getFileName() + "이 없습니다. iShares IWM(러셀 2000 ETF) 페이지 Holdings에서 CSV를 받아 "
                    + path.getParent() + "에 " + path.getFileName() + "로 넣어 주세요.");
        }
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("﻿", "");
        List<List<String>> table = parseCsv(text);
        int head = -1;
        for (int i = 0; i < table.size() && head < 0; i++) {
            if (!table.get(i).isEmpty() && table.get(i).get(0).trim().equals("Ticker")) {
                head = i;
            }
        }
        if (head < 0) {
            throw new IllegalArgumentException(path.getFileName() + "에서 Ticker 열을 찾지 못했습니다.");
        }
        List<String> cols = table.get(head);
        int ticker = cols.indexOf("Ticker");
        int name = cols.indexOf("Name");
        int asset = cols.indexOf("Asset Class");
        int exchange = cols.indexOf("Exchange");
        List<Map<String, String>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<String> r : table.subList(head + 1, table.size())) {
            String code = cell(r, ticker).toUpperCase();
            String cls = asset < 0 ? "Equity" : cell(r, asset);
            if (!cls.equals("Equity") || !TICKER.matcher(code).matches() || !seen.add(code)) {
                continue;  // 현금·선물 행, 표 아래 안내문
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", name < 0 || cell(r, name).isEmpty() ? code : cell(r, name));
            m.put("exchange", exchangeOf(cell(r, exchange)));
            out.add(m);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException(path.getFileName() + "에 종목이 없습니다.");
        }
        return out;
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    /** 따옴표("a, b", "")를 지원하는 간단한 CSV 파서. */
    static List<List<String>> parseCsv(String text) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        try (StringReader in = new StringReader(text)) {
            for (int ch = in.read(); ch >= 0; ch = in.read()) {
                char c = (char) ch;
                if (quoted) {
                    if (c == '"') {
                        in.mark(1);
                        if (in.read() == '"') {
                            cell.append('"');
                        } else {
                            in.reset();
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r') {
                        in.mark(1);
                        if (in.read() != '\n') {
                            in.reset();
                        }
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private static final class Shifted extends Exception {
    }

    /** probe가 값을 돌려줄 때까지 거래소·티커 표기를 바꿔 가며 부릅니다. 밀린 응답·500 오류면 세 번까지. 못 찾으면 null. */
    private <T> T resolve(Map<String, String> item, ShiftedProbe<T> probe) {
        String code = item.get("code");
        List<String[]> tries = new ArrayList<>();
        if (found.containsKey(code)) {
            tries.add(found.get(code));
        } else {
            List<String> names = new ArrayList<>(Arrays.asList(code));
            if (code.length() >= 4 && !code.contains(".")) {
                names.add(code.substring(0, code.length() - 1) + "." + code.charAt(code.length() - 1));
            }
            List<String> exchanges = new ArrayList<>();
            if (!item.get("exchange").isEmpty()) {
                exchanges.add(item.get("exchange"));
            }
            for (String x : EXCHANGES) {
                if (!exchanges.contains(x)) {
                    exchanges.add(x);
                }
            }
            for (String n : names) {
                for (String x : exchanges) {
                    tries.add(new String[]{x, n});
                }
            }
        }
        for (String[] t : tries) {
            for (int attempt = 0; attempt < 3; attempt++) {
                T got;
                try {
                    got = probe.get(t[0], t[1]);
                } catch (KBApiException | IOException | Shifted e) {
                    continue;
                }
                if (got != null) {
                    found.put(code, t);
                    return got;
                }
                break;  // 빈 응답: 이 거래소·표기에는 없음
            }
        }
        return null;
    }

    /** 이 거래소·표기에 없으면 null, 필드가 밀린 응답이면 Shifted. */
    private interface ShiftedProbe<T> {
        T get(String exchange, String code) throws IOException, Shifted;
    }

    private Map<String, Object> quote(Map<String, String> item) {
        return resolve(item, (x, n) -> {
            Map<String, Object> q = client.call("GSS10030", Heatmap.body("krx_cd", x, "is_cd", n));
            Object price = q.get("now_prc_p4");
            if (price == null || "".equals(price)) {
                return null;  // 이 거래소·표기에는 없음
            }
            if (!(price instanceof Number) || !(q.get("vlm") instanceof Number)) {
                throw new Shifted();
            }
            return q;
        });
    }

    /** KB 티커·거래소(주간 평균 응답의 code, exchange)로 일봉 행 (GSC10060, 약 1년, 최신순). 시그널 알림(Alerts)이 씀 */
    List<Map<String, Object>> daily(String code, String exchange) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", code);
        item.put("exchange", exchange);
        List<Map<String, Object>> rows = candles(item);
        for (int attempt = 0; attempt < 2 && rows != null && !Volume.whole(rows, "p4"); attempt++) {  // 오래된 행이 깨졌으면 한 번 더
            List<Map<String, Object>> again = candles(item);
            rows = again == null ? rows : again;
        }
        return rows;
    }

    private List<Map<String, Object>> candles(Map<String, String> item) {
        return resolve(item, (x, n) -> {
            List<Map<String, Object>> rows = KBClient.records(client.call("GSC10060", Heatmap.body("chrt_clsf", "3",  // 3 = 일봉
                    "mdfy_stk_prc_use_f", "", "is_cd", n, "clsf", "1", "srch_strt_dy", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                    "rcrd_c", Chart.CHART_DAYS, "bndl", "", "krx_cd", x)), "out2");
            if (rows.isEmpty()) {
                return null;
            }
            if (!Volume.sane(Volume.recent(rows))) {  // 주간 비교에 쓰는 최근 행만 엄격히
                throw new Shifted();
            }
            return rows;
        });
    }

    /** GET /api/russell-volume -> {"fetchedAt", "total", "listed", "stocks": [...]} (Volume.query와 같은 모양, listed: CSV 종목 수) */
    synchronized Map<String, Object> query(boolean refresh) throws IOException {
        if (!refresh && cache != null && System.currentTimeMillis() - cachedAt < CACHE_SECONDS * 1000L) {
            return cache;
        }
        List<Map<String, String>> items = loadList(csv);
        client.accessToken();  // 병렬 호출 전에 토큰을 한 번만 발급
        List<Map<String, Object>> stocks = Values.parallel(items, WORKERS, item -> {
            List<Map<String, Object>> rows = candles(item);
            for (int attempt = 0; attempt < 2 && rows != null && !Volume.whole(rows, "p4"); attempt++) {  // 오래된 행이 깨졌으면 한 번 더
                List<Map<String, Object>> again = candles(item);
                rows = again == null ? rows : again;
            }
            Map<String, Object> w = rows == null ? null : Volume.weekChange(Volume.recent(rows));
            if (w == null) {
                return null;
            }
            Map<String, Object> last = rows.get(0);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", found.get(item.get("code"))[1]);  // KB 티커 (차트 링크용, MOGA -> MOG.A)
            s.put("exchange", found.get(item.get("code"))[0]);
            s.put("name", item.get("name"));
            s.put("price", last.get("cls_prc_p4"));
            s.put("change", Values.num(last.get("up_dwn_r_p2")));
            s.put("value", Values.lng(last.get("dl_tw_amt")));
            s.putAll(w);
            s.putAll(Volume.closes(Chart.candles(rows, "p4", 1)));  // 화면의 기술적 시그널 태그용
            return s;
        });
        stocks.removeIf(Objects::isNull);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fetchedAt", LocalDateTime.now().format(STAMP));
        payload.put("total", stocks.size());
        payload.put("listed", items.size());
        payload.put("stocks", stocks);
        cachedAt = System.currentTimeMillis();
        cache = payload;
        return payload;
    }

    /**
     * 점검: CSV 종목마다 KB 시세(GSS10030)를 찾아 {"listed", "found", "missing": [못 찾은 티커], "renamed": ["MOGA -> NYS:MOG.A", ...]}.
     * 단위 테스트 RussellTest(-Drussell.live=true)와 python russell.py --check가 씁니다.
     */
    Map<String, Object> check() throws IOException {
        List<Map<String, String>> items = loadList(csv);
        client.accessToken();
        List<String> missing = Values.parallel(items, WORKERS, item -> quote(item) == null ? item.get("code") : null);
        missing.removeIf(Objects::isNull);
        List<String> renamed = new ArrayList<>();
        for (Map<String, String> item : items) {
            String[] f = found.get(item.get("code"));
            if (f != null && !f[1].equals(item.get("code"))) {
                renamed.add(item.get("code") + " -> " + f[0] + ":" + f[1]);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listed", items.size());
        out.put("found", items.size() - missing.size());
        out.put("missing", missing);
        out.put("renamed", renamed);
        return out;
    }

    /** 러셀 2000 종목별 상세시세. 그날(뉴욕 날짜) 체결이 있는 종목만 (휴장일이면 빈 목록). */
    private List<List<Object>> rows(LocalDateTime at) throws IOException {
        List<Map<String, String>> items = loadList(csv);
        String day = at.format(DateTimeFormatter.BASIC_ISO_DATE);
        client.accessToken();
        List<List<Object>> rows = Values.parallel(items, WORKERS, item -> {
            Map<String, Object> q = quote(item);
            if (q == null || !day.equals(Values.str(q.get("dt")))) {
                return null;
            }
            return Arrays.asList(found.get(item.get("code"))[1], item.get("name"), q.get("now_prc_p4"),
                    Values.num(q.get("up_dwn_r_p2")), Values.lng(q.get("vlm")), Values.lng(q.get("dl_tw_amt")));
        });
        rows.removeIf(Objects::isNull);
        return rows;
    }
}
