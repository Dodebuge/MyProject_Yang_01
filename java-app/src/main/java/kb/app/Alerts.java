package kb.app;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 시그널 알림 (alerts.py와 같은 동작·같은 alerts.db): 코스닥·러셀 2000의 주간 평균 거래량 증가율 top 30을 장중 5분마다 확인해,
 * 새로 생긴 상승(매수 쪽) 시그널을 ntfy(.env의 NTFY_TOPIC)와 브라우저(GET /api/alerts)로 알립니다.
 *
 * 순위는 30분마다만 다시 뽑고(전 종목 조회, 코스닥은 스팩 제외), top 30은 5분마다 약 1년 일봉을 새로 받아 Signals로 계산합니다.
 * 새 시그널: 직전 확인 때 없던 매수 쪽 시그널. 처음 보는 종목(서버 시작, top 30 새로 진입)은 오늘 일어난 교차만 알립니다.
 * 같은 날 같은 종목·같은 시그널은 한 번만 (alerts.db의 UNIQUE). 장중: 코스닥 09:05~15:35(한국), 러셀 2000 09:50~16:20(뉴욕).
 */
final class Alerts {

    static final int TOP = 30;
    static final int EVERY_MINUTES = 5;
    private static final int RANK_MINUTES = 30;  // 순위(전 종목 조회)는 30분마다만
    private static final int KEEP = 200;         // /api/alerts가 돌려주는 최근 알림 수
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter SLOT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Gson GSON = new Gson();
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS signal_alert ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, market TEXT NOT NULL, day TEXT NOT NULL, at TEXT NOT NULL,"
            + " code TEXT NOT NULL, name TEXT NOT NULL, text TEXT NOT NULL, d TEXT NOT NULL, ratio REAL, price REAL, change REAL,"
            + " sig TEXT NOT NULL, chart TEXT NOT NULL, UNIQUE (market, day, code, sig))";
    private static final String[] COLS = {"market", "day", "at", "code", "name", "text", "d", "ratio", "price", "change", "sig", "chart"};

    interface Rank {
        Map<String, Object> get() throws IOException;
    }

    /** 감시하는 시장 하나 (alerts.py Market). */
    static final class Market {
        final String key;
        final String name;
        final ZoneId zone;
        final LocalTime open;
        final LocalTime close;
        final Rank rank;                                                      // 주간 평균 응답 (stocks에 ratio)
        final Function<Map<String, Object>, List<Map<String, Object>>> daily; // 순위의 종목 -> 일봉 [{"d", "c"}] (오래된 날부터)
        final Function<Map<String, Object>, String> chart;                    // 차트 주소 쿼리
        final Predicate<Map<String, Object>> skip;                            // 순위에서 뺄 종목
        List<Map<String, Object>> top = new ArrayList<>();
        long rankedAt;
        final Map<String, Set<String>> prev = new ConcurrentHashMap<>();      // 종목 -> 직전 확인 때의 매수 쪽 시그널 열쇠
        String status = "대기";
        String checkedAt = "";

        Market(String key, String name, ZoneId zone, LocalTime open, LocalTime close, Rank rank,
               Function<Map<String, Object>, List<Map<String, Object>>> daily, Function<Map<String, Object>, String> chart,
               Predicate<Map<String, Object>> skip) {
            this.key = key;
            this.name = name;
            this.zone = zone;
            this.open = open;
            this.close = close;
            this.rank = rank;
            this.daily = daily;
            this.chart = chart;
            this.skip = skip;
        }
    }

    private final Path db;
    private final Map<String, String> env;
    final Map<String, Market> markets = new LinkedHashMap<>();
    private ScheduledExecutorService timer;
    /** 새 알림마다 부름 (자동매매가 시그널 종목을 사려고): (시장 kosdaq|russell, 알림) */
    volatile java.util.function.BiConsumer<String, Map<String, Object>> listener;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /**
     * 장중 5분마다 오늘 알림이 온 코스닥 종목의 [종목, 현재가, 체결강도, 총매도잔량, 총매수잔량, 가중평균가] 기록 (alerts.db kosdaq_flow).
     * 종목마다 KB를 두 번 불러(체결·호가) 전 종목이 아니라 알림 종목만. 지난 기록은 KB가 주지 않아 기록한 날부터 쌓임.
     */
    final Ticks flow;

    /** volume·russell이 null이면 시장 없이 (단위 테스트: 기록·중복 막기만). */
    Alerts(Volume volume, Russell russell, Path db, Map<String, String> env) {
        this.db = db;
        this.env = env;
        flow = new Ticks("코스닥 알림 종목 체결강도·호가", db, "kosdaq_flow", SEOUL, EVERY_MINUTES, LocalTime.of(9, 0), LocalTime.of(15, 40),
                at -> volume == null ? List.of() : firstTimes("kosdaq").getOrDefault(at.toLocalDate().toString(), Map.of()).keySet().stream()
                        .map(volume::flow).filter(java.util.Objects::nonNull).collect(Collectors.toList()));
        if (volume != null) {
            markets.put("kosdaq", new Market("kosdaq", "코스닥", SEOUL, LocalTime.of(9, 5), LocalTime.of(15, 35),
                    () -> volume.query(false), s -> candles(volume.daily(Values.str(s.get("code"))), "p2", 10),
                    s -> "market=kr&code=" + s.get("code"), s -> Values.str(s.get("name")).contains("스팩")));
        }
        if (russell != null) {
            markets.put("russell", new Market("russell", "러셀 2000", ZoneId.of("America/New_York"), LocalTime.of(9, 50), LocalTime.of(16, 20),
                    () -> russell.query(false),
                    s -> candles(russell.daily(Values.str(s.get("code")), Values.str(s.get("exchange"))), "p4", 1),
                    s -> "market=us&code=" + s.get("code") + "&exchange=" + Values.str(s.get("exchange")), s -> false));
        }
    }

    private static List<Map<String, Object>> candles(List<Map<String, Object>> rows, String suffix, double scale) {
        return rows == null ? null : Chart.candles(rows, suffix, scale);
    }

    /** 같은 시그널을 알아보는 열쇠. RSI 값처럼 바뀌는 숫자는 빼고, 교차는 일어난 날까지. */
    static String sigKey(Map<String, Object> g) {
        return Values.str(g.get("text")).replaceAll(" \\d+$", "") + "|" + Values.str(g.get("d"));
    }

    /** 직전 확인(prev) 때 없던 매수 쪽 시그널. 처음 보는 종목(prev null)은 오늘 일어난 교차만. */
    static List<Map<String, Object>> newSignals(List<Map<String, Object>> buys, Set<String> prev, String today) {
        return buys.stream().filter(g -> prev == null ? today.equals(g.get("d")) : !prev.contains(sigKey(g))).collect(Collectors.toList());
    }

    private boolean ntfyOn() {
        return !env.getOrDefault("NTFY_TOPIC", "").trim().isEmpty();
    }

    /** 장중 5분마다 확인 시작 (시장마다). */
    synchronized void start() {
        timer = Executors.newScheduledThreadPool(Math.max(1, markets.size()), r -> {
            Thread t = new Thread(r, "alerts");
            t.setDaemon(true);
            return t;
        });
        markets.values().forEach(this::scheduleNext);
        if (markets.containsKey("kosdaq")) {
            flow.start();
        }
        System.out.println("[시그널 알림] 코스닥·러셀 2000 증가율 top " + TOP + "을 장중 " + EVERY_MINUTES + "분마다 확인 (ntfy "
                + (ntfyOn() ? "켜짐" : "꺼짐: .env에 NTFY_TOPIC") + ")");
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
        flow.stop();
    }

    // 실행할 때마다 다음 시각을 다시 계산해 예약 (고정 주기로 두면 PC 절전·시계 보정 뒤 시각이 밀림)
    private synchronized void scheduleNext(Market m) {
        if (timer.isShutdown()) {
            return;
        }
        LocalDateTime at = Ticks.nextSlot(LocalDateTime.now(m.zone), EVERY_MINUTES, m.open, m.close);
        long delay = Math.max(0, Duration.between(ZonedDateTime.now(m.zone), at.atZone(m.zone)).toMillis());
        timer.schedule(() -> {
            try {
                for (Map<String, Object> a : check(m)) {
                    System.out.println("[시그널 알림] " + m.name + " " + a.get("name") + "(" + a.get("code") + ") " + a.get("text"));
                }
            } catch (IOException | SQLException | RuntimeException e) {  // 목록 파일 없음·KB 오류여도 다음 시각에 다시
                String status = "실패: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!status.equals(m.status)) {  // 같은 실패를 5분마다 찍지 않게
                    System.out.println("[시그널 알림] " + m.name + " " + status);
                }
                m.status = status;
            } finally {
                scheduleNext(m);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** 한 번 확인: (필요하면 순위를 다시 뽑고) top 30의 새 매수 쪽 시그널을 저장·알림. 새 알림 목록을 돌려줍니다. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> check(Market m) throws IOException, SQLException {
        String today = LocalDateTime.now(m.zone).format(DAY);
        if (!m.top.isEmpty()) {  // 휴장일이면 전 종목 순위 조회 없이 끝 (top 1위 일봉 하나로 확인)
            List<Map<String, Object>> first = m.daily.apply(m.top.get(0));
            if (first != null && !first.isEmpty() && !today.equals(first.get(first.size() - 1).get("d"))) {
                m.checkedAt = LocalDateTime.now(SEOUL).format(AT);
                m.status = "휴장 (오늘 일봉 없음)";
                return new ArrayList<>();
            }
        }
        if (m.top.isEmpty() || System.currentTimeMillis() - m.rankedAt > RANK_MINUTES * 60_000L) {
            List<Map<String, Object>> stocks = new ArrayList<>((List<Map<String, Object>>) m.rank.get().get("stocks"));
            stocks.removeIf(s -> s.get("ratio") == null || m.skip.test(s));
            stocks.sort(Comparator.comparingDouble(s -> -Values.num(s.get("ratio"))));
            m.top = new ArrayList<>(stocks.subList(0, Math.min(TOP, stocks.size())));
            m.rankedAt = System.currentTimeMillis();
        }
        List<List<Map<String, Object>>> charts = Values.parallel(m.top, 8, m.daily);
        m.checkedAt = LocalDateTime.now(SEOUL).format(AT);
        if (charts.stream().noneMatch(cs -> cs != null && !cs.isEmpty() && today.equals(cs.get(cs.size() - 1).get("d")))) {
            m.status = "휴장 (오늘 일봉 없음)";
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < m.top.size(); i++) {
            Map<String, Object> s = m.top.get(i);
            List<Map<String, Object>> cs = charts.get(i);
            if (cs == null) {
                continue;
            }
            List<Map<String, Object>> buys = Signals.of(cs).stream().filter(g -> (Integer) g.get("dir") > 0).collect(Collectors.toList());
            String code = Values.str(s.get("code"));
            for (Map<String, Object> g : newSignals(buys, m.prev.get(code), today)) {
                Map<String, Object> a = record(m, today, s, g);
                if (a != null) {
                    out.add(a);
                    notify(m, a);
                    if (listener != null) {
                        listener.accept(m.key, a);
                    }
                }
            }
            m.prev.put(code, buys.stream().map(Alerts::sigKey).collect(Collectors.toCollection(HashSet::new)));
        }
        m.status = "top " + m.top.size() + " 확인";
        return out;
    }

    /** 알림을 저장하고 돌려줍니다. 같은 날 같은 종목·시그널을 이미 알렸으면 null (서버 재시작·두 서버 동시 실행에도 한 번만). */
    Map<String, Object> record(Market m, String day, Map<String, Object> s, Map<String, Object> g) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("market", m.key);
        row.put("day", day);
        row.put("at", LocalDateTime.now(SEOUL).format(AT));
        row.put("code", Values.str(s.get("code")));
        row.put("name", Values.str(s.get("name")));
        row.put("text", g.get("text"));
        row.put("d", Values.str(g.get("d")));
        row.put("ratio", s.get("ratio"));
        row.put("price", s.get("price"));
        row.put("change", s.get("change"));
        row.put("sig", sigKey(g));
        row.put("chart", m.chart.apply(s));
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO signal_alert (" + String.join(", ", COLS) + ") VALUES ("
                     + String.join(", ", Collections.nCopies(COLS.length, "?")) + ")", Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < COLS.length; i++) {
                ps.setObject(i + 1, row.get(COLS[i]));
            }
            if (ps.executeUpdate() != 1) {
                return null;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("id", keys.next() ? keys.getLong(1) : null);
                out.putAll(row);
                return out;
            }
        }
    }

    /** ntfy로 보냅니다 (NTFY_TOPIC이 없으면 아무것도 안 함). 실패해도 알림 기록·브라우저 알림은 그대로. */
    void notify(Market m, Map<String, Object> a) {
        String topic = env.getOrDefault("NTFY_TOPIC", "").trim();
        if (topic.isEmpty()) {
            return;
        }
        String d = Values.str(a.get("d"));
        String when = d.isEmpty() ? "" : " " + Integer.parseInt(d.substring(4, 6)) + "/" + Integer.parseInt(d.substring(6));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put("title", "[" + m.name + "] " + a.get("name") + " ▲ " + a.get("text"));
        body.put("message", a.get("name") + " (" + a.get("code") + ") " + a.get("text") + when + "\n주간 거래량 "
                + String.format("%+.1f", Values.num(a.get("ratio"))) + "% · 현재가 " + Values.str(a.get("price"))
                + " (" + String.format("%+.2f", Values.num(a.get("change"))) + "%)");
        body.put("tags", Arrays.asList("chart_with_upwards_trend"));
        body.put("priority", 4);
        String app = env.getOrDefault("APP_URL", "").trim().replaceAll("/+$", "");
        if (!app.isEmpty()) {
            body.put("click", app + "/chart?" + a.get("chart"));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(env.getOrDefault("NTFY_SERVER", "https://ntfy.sh").replaceAll("/+$", "")))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                System.out.println("[시그널 알림] ntfy 보내기 실패: HTTP " + res.statusCode() + " " + res.body());
            }
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            System.out.println("[시그널 알림] ntfy 보내기 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * GET /api/alerts[?since=id] -> {"alerts": [최근 알림, 최신 먼저], "ntfy", "every", "top", "markets": {시장: 감시 상태},
     * "flow": 코스닥 알림 종목 체결강도·호가 마지막 기록 {at, rows}} (alerts.recent와 같음, flow는 Java 서버만)
     */
    Map<String, Object> recent(long since) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM signal_alert WHERE id > ? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, since);
            ps.setInt(2, KEEP);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        r.put(md.getColumnName(i), rs.getObject(i));
                    }
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            throw new IOException("alerts.db 저장소 오류: " + e.getMessage(), e);
        }
        Map<String, Object> ms = new LinkedHashMap<>();
        for (Market m : markets.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", m.name);
            x.put("status", m.status);
            x.put("checkedAt", m.checkedAt);
            x.put("next", Ticks.nextSlot(LocalDateTime.now(m.zone), EVERY_MINUTES, m.open, m.close).format(SLOT));
            x.put("top", m.top.stream().map(s -> {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("code", s.get("code"));
                t.put("name", s.get("name"));
                t.put("ratio", s.get("ratio"));
                return t;
            }).collect(Collectors.toList()));
            ms.put(m.key, x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alerts", rows);
        out.put("ntfy", ntfyOn());
        out.put("every", EVERY_MINUTES);
        out.put("top", TOP);
        out.put("markets", ms);
        out.put("flow", flow.last());
        return out;
    }

    /**
     * 시뮬레이션 '알림 종목만'용: {그 시장 현지 날짜 'yyyy-MM-dd': {종목: 첫 알림 현지 시각 'HH:mm'}} (alerts.first_times와 같음).
     * at은 한국 시간이라 시장 시간대(코스닥 서울, 러셀 뉴욕)로 바꿈.
     */
    Map<String, Map<String, String>> firstTimes(String market) throws IOException {
        ZoneId zone = "russell".equals(market) ? ZoneId.of("America/New_York") : SEOUL;
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT at, code FROM signal_alert WHERE market = ? ORDER BY at")) {
            ps.setString(1, market);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ZonedDateTime local = LocalDateTime.parse(rs.getString(1), AT).atZone(SEOUL).withZoneSameInstant(zone);
                    out.computeIfAbsent(local.toLocalDate().toString(), k -> new LinkedHashMap<>())
                            .putIfAbsent(rs.getString(2), local.toLocalTime().toString().substring(0, 5));
                }
            }
        } catch (SQLException e) {
            throw new IOException("alerts.db 저장소 오류: " + e.getMessage(), e);
        }
        return out;
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
            st.execute(SCHEMA);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
