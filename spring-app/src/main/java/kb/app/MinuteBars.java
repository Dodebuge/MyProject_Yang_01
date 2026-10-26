package kb.app;

import com.google.gson.Gson;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 코스닥 1분봉 저장소: minutes.db의 kosdaq_min1 (day 'yyyy-MM-dd', code, name, source 'kb'|'toss',
 * bars JSON [[HHMM, 시가, 고가, 저가, 종가, 거래량, 거래대금(원)], ...] 시각순). KEEP_DAYS일 보관.
 */
@Repository
class MinuteBars {

    static final int KEEP_DAYS = 30;  // 하루 약 20MB
    private static final Gson GSON = new Gson();

    private final JdbcTemplate db;

    MinuteBars(@Qualifier("minutesDb") JdbcTemplate db) {
        this.db = db;
        db.execute("CREATE TABLE IF NOT EXISTS kosdaq_min1 (day TEXT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL,"
                + " source TEXT NOT NULL, bars TEXT NOT NULL, PRIMARY KEY (day, code))");
    }

    /** day에 이미 받은 종목 */
    Set<String> codes(LocalDate day) {
        return new HashSet<>(db.queryForList("SELECT code FROM kosdaq_min1 WHERE day = ?", String.class, day.toString()));
    }

    /** rows: {종목, 이름, source, bars JSON}. 저장하고 KEEP_DAYS일 지난 날은 지움 (한 트랜잭션) */
    @Transactional("minutesTx")
    public void saveAll(LocalDate day, List<Object[]> rows) {
        db.batchUpdate("INSERT OR REPLACE INTO kosdaq_min1 (day, code, name, source, bars) VALUES (?, ?, ?, ?, ?)",
                rows.stream().map(r -> new Object[] {day.toString(), r[0], r[1], r[2], r[3]}).collect(Collectors.toList()));
        db.update("DELETE FROM kosdaq_min1 WHERE day < ?", day.minusDays(KEEP_DAYS).toString());
    }

    /** 저장된 날짜 (오래된 순) */
    List<String> days() {
        return db.queryForList("SELECT DISTINCT day FROM kosdaq_min1 ORDER BY day", String.class);
    }

    /** day의 전 종목 (Scalp 단타 시뮬레이션). 거래가 없던 종목은 뺌 */
    List<Scalp.Stock> stocks(String day) {
        return db.query("SELECT code, name, source, bars FROM kosdaq_min1 WHERE day = ? AND bars <> '[]'",
                (rs, i) -> Scalp.parse(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), day);
    }

    /** 저장된 한 종목·하루 1분봉 (없으면 빈 목록). 시험·분석용 */
    @SuppressWarnings("unchecked")
    List<List<Object>> load(LocalDate day, String code) {
        List<String> r = db.queryForList("SELECT bars FROM kosdaq_min1 WHERE day = ? AND code = ?", String.class, day.toString(), code);
        return r.isEmpty() ? List.of() : GSON.fromJson(r.get(0), List.class);
    }
}
