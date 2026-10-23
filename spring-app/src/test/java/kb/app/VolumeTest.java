package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final Ticks KOSDAQ = volume("unused").ticks;

    private static Volume volume(String dir) {
        try {
            return new Volume(new KBClient("", "", "", "", ""), Sqlite.jdbc(Files.createTempDirectory(dir).resolve("kosdaq.db")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

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
    void sessionIsWeekdayBetweenOpenAndClose() {
        java.time.LocalTime open = java.time.LocalTime.of(9, 0), close = java.time.LocalTime.of(15, 40);
        assertTrue(Ticks.inSession(LocalDateTime.of(2026, 10, 16, 9, 0), open, close));
        assertTrue(Ticks.inSession(LocalDateTime.of(2026, 10, 16, 15, 40), open, close));  // 15:30 종가 단일가까지 담기게
        assertFalse(Ticks.inSession(LocalDateTime.of(2026, 10, 16, 15, 45), open, close));
        assertFalse(Ticks.inSession(LocalDateTime.of(2026, 10, 16, 8, 55), open, close));
        assertFalse(Ticks.inSession(LocalDateTime.of(2026, 10, 17, 10, 0), open, close));  // 토요일
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
        Ticks v = volume("tick").ticks;
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
        Ticks v = volume("sim").ticks;
        String[] codes = {"A", "B", "C", "D스팩", "E"};
        // 시각, 종목별 {현재가, 등락률, 누적 거래량} (volume.py 자체 점검과 같음). 첫 기록(09:00) 현재가를 시가로 봄
        // A: 09:30부터 이어져 10:00에 사서 10:30 익절. B: 10:00에 시가 아래로 깨져 다시 세다 마지막 기록이라 안 삼.
        // C: 09:30은 하락이라 10:00부터 세어 10:30에 사서 마지막 기록에 팜. D스팩은 스팩 제외. E는 거래량이 안 늘어 안 삼
        Object[][][] at = {
                {{"09:00"}, {1000, 0.5, 100}, {1000, 0.5, 100}, {2000, 0.5, 100}, {1000, 0.5, 100}, {1000, 0.5, 100}},
                {{"09:30"}, {1010, 1.0, 200}, {1010, 1.0, 200}, {2010, -1.0, 200}, {1010, 1.0, 200}, {1010, 1.0, 100}},
                {{"10:00"}, {1020, 2.0, 300}, {990, 1.0, 300}, {2010, 1.0, 300}, {1020, 2.0, 300}, {1020, 2.0, 100}},
                {{"10:30"}, {1080, 8.0, 400}, {1010, 1.0, 400}, {2020, 1.0, 400}, {1020, 2.0, 400}, {1030, 3.0, 100}},
                {{"15:40"}, {900, -10.0, 500}, {1010, 1.0, 500}, {1900, -4.5, 500}, {1000, 1.0, 500}, {1030, 3.0, 100}}};
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
        Map<String, Object> r = v.simulate(40, 300_000, 5, 0, "15:00", 0, true, 30, "volume-open-up", "tp-sl", "", 0, 0, null);
        List<String> got = new ArrayList<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) r.get("trades")) {
            got.add(x.get("code") + " " + x.get("since") + " " + x.get("buyAt") + " " + x.get("qty") + " " + x.get("sellAt") + " "
                    + x.get("sellPrice") + " " + x.get("reason") + " " + x.get("pnl"));
        }
        assertEquals(List.of("A 09:30 10:00 294 10:30 1080.0 익절 17640", "C 10:00 10:30 148 15:40 1900.0 마지막 기록 -17760"), got);
        Map<String, Object> total = (Map<String, Object>) r.get("total");
        assertEquals(List.of(2, 1L, 598840L, -120L, -0.02), List.of(total.get("trades"), total.get("wins"), total.get("invested"), total.get("pnl"), total.get("ret")));
        assertEquals("2026-10-16", r.get("from"));
        assertEquals(3, ((List<?>) v.simulate(40, 300_000, 5, 0, "15:00", 0, false, 30, "volume-open-up", "tp-sl", "", 0, 0, null).get("trades")).size(), "스팩 포함");
        List<Map<String, Object>> now = (List<Map<String, Object>>) v.simulate(40, 300_000, 0, 0, "15:00", 0, true, 0, "volume-open-up", "tp-sl", "", 0, 0, null).get("trades");
        assertEquals(List.of("A 09:30", "B 09:30"), List.of(now.get(0).get("code") + " " + now.get(0).get("buyAt"), now.get(1).get("code") + " " + now.get(1).get("buyAt")), "hold=0이면 바로");
        // 모듈 교체: 시가 무시면 B도 10:00에 삼. 추적 손절 5%면 A는 고점 1080에서 15:40 900으로 내려 팜 (volume.py와 같음)
        List<String> codes2 = new ArrayList<>();
        ((List<Map<String, Object>>) v.simulate(40, 300_000, 5, 0, "15:00", 0, true, 30, "volume-up", "tp-sl", "", 0, 0, null).get("trades")).forEach(x -> codes2.add((String) x.get("code")));
        assertEquals(List.of("A", "B", "C"), codes2);
        List<String> sold = new ArrayList<>();
        ((List<Map<String, Object>>) v.simulate(40, 300_000, 0, 5, "15:00", 0, true, 30, "volume-open-up", "trailing", "", 0, 0, null).get("trades"))
                .forEach(x -> sold.add(x.get("code") + " " + x.get("sellAt") + " " + x.get("reason")));
        assertEquals(List.of("A 15:40 고점 대비 손절", "C 15:40 고점 대비 손절"), sold);
        assertThrows(IllegalArgumentException.class, () -> v.simulate(40, 300_000, 0, 0, "15:00", 0, true, 30, "nope", "tp-sl", "", 0, 0, null));
        // 매수 시작 10:30이면 그 전 기록은 안 봄. 보유 30분이면 A는 10:30, C는 15:40에 '보유 시간'. 하루 최대 1종목이면 A만.
        // 알림 종목만이면 C(10:00 알림)만 10:00부터 세어 10:30에 (volume.py 자체 점검과 같음)
        assertEquals(List.of("A 10:30", "B 10:30", "C 10:30"), pick(v.simulate(40, 300_000, 0, 0, "15:00", 0.23, true, 0, "volume-open-up", "tp-sl", "10:30", 0, 0, null), "code", "buyAt"));
        assertEquals(List.of("A 10:30 보유 시간", "C 15:40 보유 시간"), pick(v.simulate(40, 300_000, 0, 0, "15:00", 0, true, 30, "volume-open-up", "tp-sl", "", 30, 0, null), "code", "sellAt", "reason"));
        assertEquals(List.of("A"), pick(v.simulate(40, 300_000, 0, 0, "15:00", 0.23, true, 0, "volume-open-up", "tp-sl", "", 0, 1, null), "code"));
        assertEquals(List.of("C 10:00 10:30"), pick(v.simulate(40, 300_000, 0, 0, "15:00", 0.23, true, 30, "volume-open-up", "tp-sl", "", 0, 0,
                Map.of("2026-10-16", Map.of("C", "10:00"))), "code", "since", "buyAt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveBuysAtLatestRecordAndKeepsPositionsOpen() throws Exception {
        // 장중 10:00까지만 기록: A는 09:30부터 이어져 마지막 기록(10:00)에서 삼. 시뮬레이션이면 그날 마지막 기록이라 안 사지만 장중이라 삼
        Ticks t = volume("live").ticks;
        Object[][] at = {{"09:00", 1000, 0.0, 100}, {"09:30", 1010, 1.0, 200}, {"10:00", 1020, 2.0, 300}};
        for (Object[] x : at) {
            t.save("2026-10-15T" + x[0], List.of(Arrays.<Object>asList("A", "A", 1, 0, 100, 0)));
            t.save("2026-10-16T" + x[0], List.of(Arrays.<Object>asList("A", "A", x[1], x[2], x[3], 0)));
        }
        Map<String, Object> p = new LinkedHashMap<>(Map.of("min", 40, "amount", 300_000, "hold", 30, "maxBuys", 2));
        Map<String, Object> r = t.live(p, null);
        assertEquals(List.of("2026-10-16", "10:00", 5), List.of(r.get("date"), r.get("at"), r.get("every")));
        assertEquals(List.of("A"), ((List<Map<String, Object>>) r.get("buy")).stream().map(b -> b.get("code")).collect(java.util.stream.Collectors.toList()));
        assertEquals(Map.of(), r.get("sell"), "장중이라 '마지막 기록' 매도 없음");
        assertEquals(0, ((List<?>) t.simulate(40, 300_000, 0, 0, "15:00", 0, true, 30, "volume-open-up", "tp-sl", "", 0, 0, null).get("trades")).size(),
                "시뮬레이션은 그날 마지막 기록에서 안 삼");
    }

    @Test
    void comparesMondayWithFridayWhenWeekendHasNoRecords() throws Exception {
        // 전일 = 직전 기록일: 월요일(10-19)은 기록이 없는 일·토요일을 건너뛰고 금요일(10-16) 같은 시각과 비교 (volume.py 자체 점검과 같음)
        Ticks w = volume("weekend").ticks;
        Object[][] at = {{"09:00", 1000, 0.0, 100}, {"09:30", 1010, 1.0, 200}, {"15:40", 1020, 2.0, 300}};
        for (Object[] x : at) {
            w.save("2026-10-16T" + x[0], List.of(Arrays.<Object>asList("A", "A", 1000, 0.0, 100, 0)));
            w.save("2026-10-19T" + x[0], List.of(Arrays.<Object>asList("A", "A", x[1], x[2], x[3], 0)));
        }
        assertEquals(List.of("2026-10-19 09:30 2026-10-16 09:30"),
                pick(w.simulate(40, 300_000, 0, 0, "15:00", 0.23, true, 0, "volume-open-up", "tp-sl", "", 0, 0, null), "date", "buyAt", "prevAt"));
    }

    @Test
    void vwapUpBuysOnlyAboveVolumeWeightedAverage() throws Exception {
        // VWAP = 누적 거래대금 ÷ 누적 거래량: 09:30은 1010원 < 1050원이라 안 사고 10:00(1100원 > 1066.7원)에 삼. 거래대금이 없는 Z는 안 삼 (volume.py와 같음)
        Ticks v = volume("vwap").ticks;
        Object[][] at = {{"09:00", 1000, 100, 100_000}, {"09:30", 1010, 200, 210_000}, {"10:00", 1100, 300, 320_000}, {"15:40", 1100, 400, 430_000}};
        for (Object[] x : at) {
            double ch = (int) x[1] / 10.0 - 100;  // 전일 종가 1000원
            v.save("2026-10-15T" + x[0], List.of(Arrays.<Object>asList("V", "V", 1000, 0.0, 100, 0), Arrays.<Object>asList("Z", "Z", 1000, 0.0, 100, 0)));
            v.save("2026-10-16T" + x[0], List.of(Arrays.<Object>asList("V", "V", x[1], ch, x[2], x[3]), Arrays.<Object>asList("Z", "Z", x[1], ch, x[2], 0)));
        }
        assertEquals(List.of("V 10:00"), pick(v.simulate(40, 300_000, 0, 0, "15:00", 0.23, true, 0, "vwap-up", "tp-sl", "", 0, 0, null), "code", "buyAt"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> pick(Map<String, Object> r, String... keys) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) r.get("trades")) {
            List<String> v = new ArrayList<>();
            for (String k : keys) {
                v.add(String.valueOf(x.get(k)));
            }
            out.add(String.join(" ", v));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void simulateDropsPriceThatDoesNotMatchChange() throws Exception {
        // 자릿수가 빠진 현재가(09:30 100원, 등락률은 그대로)는 빼고 10:00에 1010원으로 삼 (volume.py 자체 점검과 같음)
        Ticks g = volume("glitch").ticks;
        Object[][] at = {{"09:00", 1000, 0.0, 100}, {"09:30", 100, 1.0, 200}, {"10:00", 1010, 1.0, 300}, {"15:40", 1010, 1.0, 400}};
        for (Object[] x : at) {
            g.save("2026-10-15T" + x[0], List.of(Arrays.<Object>asList("G", "G", 1, 0, 100, 0)));
            g.save("2026-10-16T" + x[0], List.of(Arrays.<Object>asList("G", "G", x[1], x[2], x[3], 0)));
        }
        List<Map<String, Object>> ts = (List<Map<String, Object>>) g.simulate(40, 300_000, 0, 0, "15:00", 0, true, 0, "volume-up", "tp-sl", "", 0, 0, null).get("trades");
        assertEquals(List.of("10:00 1010.0"), List.of(ts.get(0).get("buyAt") + " " + ts.get(0).get("buyPrice")));
        assertEquals(1, ts.size());
    }
}
