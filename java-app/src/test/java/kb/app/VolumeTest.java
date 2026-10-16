package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VolumeTest {

    private static List<Map<String, Object>> candles(Object... dayVolume) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < dayVolume.length; i += 2) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("dt", dayVolume[i]);
            r.put("vlm", dayVolume[i + 1]);
            rows.add(r);
        }
        return rows;
    }

    @Test
    void comparesDailyAverageOfThisWeekWithPreviousWeek() {
        // 이번 주 5일(평균 120), 휴장일로 3일뿐인 전주(평균 100), 그 전 주는 무시. 날짜가 깨진 행도 무시
        Map<String, Object> w = Volume.weekChange(candles("20261016", 200, "20261015", 100, "20261014", 100, "20261013", 100,
                "20261012", 100, "20261008", 100, "20261007", 100, "20261006", 100, "20261002", 999, "99999999", 5));
        assertEquals(120L, w.get("avg"));
        assertEquals(100L, w.get("prevAvg"));
        assertEquals(5, w.get("days"));
        assertEquals(3, w.get("prevDays"));
        assertEquals(20.0, w.get("ratio"));
    }

    @Test
    void rejectsShiftedRows() {
        assertTrue(Volume.sane(candles("20261016", 200L, "20261015", 100L)));
        assertFalse(Volume.sane(candles("20261016", "", "20261015", 100L)));        // 거래량 빈 값
        assertFalse(Volume.sane(candles("20261015", 100L, "20261016", 200L)));      // 순서가 뒤집힘
        assertFalse(Volume.sane(candles("20261016", 200L, "99999999", 100L)));      // 날짜가 깨짐
        assertFalse(Volume.sane(candles()));
    }

    @Test
    void needsTwoWeeks() {
        assertNull(Volume.weekChange(candles("20261016", 200, "20261015", 100)));
    }
}
