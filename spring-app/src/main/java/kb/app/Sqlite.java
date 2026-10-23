package kb.app;

import java.nio.file.Path;

import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** 데이터 폴더의 SQLite 파일(kb.db, kosdaq.db …)에 붙는 JdbcTemplate. Python 서버와 같은 파일을 같이 써서 연결은 쓸 때마다 엽니다. */
final class Sqlite {

    private Sqlite() {
    }

    static JdbcTemplate jdbc(Path file) {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(30_000);  // 다른 요청·Python이 쓰는 중이면 기다림 (Python timeout=30과 같음)
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return new JdbcTemplate(ds);
    }
}
