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
    void simulatesBuyWhenVolumeAboveOpenAndRiseHold30Minutes() throws Exception {
        Ticks v = new Volume(new KBClient("", "", "", "", ""), Files.createTempDirectory("sim").resolve("kosdaq.db")).ticks;
        String[] codes = {"A", "B", "C", "D스팩", "E"};
        // 시각, 종목별 {현재가, 등락률, 누적 거래량} (volume.py 자체 점검과 같음). 첫 기록(09:00) 현재가를 시가로 봄
        // A: 09:30부터 이어져 10:00에 사서 10:30 익절. B: 10:00에 시가 아래로 깨져 다시 세다 마지막 기록이라 안 삼.
        // C: 09:30은 하락이라 10:00부터 세어 10:30에 사서 마지막 기록에 팜. D스팩은 스팩 제외. E는 거래량이 안 늘어 안 삼
        Object[][][] at = {
                {{"09:00"}, {1000, 0.5, 100}, {1000, 0.5, 100}, {2000, 0.5, 100}, {1000, 0.5, 100}, {1000, 0.5, 100}},
                {{"09:30"}, {1010, 1.0, 200}, {1010, 1.0, 200}, {2010, -1.0, 200}, {1010, 1.0, 200}, {1010, 1.0, 100}},
                {{"10:00"}, {1020, 2.0, 300}, {990, 1.0, 300}, {2010, 1.0, 300}, {1020, 2.0, 300}, {1020, 2.0, 100}},
                {{"10:30"}, {1080, 8.0, 400}, {1010, 1.0, 400}, {2020, 1.0, 400}, {1020, 2.0, 400}, {1030, 3.0, 100}},
                {{"15:40"}, {900, 1.0, 500}, {1010, 1.0, 500}, {1900, 1.0, 500}, {1000, 1.0, 500}, {1030, 3.0, 100}}};
        for (Object[][] t : at) {
            List<List<Object>> prev = new ArrayList<>();
            List<List<Object>> rows = new ArrayList<>();
            for (int i = 0; i < codes.length; i++) {
                prev.add(Arrays.<Object>asList(codes[i], codes[i], 1, 0, 100, 0));
                rows.add(Arrays.<Object>asList(codes[i], codes[i], t[i + 1][0], t[i + 1][1], t[i + 1][2], 0));
            }
            v.save("2026-10-15T" + t[0][0], prev);
            v.save("2026-10-16T" + t[0][0], rows);
        }
        Map<String, Object> r = v.simulate(40, 300_000, 5, 0, "15:00", 0, true, 30);
        List<String> got = new ArrayList<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) r.get("trades")) {
            got.add(x.get("code") + " " + x.get("since") + " " + x.get("buyAt") + " " + x.get("qty") + " " + x.get("sellAt") + " "
                    + x.get("sellPrice") + " " + x.get("reason") + " " + x.get("pnl"));
        }
        assertEquals(List.of("A 09:30 10:00 294 10:30 1080.0 익절 17640", "C 10:00 10:30 148 15:40 1900.0 마지막 기록 -17760"), got);
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertEquals(List.of(2, 1L, 598840L, -120L, -0.02), List.of(total.get("trades"), total.get("wins"), total.get("invested"), total.get("pnl"), total.get("ret")));
        assertEquals("2026-10-16", r.get("from"));
        assertEquals(3, ((List<?>) v.simulate(40, 300_000, 5, 0, "15:00", 0, false, 30).get("trades")).size(), "스팩 포함");
        List<Map<String, Object>> now = (List<Map<String, Object>>) v.simulate(40, 300_000, 0, 0, "15:00", 0, true, 0).get("trades");
        assertEquals(List.of("A 09:30", "B 09:30"), List.of(now.get(0).get("code") + " " + now.get(0).get("buyAt"), now.get(1).get("code") + " " + now.get(1).get("buyAt")), "hold=0이면 바로");
    }
}
