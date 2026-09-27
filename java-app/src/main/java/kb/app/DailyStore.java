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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 내정보 데이터를 하루 한 번만 KB에서 조회해 SQLite(kb.db)에 저장합니다 (daily_store.py와 같은 파일·같은 테이블).
 * 같은 날(한국 시간) 다시 요청하면 KB를 부르지 않고 저장된 값을 돌려줍니다.
 * (kind, date)가 기본키라 같은 날 두 번 저장되지 않습니다. 동시에 처음 들어온 두 요청은 KB를 두 번 부를 수 있지만,
 * 먼저 저장된 값 하나만 남고 두 요청 모두 그 값을 돌려받습니다.
 */
final class DailyStore {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 정수는 Long, 소수는 Double로 읽어 Python 버전과 같은 JSON 숫자 모양을 유지합니다 (기본값은 모두 Double).
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS daily ("
            + " kind TEXT NOT NULL, date TEXT NOT NULL, fetched_at TEXT NOT NULL, payload TEXT NOT NULL,"
            + " PRIMARY KEY (kind, date))";

    private final String url;

    DailyStore(Path dbFile) {
        this.url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc가 classpath에 없습니다", e);
        }
    }

    interface Fetch {
        Map<String, Object> get() throws IOException;
    }

    /**
     * 오늘 저장된 kind 값을 돌려주고, 없으면 fetch로 조회해 저장한 뒤 돌려줍니다.
     * refresh가 true(화면의 'KB에서 다시 조회')면 저장된 값을 건너뛰고 다시 조회해 오늘 값을 덮어씁니다.
     */
    Map<String, Object> daily(String kind, boolean refresh, Fetch fetch) throws IOException {
        String today = LocalDate.now(SEOUL).toString();
        try {
            String stored = refresh ? null : select(kind, today);
            if (stored != null) {
                return GSON.fromJson(stored, MAP);
            }
            Map<String, Object> payload = fetch.get();  // KB 조회는 DB 연결 밖에서
            String onConflict = refresh ? "DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload" : "DO NOTHING";
            try (Connection c = open();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO daily (kind, date, fetched_at, payload) VALUES (?, ?, ?, ?) ON CONFLICT (kind, date) " + onConflict)) {
                ps.setString(1, kind);
                ps.setString(2, today);
                ps.setString(3, LocalDateTime.now(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                ps.setString(4, GSON.toJson(payload));
                ps.executeUpdate();
            }
            return GSON.fromJson(select(kind, today), MAP);
        } catch (SQLException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
    }

    private String select(String kind, String date) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT payload FROM daily WHERE kind = ? AND date = ?")) {
            ps.setString(1, kind);
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");  // 다른 요청이 쓰는 중이면 기다림 (Python timeout=30과 같음)
            st.execute(SCHEMA);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
