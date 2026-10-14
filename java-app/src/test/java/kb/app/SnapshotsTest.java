package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SnapshotsTest {

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
