package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VolumeTest {

    private static final Ticks KOSDAQ = new Volume(new KBClient("", "", "", "", ""), Path.of("unused.db")).ticks;

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

    @Test
    void nextTickStaysInMarketHours() {
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 5), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 16, 9, 3)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 10), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 16, 9, 5)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 0), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 16, 8, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 15, 40), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 16, 15, 35)));
        assertEquals(LocalDateTime.of(2026, 10, 16, 9, 0), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 15, 15, 40)));
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 16, 23, 0)));  // 금 -> 월
        assertEquals(LocalDateTime.of(2026, 10, 19, 9, 0), KOSDAQ.nextTick(LocalDateTime.of(2026, 10, 17, 12, 0)));  // 토 -> 월
    }

    @Test
    @SuppressWarnings("unchecked")
    void comparesSameTimeWithPreviousDayAndWeek() throws Exception {
        Ticks v = new Volume(new KBClient("", "", "", "", ""), Files.createTempDirectory("tick").resolve("kosdaq.db")).ticks;
        String[][] ticks = {{"2026-10-08T10:30", "50"}, {"2026-10-09T10:20", "80"}, {"2026-10-15T10:30", "100"},
                {"2026-10-16T10:30", "150"}, {"2026-10-16T10:40", "160"}};
        for (String[] t : ticks) {
            long vol = Long.parseLong(t[1]);
            v.save(t[0], Arrays.asList(Arrays.<Object>asList("000001", "가", 1000L, 1.5, vol, vol * 1000)));
        }
        Map<String, Object> r = v.intraday("2026-10-16", "10:35");
        assertEquals("10:30", r.get("at"));
        assertEquals("2026-10-15", ((Map<String, String>) r.get("prev")).get("date"));
        assertEquals("2026-10-09", ((Map<String, String>) r.get("week")).get("date"));
        assertEquals("10:20", ((Map<String, String>) r.get("week")).get("at"));
        Map<String, Object> s = ((List<Map<String, Object>>) r.get("stocks")).get(0);
        assertEquals(50.0, s.get("prevRatio"));
        assertEquals(87.5, s.get("weekRatio"));
        assertEquals("10:40", v.intraday("", "").get("at"));
        assertNull(v.intraday("2026-10-08", "").get("prev"));
        assertEquals(540, r.get("utcOffset"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulatesBuyOnVolumeAndRiseThenSells() throws Exception {
        Ticks v = new Volume(new KBClient("", "", "", "", ""), Files.createTempDirectory("sim").resolve("kosdaq.db")).ticks;
        String[] codes = {"A", "B", "C", "D스팩", "E"};
        Object[][][] at = {  // 시각, 종목별 {현재가, 등락률, 누적 거래량} (volume.py 자체 점검과 같음)
                {{"2026-10-15T09:00"}, {1, 0, 100}, {1, 0, 100}, {1, 0, 100}, {1, 0, 100}, {1, 0, 100}},
                {{"2026-10-15T10:00"}, {1, 0, 200}, {1, 0, 200}, {1, 0, 200}, {1, 0, 200}, {1, 0, 200}},
                {{"2026-10-15T15:40"}, {1, 0, 300}, {1, 0, 300}, {1, 0, 300}, {1, 0, 300}, {1, 0, 300}},
                {{"2026-10-16T09:00"}, {1000, 1.0, 150}, {1000, -1.0, 100}, {2000, 1.0, 100}, {1000, 1.0, 900}, {1000, 1.0, 100}},
                {{"2026-10-16T10:00"}, {1100, 11.0, 300}, {1000, -1.0, 900}, {2000, 2.0, 400}, {1000, 1.0, 900}, {1000, 1.0, 200}},
                {{"2026-10-16T15:40"}, {900, 1.0, 400}, {1000, -1.0, 900}, {1900, 2.0, 500}, {1000, 1.0, 900}, {1000, 1.0, 900}}};
        for (Object[][] t : at) {
            List<List<Object>> rows = new ArrayList<>();
            for (int i = 0; i < codes.length; i++) {
                rows.add(Arrays.<Object>asList(codes[i], codes[i], t[i + 1][0], t[i + 1][1], t[i + 1][2], 0));
            }
            v.save((String) t[0][0], rows);
        }
        Map<String, Object> r = v.simulate(40, 300_000, 5, 0, "15:00", 0, true);
        List<String> got = new ArrayList<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) r.get("trades")) {
            got.add(x.get("code") + " " + x.get("buyAt") + " " + x.get("qty") + " " + x.get("sellAt") + " " + x.get("sellPrice") + " " + x.get("reason") + " " + x.get("pnl"));
        }
        assertEquals(List.of("A 09:00 300 10:00 1100.0 익절 30000", "C 10:00 150 15:40 1900.0 마지막 기록 -15000"), got);
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertEquals(List.of(2, 1L, 600000L, 15000L, 2.5), List.of(total.get("trades"), total.get("wins"), total.get("invested"), total.get("pnl"), total.get("ret")));
        assertEquals("2026-10-16", r.get("from"));
        assertEquals(3, ((List<?>) v.simulate(40, 300_000, 5, 0, "15:00", 0, false).get("trades")).size(), "스팩 포함");
    }
}
