package kb.app;

import java.io.IOException;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 보유 현황을 4시간마다(한국 시간 00·04·08·12·16·20시) KB·토스에서 조회해 MySQL/MariaDB에 기록합니다 (snapshots.py와 같은 테이블).
 * .env(또는 환경변수)에 MYSQL_HOST가 있을 때만 켜집니다. 없으면 기록하지 않고 그래프는 kb.db의 하루 기록만 씁니다.
 * 테이블은 처음 접속할 때 만들고, 데이터베이스·계정은 미리 만들어 두세요 (deploy/README.md).
 */
@Service
class Snapshots {

    private static final Logger log = LoggerFactory.getLogger(Snapshots.class);
    static final int INTERVAL_HOURS = 4;
    static final String CRON = "0 0 */4 * * *";  // 한국 시간 00·04·…·20시 정각
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS holdings_snapshot ("
            + " at DATETIME NOT NULL PRIMARY KEY, total BIGINT NOT NULL, stock BIGINT NOT NULL, cash BIGINT NOT NULL,"
            + " kb BIGINT NOT NULL, kb_stock BIGINT NOT NULL, toss BIGINT NULL, toss_stock BIGINT NULL)";
    // 응답 키 -> 열 이름 (순서 고정)
    private static final String[][] COLS = {{"total", "total"}, {"stock", "stock"}, {"cash", "cash"}, {"kb", "kb"},
            {"kbStock", "kb_stock"}, {"toss", "toss"}, {"tossStock", "toss_stock"}};

    private final Heatmap heatmap;
    private final KbProperties.Mysql mysql;
    private final JdbcTemplate db;  // MYSQL_HOST가 없으면 null (꺼짐)

    Snapshots(Heatmap heatmap, KbProperties.Mysql mysql) {
        this.heatmap = heatmap;
        this.mysql = mysql;
        // 4시간에 한 번이라 풀 없이 쓸 때마다 접속 (MySQL이 꺼져 있어도 서버는 뜸)
        this.db = mysql.url() == null ? null : new JdbcTemplate(new DriverManagerDataSource(mysql.url(), mysql.user(), mysql.password()));
    }

    @Autowired
    Snapshots(Heatmap heatmap, KbProperties props) {
        this(heatmap, props.mysql());
        log.info(db == null ? "[기록] MYSQL_HOST 없음 -> 4시간 보유 금액 기록 안 함"
                : "[기록] " + safeUrl() + " 에 " + INTERVAL_HOURS + "시간마다 보유 금액 기록 (다음 " + next().format(DateTimeFormatter.ofPattern("HH:mm")) + ")");
    }

    private String safeUrl() {
        return mysql.url().replaceAll("\\?.*", "");
    }

    /** 다음 기록 시각 (한국 시간) */
    static LocalDateTime next() {
        return CronExpression.parse(CRON).next(LocalDateTime.now(SEOUL));
    }

    /** 4시간마다 기록. KB·MySQL 오류여도 다음 시각에 다시 */
    @Scheduled(cron = CRON, zone = "Asia/Seoul")
    public void record() {
        if (db == null) {
            return;
        }
        String at = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.HOURS).format(AT);
        try {
            Map<String, Object> summary = Heatmap.summarize(at, heatmap.queryHoldings(true));
            save(summary);
            log.info("[기록] {} 총자산 {}원 저장", summary.get("date"), summary.get("total"));
        } catch (IOException | RuntimeException e) {
            log.warn("[기록] {} 실패: {}: {}", at, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    void save(Map<String, Object> summary) {
        StringBuilder cols = new StringBuilder(), marks = new StringBuilder(), update = new StringBuilder();
        Object[] args = new Object[COLS.length + 1];
        args[0] = ((String) summary.get("date")).replace('T', ' ');
        for (int i = 0; i < COLS.length; i++) {
            cols.append(", ").append(COLS[i][1]);
            marks.append(", ?");
            update.append(update.length() == 0 ? "" : ", ").append(COLS[i][1]).append(" = VALUES(").append(COLS[i][1]).append(')');
            args[i + 1] = summary.get(COLS[i][0]);
        }
        db.execute(SCHEMA);
        db.update("INSERT INTO holdings_snapshot (at" + cols + ") VALUES (?" + marks + ") ON DUPLICATE KEY UPDATE " + update, args);
    }

    List<Map<String, Object>> load() {
        StringBuilder cols = new StringBuilder();
        for (String[] col : COLS) {
            cols.append(", ").append(col[1]);
        }
        db.execute(SCHEMA);
        return db.query("SELECT at" + cols + " FROM holdings_snapshot ORDER BY at", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", rs.getTimestamp(1).toLocalDateTime().format(AT));
            for (int i = 0; i < COLS.length; i++) {
                long v = rs.getLong(i + 2);
                row.put(COLS[i][0], rs.wasNull() ? null : v);
            }
            return row;
        });
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
        if (db == null) {
            String why = "MYSQL_HOST가 없습니다. .env에 MYSQL_HOST·MYSQL_USER·MYSQL_PASSWORD를 넣으세요 (deploy/install-mariadb.sh).";
            System.out.println("[실패] " + why);
            return why;
        }
        System.out.println("설정    " + mysql.user() + " @ " + safeUrl() + " (비밀번호 " + (mysql.password().isEmpty() ? "없음" : "있음") + ")");
        try {
            String product = db.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName() + " " + c.getMetaData().getDatabaseProductVersion());
            db.execute(SCHEMA);
            System.out.println("[성공] 접속·테이블  " + product);
            new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())).executeWithoutResult(tx -> {
                tx.setRollbackOnly();  // 시험 쓰기는 남기지 않음
                db.update("INSERT INTO holdings_snapshot (at, total, stock, cash, kb, kb_stock) VALUES ('2000-01-01 00:00:00', 1, 1, 0, 1, 1)"
                        + " ON DUPLICATE KEY UPDATE total = 1");
                Long total = db.queryForObject("SELECT total FROM holdings_snapshot WHERE at = '2000-01-01 00:00:00'", Long.class);
                if (total == null || total != 1) {
                    throw new IllegalStateException("쓴 값을 다시 읽지 못했습니다");
                }
            });
            System.out.println("[성공] 쓰기·읽기 (되돌림)");
            db.query("SELECT COUNT(*), MAX(at) FROM holdings_snapshot", rs -> {
                System.out.println("기록    " + rs.getLong(1) + "건" + (rs.getTimestamp(2) == null ? "" : ", 마지막 " + rs.getTimestamp(2).toLocalDateTime().format(AT))
                        + " · 다음 기록 " + next().format(AT));
            });
            return null;
        } catch (DataAccessException | IllegalStateException e) {
            Throwable cause = e instanceof DataAccessException d ? d.getMostSpecificCause() : e;
            String why = mysql.user() + " @ " + safeUrl() + " -> " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            System.out.println("[실패] " + why);
            return why;
        }
    }

    /** dbcheck용 최소 Spring: 웹 서버·주기 작업 없이 설정(application.properties + .env)만 읽음 */
    @EnableConfigurationProperties(KbProperties.class)
    static class DbCheck {
    }

    /** java -cp kb-openapi-spring.jar kb.app.Snapshots  (spring-app/run.sh dbcheck). .env는 KB_DATA_DIR에서 읽음. */
    public static void main(String[] args) {
        int code;
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(DbCheck.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF).logStartupInfo(false).run(args)) {
            KbProperties p = ctx.getBean(KbProperties.class);
            System.out.println(".env    " + p.data(".env").toAbsolutePath().normalize());
            code = new Snapshots(null, p.mysql()).check() == null ? 0 : 1;
        }
        System.exit(code);
    }

    /** GET /api/holdings-history : 하루 기록에 MySQL 4시간 기록을 합침. MySQL이 안 되면 하루 기록만 돌려주고 이유를 snapshotError에. */
    @SuppressWarnings("unchecked")
    Map<String, Object> history() throws IOException {
        Map<String, Object> payload = heatmap.holdingsHistory();
        if (db == null) {
            return payload;
        }
        try {
            payload.put("days", merge((List<Map<String, Object>>) payload.get("days"), load()));
            payload.put("interval", INTERVAL_HOURS);
        } catch (DataAccessException e) {  // MySQL이 꺼져 있어도 그래프는 보이게
            payload.put("snapshotError", e.getMostSpecificCause().getClass().getSimpleName() + ": " + e.getMostSpecificCause().getMessage());
        }
        return payload;
    }
}
