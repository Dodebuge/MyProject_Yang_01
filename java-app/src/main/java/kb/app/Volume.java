package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다 (volume.py와 같은 계산).
 *
 * 코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
 * 종목별 최근 일봉(IVS11560)을 주(월~일)별로 묶어 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
 * 휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
 * 여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.
 * 장중 10분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (아래 '10분 기록').
 */
final class Volume {

    private static final int CACHE_SECONDS = 600;  // 1,700번 넘게 부르는 조회라 10분 동안은 다시 부르지 않음
    private static final int DAYS = 15;            // 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KBClient client;
    private final Path tickDb;
    private long cachedAt;
    private Map<String, Object> cache;

    Volume(KBClient client, Path tickDb) {
        this.client = client;
        this.tickDb = tickDb;
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

    // ------------------------------------------------------------------ 10분 기록 (같은 시각 거래량 비교, volume.py와 같은 파일·같은 테이블)
    // 장중(평일 09:00~15:40) 10분마다 코스닥 전 종목의 누적 거래량·현재가를 kosdaq.db에 저장합니다 (IVU10210 한 번).
    // 화면은 고른 시각의 누적 거래량을 전일·전주(7일 전 이전의 마지막 거래일) 같은 시각과 비교합니다.
    // 같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록을 씁니다.

    private static final String TICK_SCHEMA = "CREATE TABLE IF NOT EXISTS kosdaq_tick ("
            + " at TEXT PRIMARY KEY, payload TEXT NOT NULL)";  // at: '2026-10-16T09:10', payload: [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...]
    private static final int TICK_MINUTES = 10;
    private static final LocalTime TICK_OPEN = LocalTime.of(9, 0);
    private static final LocalTime TICK_CLOSE = LocalTime.of(15, 40);  // 15:30 종가 단일가까지 담기게
    private static final int TICK_KEEP_DAYS = 14;       // 전주 비교에 7일 + 긴 연휴 여유 (하루 약 4MB)
    private static final int TICK_DELAY_SECONDS = 30;   // 정각 바로는 KB 시세·일봉이 아직 안 바뀌었을 수 있어 조금 뒤에 조회
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type ROWS = new TypeToken<List<List<Object>>>() { }.getType();

    private ScheduledExecutorService timer;

    /** now 다음의 장중 10분 시각 (평일 09:00~15:40). */
    static LocalDateTime nextTick(LocalDateTime now) {
        LocalDateTime t = now.truncatedTo(ChronoUnit.MINUTES).withMinute(now.getMinute() - now.getMinute() % TICK_MINUTES).plusMinutes(TICK_MINUTES);
        if (t.toLocalTime().isBefore(TICK_OPEN)) {
            t = t.with(TICK_OPEN);
        } else if (t.toLocalTime().isAfter(TICK_CLOSE)) {
            t = t.plusDays(1).with(TICK_OPEN);
        }
        while (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) {  // 토·일이면 다음 날 09:00
            t = t.plusDays(1).with(TICK_OPEN);
        }
        return t;
    }

    /** 장중 10분마다 기록 시작. */
    synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kosdaq-tick");
            t.setDaemon(true);
            return t;
        });
        scheduleNextTick();
        System.out.println("[코스닥 10분] 장중 10분마다 코스닥 거래량 기록 -> " + tickDb.getFileName()
                + " (다음 " + nextTick(LocalDateTime.now(SEOUL)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + ")");
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    // 실행할 때마다 다음 시각을 다시 계산해 예약 (고정 주기로 두면 PC 절전·시계 보정 뒤 시각이 밀림)
    private synchronized void scheduleNextTick() {
        if (timer.isShutdown()) {
            return;
        }
        LocalDateTime at = nextTick(LocalDateTime.now(SEOUL));
        long delay = Math.max(0, Duration.between(LocalDateTime.now(SEOUL), at).toMillis()) + TICK_DELAY_SECONDS * 1000L;
        timer.schedule(() -> {
            try {
                int n = takeTick(at);
                System.out.println("[코스닥 10분] " + at.format(AT) + " " + (n > 0 ? n + "종목 저장" : "휴장 · 저장 안 함"));
            } catch (IOException | SQLException | RuntimeException e) {  // KB 오류여도 다음 시각에 다시
                System.out.println("[코스닥 10분] " + at.format(AT) + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                scheduleNextTick();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** at 시각의 코스닥 전 종목 시세를 저장하고 종목 수를 돌려줍니다. 휴장일(오늘 일봉이 없음)이면 저장하지 않고 0. */
    int takeTick(LocalDateTime at) throws IOException, SQLException {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : KBClient.records(client.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
                "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2")) {
            if (Values.str(r.get("is_cd")).length() == 6 && r.get("vlm") instanceof Number) {  // 필드가 밀린 행은 버림
                rows.add(Arrays.asList(Values.str(r.get("is_cd")), Values.str(r.get("is_nm")), Values.lng(r.get("now_prc")),
                        Values.num(r.get("up_dwn_r_p2")), Values.lng(r.get("vlm")), Values.lng(r.get("dl_tw_amt"))));
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> last = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1",
                "chrt_clsf", "D", "minute_tck_indx", "", "is_cd", (String) rows.get(0).get(0), "inq_clsf", "2", "strt_dy", "",
                "inq_cnt", "1")), "out2");
        if (last.isEmpty() || !at.format(DateTimeFormatter.BASIC_ISO_DATE).equals(Values.str(last.get(0).get("dt")))) {
            return 0;  // 휴장일: 시세가 마지막 거래일 값 그대로
        }
        saveTick(at.format(AT), rows);
        return rows.size();
    }

    void saveTick(String at, List<List<Object>> rows) throws SQLException {
        String oldest = LocalDateTime.parse(at, AT).minusDays(TICK_KEEP_DAYS).format(AT);
        try (Connection c = openTicks();
             PreparedStatement put = c.prepareStatement("INSERT OR REPLACE INTO kosdaq_tick (at, payload) VALUES (?, ?)");
             PreparedStatement prune = c.prepareStatement("DELETE FROM kosdaq_tick WHERE at < ?")) {
            put.setString(1, at);
            put.setString(2, GSON.toJson(rows));
            put.executeUpdate();
            prune.setString(1, oldest);
            prune.executeUpdate();
        }
    }

    /** GET /api/kosdaq-intraday?date=2026-10-16&amp;at=10:30 (비우면 마지막 기록). volume.intraday와 같은 JSON. */
    Map<String, Object> intraday(String day, String at) throws IOException {
        try (Connection c = openTicks()) {
            List<String> ats = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT at FROM kosdaq_tick ORDER BY at")) {
                while (rs.next()) {
                    ats.add(rs.getString(1));
                }
            }
            TreeSet<String> dateSet = new TreeSet<>(Comparator.reverseOrder());
            ats.forEach(a -> dateSet.add(a.substring(0, 10)));
            List<String> dates = new ArrayList<>(dateSet);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dates", dates);
            out.put("nextTick", nextTick(LocalDateTime.now(SEOUL)).format(AT));
            if (ats.isEmpty()) {
                out.put("date", null);
                out.put("at", null);
                out.put("slots", new ArrayList<>());
                out.put("prev", null);
                out.put("week", null);
                out.put("stocks", new ArrayList<>());
                return out;
            }
            String d = dates.contains(day) ? day : dates.get(0);
            List<String> slots = new ArrayList<>();
            ats.stream().filter(a -> a.startsWith(d)).forEach(a -> slots.add(a.substring(11)));
            String t = slots.get(at.isEmpty() ? slots.size() - 1 : 0);
            for (String s : slots) {
                if (!at.isEmpty() && s.compareTo(at) <= 0) {
                    t = s;
                }
            }
            Map<String, String> prev = findTick(ats, dates, d, t);
            Map<String, String> week = findTick(ats, dates, LocalDate.parse(d).minusDays(6).toString(), t);  // 7일 전(같은 요일), 휴장이면 그 이전 마지막 기록일
            List<List<Object>> now = loadTick(c, d + "T" + t);
            Map<Object, Object> prevV = volumes(loadTick(c, prev == null ? null : prev.get("date") + "T" + prev.get("at")));
            Map<Object, Object> weekV = volumes(loadTick(c, week == null ? null : week.get("date") + "T" + week.get("at")));
            List<Map<String, Object>> stocks = new ArrayList<>();
            for (List<Object> r : now) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("code", r.get(0));
                s.put("name", r.get(1));
                s.put("price", r.get(2));
                s.put("change", r.get(3));
                s.put("vlm", r.get(4));
                s.put("value", r.get(5));
                long v = Values.lng(r.get(4));
                s.put("prevVlm", prevV.get(r.get(0)));
                s.put("prevRatio", tickRatio(v, prevV.get(r.get(0))));
                s.put("weekVlm", weekV.get(r.get(0)));
                s.put("weekRatio", tickRatio(v, weekV.get(r.get(0))));
                stocks.add(s);
            }
            out.put("date", d);
            out.put("at", t);
            out.put("slots", slots);
            out.put("prev", prev);
            out.put("week", week);
            out.put("stocks", stocks);
            return out;
        } catch (SQLException e) {
            throw new IOException("kosdaq.db 저장소 오류: " + e.getMessage(), e);
        }
    }

    /** before 이전 마지막 기록일의, at 이전 마지막 기록. 없으면 null. */
    private static Map<String, String> findTick(List<String> ats, List<String> dates, String before, String at) {
        String day = dates.stream().filter(x -> x.compareTo(before) < 0).findFirst().orElse(null);  // dates는 최신순
        String hit = null;
        for (String a : ats) {
            if (day != null && a.startsWith(day) && a.substring(11).compareTo(at) <= 0) {
                hit = a;
            }
        }
        if (hit == null) {
            return null;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("date", day);
        m.put("at", hit.substring(11));
        return m;
    }

    private static List<List<Object>> loadTick(Connection c, String at) throws SQLException {
        if (at == null) {
            return new ArrayList<>();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM kosdaq_tick WHERE at = ?")) {
            ps.setString(1, at);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GSON.fromJson(rs.getString(1), ROWS) : new ArrayList<>();
            }
        }
    }

    private static Map<Object, Object> volumes(List<List<Object>> rows) {
        Map<Object, Object> m = new HashMap<>();
        rows.forEach(r -> m.put(r.get(0), r.get(4)));
        return m;
    }

    private static Double tickRatio(long v, Object before) {
        long b = before == null ? 0 : Values.lng(before);
        return b > 0 ? Math.round(((double) v / b - 1) * 10000) / 100.0 : null;
    }

    private Connection openTicks() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc가 classpath에 없습니다", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + tickDb.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");
            st.execute(TICK_SCHEMA);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
