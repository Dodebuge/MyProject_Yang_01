package kb.app;

import com.google.gson.Gson;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 코스닥 전 종목 1분봉 수집 (minutes.db, Spring·Java 서버만). 장중 기록(Ticks, 5분 현재가)과 달리 1분마다 시가·고가·저가·종가·거래량이 있어
 * 분봉 익절·손절·이평 같은 시뮬레이션에 씁니다.
 * 평일 NXT 시간외가 끝난 뒤(20:10)에 그날 1분봉을 종목마다 받아 그날 것만 저장. 종목 목록은 거래대금 상위(IVU10210, 코스닥 전부 약 1,700).
 * API 부담을 나누려고 목록의 짝수 번째는 KB, 홀수 번째는 토스로 받습니다 (토스 키가 없으면 모두 KB, 토스가 실패한 종목은 KB로 다시).
 *   KB: IVS11560 1분봉 1,500개(하루 08:00~20:00 + 전날) 한 번. 토스: /api/v1/candles interval 1m 200개씩 그날 첫 봉까지 (하루 약 4번).
 *   토스 시각은 봉이 끝난 시각이라 1분 빼서 KB처럼 봉 시작 시각으로 맞추고, 거래가 없던 분(거래량 0)은 둘 다 뺍니다 (KB는 0 봉도 줌).
 *   10-22 비교: 거래가 있는 봉의 82%가 같음. 토스에는 08시 NXT 프리마켓 봉이 더 있고, 거래량이 KB보다 큰 봉이 있음(KRX·NXT 통합으로 보임).
 *   토스에는 거래대금이 없어 (고가+저가+종가)/3 × 거래량으로 추정합니다 (source 열 'toss').
 * KB·토스 각각 4개씩 동시에 불러 몇 분 걸립니다.
 * 표 kosdaq_min1(day 'yyyy-MM-dd', code, name, source 'kb'|'toss', bars JSON [[HHMM, 시가, 고가, 저가, 종가, 거래량, 거래대금(원)], ...] 시각순, 08:00~20:00).
 * 30일 보관 (하루 약 20MB).
 * 이미 받은 종목(거래가 없던 종목은 빈 목록)은 건너뛰어, 서버를 늦게 켜거나 중간에 실패해도 시작할 때 마지막 거래일을 이어 받습니다. 그날 1분봉이 하나도 없으면 휴장일로 보고 저장 안 함.
 */
final class Minutes {

    static final LocalTime AT = LocalTime.of(20, 10);  // NXT 시간외(~20:00)까지 끝난 뒤
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int KEEP_DAYS = 30;           // 하루 수십 MB
    private static final int WORKERS = 4;
    private static final int CHUNK = 100;              // 이만큼 받을 때마다 저장 (중간에 멈춰도 이어 받게)
    private static final Gson GSON = new Gson();

    /** 토스 GET (운영은 TossClient.get, 테스트는 가짜). */
    interface Toss {
        Object get(String path, Map<String, String> params) throws IOException;
    }

    private final Monitor.Tr tr;
    private final Toss toss;  // null이면 모두 KB
    private final Path db;
    private ScheduledExecutorService timer;

    Minutes(Monitor.Tr tr, Toss toss, Path db) {
        this.tr = tr;
        this.toss = toss;
        this.db = db;
    }

    Minutes(KBClient client, TossClient toss, Path db) {
        this(client::call, toss == null ? null : (path, q) -> toss.get(path, q, false), db);
    }

    /** 매일 20:10 수집 시작. 마지막 거래일(지금이 20:10 전이면 직전 평일)을 아직 다 받지 않았으면 곧바로 받음. */
    synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kosdaq-min1");
            t.setDaemon(true);
            return t;
        });
        LocalDateTime now = LocalDateTime.now(SEOUL);
        LocalDate last = now.toLocalTime().isBefore(AT) ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        while (last.getDayOfWeek() == DayOfWeek.SATURDAY || last.getDayOfWeek() == DayOfWeek.SUNDAY) {
            last = last.minusDays(1);
        }
        LocalDate first = last;
        timer.execute(() -> run(first));
        scheduleNext();
        System.out.println("[코스닥 1분봉] 평일 " + AT + "마다 전 종목 1분봉 수집 -> " + db.getFileName() + " (지금 " + first + " 이어 받기)");
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private synchronized void scheduleNext() {
        if (timer.isShutdown()) {
            return;
        }
        LocalDateTime next = nextRun(LocalDateTime.now(SEOUL));
        long delay = Math.max(0, Duration.between(LocalDateTime.now(SEOUL), next).toMillis());
        timer.schedule(() -> {
            try {
                run(next.toLocalDate());
            } finally {
                scheduleNext();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** now 다음의 평일 20:10. */
    static LocalDateTime nextRun(LocalDateTime now) {
        LocalDateTime t = now.toLocalDate().atTime(AT);
        if (!t.isAfter(now)) {
            t = t.plusDays(1);
        }
        while (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) {
            t = t.plusDays(1);
        }
        return t;
    }

    private void run(LocalDate day) {
        try {
            long t0 = System.currentTimeMillis();
            int[] r = collect(day);
            System.out.println("[코스닥 1분봉] " + day + " " + (r[0] == 0 && r[1] == 0 ? "휴장 또는 이미 다 받음"
                    : r[0] + "종목 저장 (토스 " + r[2] + ")" + (r[1] > 0 ? ", " + r[1] + "종목 실패(다음 시작 때 다시)" : ""))
                    + " · " + (System.currentTimeMillis() - t0) / 1000 + "초");
        } catch (IOException | SQLException | RuntimeException e) {
            System.out.println("[코스닥 1분봉] " + day + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** day의 코스닥 전 종목 1분봉을 받아 저장 (이미 받은 종목은 건너뜀) -> {저장한 종목 수, 실패한 종목 수, 그중 토스로 받은 수}. 휴장일이면 {0, 0, 0}. */
    int[] collect(LocalDate day) throws IOException, SQLException {
        List<Map<String, Object>> listed = KBClient.records(tr.call("IVU10210", Heatmap.body("srt_clsf", "", "inq_cnt", "3000",
                "excg_clsf", "0", "mkt_clsf", "3", "thdy_bdy_clsf", "")), "out2");
        Set<String> have = new HashSet<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT code FROM kosdaq_min1 WHERE day = ?")) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    have.add(rs.getString(1));
                }
            }
        }
        List<String[]> todo = new ArrayList<>();
        for (Map<String, Object> r : listed) {
            String code = Values.str(r.get("is_cd"));
            if (code.length() == 6 && !have.contains(code)) {
                todo.add(new String[] {code, Values.str(r.get("is_nm"))});
            }
        }
        int saved = 0;
        int failed = 0;
        int byToss = 0;
        ExecutorService kbPool = Executors.newFixedThreadPool(WORKERS);
        ExecutorService tossPool = Executors.newFixedThreadPool(WORKERS);
        try {
            for (int i = 0; i < todo.size(); i += CHUNK) {
                List<Future<Object[]>> got = new ArrayList<>();
                for (int k = i; k < Math.min(todo.size(), i + CHUNK); k++) {
                    String code = todo.get(k)[0];
                    boolean viaToss = toss != null && k % 2 == 1;  // 절반은 토스
                    got.add((viaToss ? tossPool : kbPool).submit(() -> {
                        List<List<Object>> b = viaToss ? tossBars(code, day) : null;
                        return b != null ? new Object[] {b, "toss"} : new Object[] {bars(code, day), "kb"};  // 토스가 실패하면 KB로
                    }));
                }
                List<Object[]> rows = new ArrayList<>();
                for (int k = 0; k < got.size(); k++) {
                    Object[] r = got.get(k).get();
                    String[] s = todo.get(i + k);
                    if (r[0] == null) {
                        failed++;
                    } else {
                        rows.add(new Object[] {s[0], s[1], r[1], GSON.toJson(r[0])});  // 거래가 없던 종목은 [] (받은 것으로 표시)
                        byToss += "toss".equals(r[1]) ? 1 : 0;
                    }
                }
                if (i == 0 && have.isEmpty() && failed < got.size() && rows.stream().allMatch(r -> "[]".equals(r[3]))) {
                    return new int[] {0, 0, 0};  // 거래대금 상위 100종목이 모두 그날 1분봉이 없음 = 휴장일
                }
                save(day, rows);
                saved += rows.size();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause());
        } finally {
            kbPool.shutdownNow();
            tossPool.shutdownNow();
        }
        return new int[] {saved, failed, byToss};
    }

    /** 토스 1분봉 (KB bars와 같은 모양). 200개씩 그날 첫 봉 앞까지. 오류가 이어지면 null (KB로 다시). */
    @SuppressWarnings("unchecked")
    private List<List<Object>> tossBars(String code, LocalDate day) {
        String d = day.toString();
        List<List<Object>> out = new ArrayList<>();
        String before = null;
        for (int page = 0; page < 10; page++) {  // 하루 08:00~20:00 약 720개 = 4페이지 (+ 오늘 이후 봉이 있을 때 여유)
            Map<String, String> q = new java.util.LinkedHashMap<>();
            q.put("symbol", code);
            q.put("interval", "1m");
            q.put("count", "200");
            if (before != null) {
                q.put("before", before);
            }
            Map<String, Object> r = null;
            for (int attempt = 0; attempt < 3 && r == null; attempt++) {
                try {
                    r = (Map<String, Object>) toss.get("/api/v1/candles", q);
                } catch (IOException | RuntimeException e) {
                    // 다시
                }
            }
            if (r == null) {
                return null;
            }
            List<Map<String, Object>> cs = r.get("candles") instanceof List ? (List<Map<String, Object>>) r.get("candles") : List.of();
            boolean older = false;
            for (Map<String, Object> c : cs) {
                String ts = Values.str(c.get("timestamp"));  // 2026-10-22T09:01:00.000+09:00 = 09:00~09:01 봉
                if (ts.length() < 16) {
                    continue;
                }
                LocalDateTime end = LocalDateTime.parse(ts.substring(0, 16));
                LocalDateTime start = end.minusMinutes(1);
                older |= start.toLocalDate().isBefore(day);
                double v = Values.num(c.get("volume"));
                if (!start.toLocalDate().toString().equals(d) || v <= 0) {
                    continue;
                }
                double h = Values.num(c.get("highPrice")), l = Values.num(c.get("lowPrice")), cl = Values.num(c.get("closePrice"));
                out.add(List.of(start.format(DateTimeFormatter.ofPattern("HHmm")), Values.num(c.get("openPrice")), h, l, cl, v,
                        (double) Math.round((h + l + cl) / 3 * v)));  // 거래대금 추정
            }
            before = Values.str(r.get("nextBefore"));
            if (older || cs.isEmpty() || before.isEmpty()) {
                break;
            }
        }
        out.sort((a, b) -> ((String) a.get(0)).compareTo((String) b.get(0)));
        return out;
    }

    /** 한 종목의 day 1분봉 [[HHMM, 시가, 고가, 저가, 종가, 거래량, 거래대금]]. 그날 봉이 없으면 빈 목록(거래 없음), KB 오류가 이어지면 null. */
    private List<List<Object>> bars(String code, LocalDate day) {
        String d = day.format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<Map<String, Object>> rows = KBClient.records(tr.call("IVS11560", Heatmap.body("info_ccd", "1", "mkt_clsf", "1",
                        "chrt_clsf", "B", "minute_tck_indx", "1", "is_cd", code, "inq_clsf", "2", "strt_dy", "", "inq_cnt", "1500")), "out2");
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> c : Chart.candles(rows, "p2", 1)) {  // 시각순, 깨진 행·중복 빼고
                    // KB는 거래가 없던 분도 거래량 0 봉으로 줌 (전체의 약 60%) -> 토스처럼 뺌. 가끔 오는 깨진 시각(0000, 7300 등)도 뺌
                    String t = (String) c.get("t");
                    if (d.equals(c.get("d")) && (double) c.get("v") > 0 && t.compareTo("0800") >= 0 && t.compareTo("2000") <= 0
                            && Integer.parseInt(t.substring(2)) < 60) {
                        out.add(List.of(c.get("t"), c.get("o"), c.get("h"), c.get("l"), c.get("c"), c.get("v"), c.get("amt")));
                    }
                }
                return out;
            } catch (KBApiException | IOException e) {
                // 다시
            }
        }
        return null;
    }

    private void save(LocalDate day, List<Object[]> rows) throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement put = c.prepareStatement("INSERT OR REPLACE INTO kosdaq_min1 (day, code, name, source, bars) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement old = c.prepareStatement("DELETE FROM kosdaq_min1 WHERE day < ?")) {
                for (Object[] r : rows) {
                    put.setString(1, day.toString());
                    put.setString(2, (String) r[0]);
                    put.setString(3, (String) r[1]);
                    put.setString(4, (String) r[2]);
                    put.setString(5, (String) r[3]);
                    put.addBatch();
                }
                put.executeBatch();
                old.setString(1, day.minusDays(KEEP_DAYS).toString());
                old.executeUpdate();
            }
            c.commit();
        }
    }

    /** 저장된 한 종목·하루 1분봉 (없으면 빈 목록). 시험·분석용 */
    List<List<Object>> load(LocalDate day, String code) throws SQLException {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT bars FROM kosdaq_min1 WHERE day = ? AND code = ?")) {
            ps.setString(1, day.toString());
            ps.setString(2, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GSON.fromJson(rs.getString(1), List.class) : List.of();
            }
        }
    }

    private Connection connect() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc가 classpath에 없습니다", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");
            st.execute("CREATE TABLE IF NOT EXISTS kosdaq_min1 (day TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL,"
                    + " source TEXT NOT NULL, bars TEXT NOT NULL, PRIMARY KEY (day, code))");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
