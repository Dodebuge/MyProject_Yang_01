package kb.app;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

/**
 * 시장 모니터(/monitor) 시세 저장소 (monitor.py와 같은 파일·같은 테이블).
 * 화면이 열릴 때마다 지수·추가 종목의 일봉을 KB에서 하나씩 받으면 수 초 걸려, 5분마다 미리 받아
 * SQLite(kb.db)의 monitor_quotes 테이블에 저장해 두고 화면은 저장된 값을 바로 받습니다 (GET /api/monitor-quotes).
 * 종목 목록이 바뀌면(POST /api/monitor) 다음 5분을 기다리지 않고 곧바로 다시 받습니다.
 */
final class Monitor {

    static final int EVERY_SECONDS = 300;
    static final int KEEP_CANDLES = 40;  // 월간 대비(1달 전 ≈ 22거래일)에 넉넉하게
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final java.lang.reflect.Type MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS monitor_quotes ("
            + " key TEXT PRIMARY KEY, fetched_at TEXT NOT NULL, payload TEXT NOT NULL)";

    // 지수 히트맵 칸. KB OpenAPI에 지수 시세가 없어 추종 ETF로 봄 (Trader INDEX와 같은 방식)
    // ponytail: 지수 대신 추종 ETF. 정확한 지수 값이 필요하면 KB 지수 시세 TR로 바꿀 것
    static final List<Map<String, Object>> INDICES = List.of(
            index("코스피", "kr", "069500", "", "KODEX 200"),
            index("코스닥", "kr", "229200", "", "KODEX 코스닥150"),
            index("S&P 500", "us", "SPY", "AMX", "SPY"),
            index("나스닥", "us", "QQQ", "NAS", "QQQ (나스닥 100)"),
            index("다우", "us", "DIA", "AMX", "DIA"),
            index("러셀 2000", "us", "IWM", "AMX", "IWM"));

    private static Map<String, Object> index(String name, String market, String code, String exchange, String etf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("market", market);
        m.put("code", code);
        m.put("exchange", exchange);
        m.put("etf", etf);
        return Collections.unmodifiableMap(m);
    }

    /** 일봉 조회 (운영은 chart.chart(..., save=false), 테스트는 가짜). */
    interface Quote {
        Map<String, Object> chart(String market, String code, String exchange) throws IOException;
    }

    private final Chart chart;
    private final Quote quote;
    private final String url;
    private ScheduledExecutorService timer;

    Monitor(Chart chart, Path dbFile, Quote quote) {
        this.chart = chart;
        this.quote = quote;
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc가 classpath에 없습니다", e);
        }
    }

    Monitor(Chart chart, Path dbFile) {
        this(chart, dbFile, (m, c, x) -> chart.chart(m, c, x, false));
    }

    /** GET /api/monitor : 지수 칸과 사용자가 추가한 종목. */
    Map<String, Object> listing() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indices", INDICES);
        out.putAll(chart.monitorItems());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> one(Map<String, Object> ix) {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> cs = (List<Map<String, Object>>) quote.chart(
                    Values.str(ix.get("market")), Values.str(ix.get("code")), Values.str(ix.get("exchange"))).get("candles");
            List<Map<String, Object>> slim = new ArrayList<>();
            for (Map<String, Object> c : cs.subList(Math.max(0, cs.size() - KEEP_CANDLES), cs.size())) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("d", c.get("d"));
                k.put("c", c.get("c"));
                slim.add(k);
            }
            p.put("candles", slim);
        } catch (Exception e) {  // 한 종목이 실패해도 나머지는 저장
            p.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return p;
    }

    /** 지수·추가 종목 일봉을 KB에서 받아 저장하고, 저장된 전체를 돌려줍니다. 목록에서 빠진 종목은 지웁니다. */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> refresh() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>(INDICES);
        items.addAll((List<Map<String, Object>>) chart.monitorItems().get("items"));
        List<Map<String, Object>> got = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, Object>>> jobs = new ArrayList<>();
            for (Map<String, Object> ix : items) {
                jobs.add(pool.submit(() -> one(ix)));
            }
            for (Future<Map<String, Object>> f : jobs) {
                got.add(f.get());
            }
        } catch (Exception e) {
            throw new IOException("모니터 시세 조회 중단: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
        String now = LocalDateTime.now(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        List<String> keys = new ArrayList<>();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO monitor_quotes (key, fetched_at, payload) VALUES (?, ?, ?)"
                    + " ON CONFLICT (key) DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload")) {
                for (int i = 0; i < items.size(); i++) {
                    String key = items.get(i).get("market") + ":" + items.get(i).get("code");
                    keys.add(key);
                    ps.setString(1, key);
                    ps.setString(2, now);
                    ps.setString(3, GSON.toJson(got.get(i)));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM monitor_quotes WHERE key NOT IN ("
                    + String.join(",", Collections.nCopies(keys.size(), "?")) + ")")) {
                for (int i = 0; i < keys.size(); i++) {
                    ps.setString(i + 1, keys.get(i));
                }
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
        return quotes();
    }

    /** GET /api/monitor-quotes : 저장된 시세 (KB 조회 없음). {"quotes": {key: {"fetchedAt", "candles" 또는 "error"}}} */
    Map<String, Object> quotes() throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        try (Connection c = open();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT key, fetched_at, payload FROM monitor_quotes")) {
            while (rs.next()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("fetchedAt", rs.getString(2));
                v.putAll(GSON.fromJson(rs.getString(3), MAP));
                q.put(rs.getString(1), v);
            }
        } catch (SQLException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
        return Collections.singletonMap("quotes", q);
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");  // 다른 요청이 쓰는 중이면 기다림 (Python timeout=30과 같음)
            st.execute(SCHEMA);
        }
        return c;
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {  // DB·네트워크 오류여도 다음에 다시
            System.out.println("[모니터] 시세 저장 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 종목 목록이 바뀜: 다음 5분을 기다리지 않고 곧바로 다시 받음. */
    synchronized void changed() {
        if (timer != null) {
            timer.execute(this::refreshQuietly);
        }
    }

    /** 바로 한 번, 이후 5분마다 refresh. */
    // ponytail: 장 시간과 상관없이 5분마다 (지수·종목 수십 개라 부담이 작음). 줄이려면 국내·미국 장중에만
    synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(this::refreshQuietly, 0, EVERY_SECONDS, TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }
}
