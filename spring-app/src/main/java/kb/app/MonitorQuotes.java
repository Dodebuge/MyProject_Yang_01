package kb.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 시장 모니터 시세 저장소: kb.db의 monitor_quotes (monitor.py와 같은 표). key -> {fetched_at, payload JSON} */
@Repository
class MonitorQuotes {

    private final JdbcTemplate db;

    MonitorQuotes(@Qualifier("kbDb") JdbcTemplate db) {
        this.db = db;
        db.execute("CREATE TABLE IF NOT EXISTS monitor_quotes (key TEXT PRIMARY KEY, fetched_at TEXT NOT NULL, payload TEXT NOT NULL)");
    }

    /** payloads(key -> JSON)로 모두 바꿉니다: 있으면 덮어쓰고, 목록에서 빠진 key는 지움. 화면이 반쯤 바뀐 값을 읽지 않게 한 트랜잭션으로 */
    @Transactional("kbTx")
    public void replaceAll(String fetchedAt, Map<String, String> payloads) {
        List<Object[]> rows = new ArrayList<>();
        payloads.forEach((k, v) -> rows.add(new Object[] {k, fetchedAt, v}));
        db.batchUpdate("INSERT INTO monitor_quotes (key, fetched_at, payload) VALUES (?, ?, ?)"
                + " ON CONFLICT (key) DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload", rows);
        db.update("DELETE FROM monitor_quotes WHERE key NOT IN (" + String.join(",", Collections.nCopies(payloads.size(), "?")) + ")",
                payloads.keySet().toArray());
    }

    /** key -> [fetched_at, payload JSON] */
    Map<String, String[]> findAll() {
        Map<String, String[]> out = new LinkedHashMap<>();
        db.query("SELECT key, fetched_at, payload FROM monitor_quotes",
                rs -> {
                    out.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
                });
        return out;
    }
}
