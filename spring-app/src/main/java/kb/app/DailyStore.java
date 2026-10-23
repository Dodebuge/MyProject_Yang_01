package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 내정보 데이터를 하루 한 번만 KB에서 조회해 SQLite(kb.db)에 저장합니다 (daily_store.py와 같은 파일·같은 테이블).
 * 같은 날(한국 시간) 다시 요청하면 KB를 부르지 않고 저장된 값을 돌려줍니다.
 * (kind, date)가 기본키라 같은 날 두 번 저장되지 않습니다. 동시에 처음 들어온 두 요청은 KB를 두 번 부를 수 있지만,
 * 먼저 저장된 값 하나만 남고 두 요청 모두 그 값을 돌려받습니다.
 */
@Repository
class DailyStore {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 정수는 Long, 소수는 Double로 읽어 Python 버전과 같은 JSON 숫자 모양을 유지합니다 (기본값은 모두 Double).
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();

    private final JdbcTemplate db;

    DailyStore(@Qualifier("kbDb") JdbcTemplate db) {
        this.db = db;
        db.execute("CREATE TABLE IF NOT EXISTS daily ("
                + " kind TEXT NOT NULL, date TEXT NOT NULL, fetched_at TEXT NOT NULL, payload TEXT NOT NULL,"
                + " PRIMARY KEY (kind, date))");
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
            db.update("INSERT INTO daily (kind, date, fetched_at, payload) VALUES (?, ?, ?, ?) ON CONFLICT (kind, date) " + onConflict,
                    kind, today, LocalDateTime.now(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), GSON.toJson(payload));
            return GSON.fromJson(select(kind, today), MAP);
        } catch (DataAccessException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
    }

    /** kinds로 저장된 날짜별 값 [(날짜, 값)]. 날짜순이고, 같은 날짜 안에서는 kind 이름순 (daily_store.history와 같음). */
    List<Map.Entry<String, Map<String, Object>>> history(String... kinds) throws IOException {
        String marks = String.join(",", Collections.nCopies(kinds.length, "?"));
        try {
            return db.query("SELECT date, payload FROM daily WHERE kind IN (" + marks + ") ORDER BY date, kind",
                    (rs, i) -> new AbstractMap.SimpleEntry<>(rs.getString(1), GSON.<Map<String, Object>>fromJson(rs.getString(2), MAP)),
                    (Object[]) kinds);
        } catch (DataAccessException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
    }

    private String select(String kind, String date) {
        List<String> r = db.queryForList("SELECT payload FROM daily WHERE kind = ? AND date = ?", String.class, kind, date);
        return r.isEmpty() ? null : r.get(0);
    }
}
