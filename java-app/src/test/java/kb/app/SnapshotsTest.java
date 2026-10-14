package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * -Dmysql.live=true 를 주면 .env의 MYSQL_* 로 실제 DB 접속·쓰기·읽기도 확인합니다 (시험 쓰기는 되돌림).
 *   ../spring-app/mvnw test -Dtest=SnapshotsTest -Dmysql.live=true                                (java-app 폴더에서, 저장소 루트 .env)
 *   ../spring-app/mvnw test -Dtest=SnapshotsTest -Dmysql.live=true -Dkb.data.dir=/opt/kb_openapi_sample
 */
class SnapshotsTest {

    @Test
    @EnabledIfSystemProperty(named = "mysql.live", matches = "true")
    void liveDatabaseAcceptsConnectionAndWrites() throws Exception {
        Path env = Path.of(System.getProperty("kb.data.dir", ".."), ".env").toAbsolutePath().normalize();
        String why = new Snapshots(null, env).check();
        assertNull(why, () -> "DB 점검 실패 (.env: " + env + "): " + why);
    }

    @Test
    void nextRunIsNextFourHourMark() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 4, 0), Snapshots.nextRun(LocalDateTime.of(2026, 10, 15, 0, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 15, 4, 0), Snapshots.nextRun(LocalDateTime.of(2026, 10, 15, 3, 59)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 0, 0), Snapshots.nextRun(LocalDateTime.of(2026, 10, 15, 22, 10)));
    }

    @Test
    void snapshotsReplaceDailyRecordOfSameDay() {
        List<Map<String, Object>> days = Arrays.asList(Collections.singletonMap("date", "2026-10-14"), Collections.singletonMap("date", "2026-10-15"));
        List<Map<String, Object>> snaps = Arrays.asList(Collections.singletonMap("date", "2026-10-15T00:00"), Collections.singletonMap("date", "2026-10-15T04:00"));
        assertEquals(Arrays.asList("2026-10-14", "2026-10-15T00:00", "2026-10-15T04:00"),
                Snapshots.merge(days, snaps).stream().map(d -> d.get("date")).collect(Collectors.toList()));
    }
}
