package kb.app;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 보유 현황을 4시간마다(한국 시간 00·04·08·12·16·20시) KB·토스에서 조회해 MySQL/MariaDB에 기록합니다 (snapshots.py와 같은 테이블).
 * .env(또는 환경변수)에 MYSQL_HOST가 있을 때만 켜집니다. 없으면 기록하지 않고 그래프는 kb.db의 하루 기록만 씁니다.
 * 테이블은 처음 접속할 때 만들고, 데이터베이스·계정은 미리 만들어 두세요 (deploy/README.md).
 */
final class Snapshots {

    static final int INTERVAL_HOURS = 4;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS holdings_snapshot ("
            + " at DATETIME NOT NULL PRIMARY KEY, total BIGINT NOT NULL, stock BIGINT NOT NULL, cash BIGINT NOT NULL,"
            + " kb BIGINT NOT NULL, kb_stock BIGINT NOT NULL, toss BIGINT NULL, toss_stock BIGINT NULL)";
    // 응답 키 -> 열 이름 (순서 고정)
    private static final String[][] COLS = {{"total", "total"}, {"stock", "stock"}, {"cash", "cash"}, {"kb", "kb"},
            {"kbStock", "kb_stock"}, {"toss", "toss"}, {"tossStock", "toss_stock"}};

    private final Heatmap heatmap;
    private final String url, user, password;  // url이 null이면 꺼짐
    private ScheduledExecutorService timer;

    Snapshots(Heatmap heatmap, Path envFile) throws IOException {
        this.heatmap = heatmap;
        Map<String, String> env = KBClient.readEnvFile(envFile);
        env.putAll(System.getenv());
        String host = env.getOrDefault("MYSQL_HOST", "");
        String port = env.getOrDefault("MYSQL_PORT", "");
        String db = env.getOrDefault("MYSQL_DATABASE", "");
        this.url = host.isEmpty() ? null : "jdbc:mysql://" + host + ":" + (port.isEmpty() ? "3306" : port) + "/"
                + (db.isEmpty() ? "kb_openapi" : db) + "?connectTimeout=10000&socketTimeout=60000&characterEncoding=utf8";
        this.user = env.getOrDefault("MYSQL_USER", "");
        this.password = env.getOrDefault("MYSQL_PASSWORD", "");
    }

    /** 4시간마다 기록 시작. MySQL 설정이 없으면 아무것도 안 함. */
    synchronized void start() {
        if (url == null) {
            System.out.println("[기록] MYSQL_HOST 없음 -> 4시간 보유 금액 기록 안 함");
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "snapshots");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
        System.out.println("[기록] " + url.replaceAll("\\?.*", "") + " 에 " + INTERVAL_HOURS + "시간마다 보유 금액 기록 (다음 "
                + nextRun(LocalDateTime.now(SEOUL)).format(DateTimeFormatter.ofPattern("HH:mm")) + ")");
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** now 다음의 4시간 정각 (00·04·…·20시). */
    static LocalDateTime nextRun(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.HOURS).withHour(now.getHour() - now.getHour() % INTERVAL_HOURS).plusHours(INTERVAL_HOURS);
    }

    // 실행할 때마다 다음 정각을 다시 계산해 예약 (고정 주기로 두면 PC 절전·시계 보정 뒤 시각이 밀림)
    private synchronized void scheduleNext() {
        if (timer.isShutdown()) {
            return;
        }
        LocalDateTime at = nextRun(LocalDateTime.now(SEOUL));
        long delay = Math.max(0, Duration.between(LocalDateTime.now(SEOUL), at).toMillis());
        timer.schedule(() -> {
            try {
                Map<String, Object> summary = Heatmap.summarize(at.format(AT), heatmap.queryHoldings(true));
                save(summary);
                System.out.println("[기록] " + summary.get("date") + " 총자산 " + summary.get("total") + "원 저장");
            } catch (IOException | SQLException | RuntimeException e) {  // KB·MySQL 오류여도 다음 시각에 다시
                System.out.println("[기록] " + at.format(AT) + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                scheduleNext();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private Connection open() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new SQLException("mysql-connector-j가 classpath에 없습니다", e);
        }
        Connection c = DriverManager.getConnection(url, user, password);
        try (Statement st = c.createStatement()) {
            st.execute(SCHEMA);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    void save(Map<String, Object> summary) throws SQLException {
        StringBuilder cols = new StringBuilder(), marks = new StringBuilder(), update = new StringBuilder();
        for (String[] col : COLS) {
            cols.append(", ").append(col[1]);
            marks.append(", ?");
            update.append(update.length() == 0 ? "" : ", ").append(col[1]).append(" = VALUES(").append(col[1]).append(')');
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("INSERT INTO holdings_snapshot (at" + cols + ") VALUES (?" + marks
                     + ") ON DUPLICATE KEY UPDATE " + update)) {
            ps.setString(1, ((String) summary.get("date")).replace('T', ' '));
            for (int i = 0; i < COLS.length; i++) {
                ps.setObject(i + 2, summary.get(COLS[i][0]));
            }
            ps.executeUpdate();
        }
    }

    List<Map<String, Object>> load() throws SQLException {
        StringBuilder cols = new StringBuilder();
        for (String[] col : COLS) {
            cols.append(", ").append(col[1]);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT at" + cols + " FROM holdings_snapshot ORDER BY at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", rs.getTimestamp(1).toLocalDateTime().format(AT));
                for (int i = 0; i < COLS.length; i++) {
                    long v = rs.getLong(i + 2);
                    row.put(COLS[i][0], rs.wasNull() ? null : v);
                }
                out.add(row);
            }
        }
        return out;
    }

    /** kb.db의 하루 기록 + 4시간 기록. 4시간 기록이 있는 날은 하루 기록을 빼고 4시간 기록만 씁니다. */
    static List<Map<String, Object>> merge(List<Map<String, Object>> days, List<Map<String, Object>> snaps) {
        Set<String> covered = new HashSet<>();
        snaps.forEach(s -> covered.add(((String) s.get("date")).substring(0, 10)));
        List<Map<String, Object>> out = new ArrayList<>(snaps);
        days.stream().filter(d -> !covered.contains((String) d.get("date"))).forEach(out::add);
        out.sort(Comparator.comparing(d -> (String) d.get("date")));
        return out;
    }

    /**
     * DB 접속 점검 (spring-app/run.sh dbcheck). .env의 MYSQL_* 로 접속해 테이블 생성, 쓰기·읽기(되돌림), 기록 현황을 확인합니다.
     * 시험 쓰기는 트랜잭션 안에서 하고 되돌리므로 기록이 남지 않습니다. 성공하면 null, 실패하면 이유 (출력도 함).
     */
    String check() {
        if (url == null) {
            String why = "MYSQL_HOST가 없습니다. .env에 MYSQL_HOST·MYSQL_USER·MYSQL_PASSWORD를 넣으세요 (deploy/install-mariadb.sh).";
            System.out.println("[실패] " + why);
            return why;
        }
        System.out.println("설정    " + user + " @ " + url.replaceAll("\\?.*", "") + " (비밀번호 " + (password.isEmpty() ? "없음" : "있음") + ")");
        try (Connection c = open()) {  // 접속 + CREATE TABLE IF NOT EXISTS
            System.out.println("[성공] 접속·테이블  " + c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO holdings_snapshot (at, total, stock, cash, kb, kb_stock) VALUES ('2000-01-01 00:00:00', 1, 1, 0, 1, 1)"
                        + " ON DUPLICATE KEY UPDATE total = 1");
                try (ResultSet rs = st.executeQuery("SELECT total FROM holdings_snapshot WHERE at = '2000-01-01 00:00:00'")) {
                    if (!rs.next() || rs.getLong(1) != 1) {
                        throw new SQLException("쓴 값을 다시 읽지 못했습니다");
                    }
                }
            } finally {
                c.rollback();  // 시험 쓰기는 남기지 않음
            }
            System.out.println("[성공] 쓰기·읽기 (되돌림)");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*), MAX(at) FROM holdings_snapshot")) {
                rs.next();
                System.out.println("기록    " + rs.getLong(1) + "건" + (rs.getTimestamp(2) == null ? "" : ", 마지막 " + rs.getTimestamp(2).toLocalDateTime().format(AT))
                        + " · 다음 기록 " + nextRun(LocalDateTime.now(SEOUL)).format(AT));
            }
            return null;
        } catch (SQLException e) {
            String why = user + " @ " + url.replaceAll("\\?.*", "") + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("[실패] " + why);
            return why;
        }
    }

    /** java -cp kb-openapi-spring.jar kb.app.Snapshots  (spring-app/run.sh dbcheck). .env는 KB_DATA_DIR(기본 현재 폴더)에서 읽음. */
    public static void main(String[] args) throws IOException {
        String dir = System.getenv("KB_DATA_DIR");
        Path env = Path.of(dir == null || dir.isEmpty() ? "." : dir).resolve(".env");
        System.out.println(".env    " + env.toAbsolutePath().normalize());
        System.exit(new Snapshots(null, env).check() == null ? 0 : 1);
    }

    /** GET /api/holdings-history : 하루 기록에 MySQL 4시간 기록을 합침. MySQL이 안 되면 하루 기록만 돌려주고 이유를 snapshotError에. */
    @SuppressWarnings("unchecked")
    Map<String, Object> history() throws IOException {
        Map<String, Object> payload = heatmap.holdingsHistory();
        if (url == null) {
            return payload;
        }
        try {
            payload.put("days", merge((List<Map<String, Object>>) payload.get("days"), load()));
            payload.put("interval", INTERVAL_HOURS);
        } catch (SQLException e) {  // MySQL이 꺼져 있어도 그래프는 보이게
            payload.put("snapshotError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return payload;
    }
}
