package kb.app;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MinutesTest {

    private static Map<String, Object> bar(String dt, String tm, double c) {
        return Map.of("dt", dt, "tm", tm, "opn_prc_p2", c, "hgh_prc_p2", c + 1, "lw_prc_p2", c - 1, "cls_prc_p2", c, "vlm", 10, "dl_tw_amt", 10 * c);
    }

    /** 그날 1분봉만 시각순으로 저장, 거래 없던 종목은 빈 목록, 이미 받은 종목은 다시 안 부름, 그날 봉이 하나도 없으면 휴장일. */
    @Test
    void collectsDayBarsOfEveryStockOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Minutes m = new Minutes((tr, body) -> {
            if ("IVU10210".equals(tr)) {
                return Map.of("out2", List.of(Map.of("is_cd", "000001", "is_nm", "가"), Map.of("is_cd", "000002", "is_nm", "나"),
                        Map.of("is_cd", "BAD", "is_nm", "밀린 행")));
            }
            calls.incrementAndGet();
            List<Map<String, Object>> rows = new ArrayList<>();
            if ("000001".equals(body.get("is_cd"))) {  // 최신순, 전날 봉·거래량 0 봉·깨진 시각 섞임
                rows.add(bar("20261022", "730000", 120));
                Map<String, Object> idle = new java.util.HashMap<>(bar("20261022", "090500", 105));
                idle.put("vlm", 0);
                rows.add(idle);
                rows.add(bar("20261022", "091000", 110));
                rows.add(bar("20261022", "090000", 100));
                rows.add(bar("20261021", "195900", 90));
            }
            return Map.of("out2", rows);
        }, null, Files.createTempDirectory("min").resolve("minutes.db"));
        LocalDate day = LocalDate.of(2026, 10, 22);
        assertArrayEquals(new int[] {2, 0, 0}, m.collect(day));
        assertEquals(List.of(List.of("0900", 100.0, 101.0, 99.0, 100.0, 10.0, 1000.0), List.of("0910", 110.0, 111.0, 109.0, 110.0, 10.0, 1100.0)),
                m.load(day, "000001"));
        assertEquals(List.of(), m.load(day, "000002"));
        assertEquals(2, calls.get());
        assertArrayEquals(new int[] {0, 0, 0}, m.collect(day));  // 이어 받기: 다 받았으면 KB 1분봉을 다시 안 부름
        assertEquals(2, calls.get());
        assertArrayEquals(new int[] {0, 0, 0}, m.collect(LocalDate.of(2026, 10, 23)));  // 그날 봉이 없음 = 휴장일, 저장 안 함
        assertEquals(List.of(), m.load(LocalDate.of(2026, 10, 23), "000002"));
    }

    /** 목록의 홀수 번째는 토스: 끝난 시각 -> 시작 시각, 거래량 0인 분 빼고, 거래대금은 추정, 페이지는 그날 첫 봉 앞까지. 토스가 실패하면 KB로. */
    @Test
    void splitsStocksBetweenKbAndToss() throws Exception {
        List<String> kb = new ArrayList<>();
        List<Map<String, String>> tossCalls = new ArrayList<>();
        Map<String, Object> page1 = Map.of("candles", List.of(c("2026-10-22T09:03:00.000+09:00", 0), c("2026-10-22T09:02:00.000+09:00", 30)),
                "nextBefore", "p2");
        Map<String, Object> page2 = Map.of("candles", List.of(c("2026-10-22T09:01:00.000+09:00", 10), c("2026-10-21T20:00:00.000+09:00", 5)),
                "nextBefore", "p3");
        Minutes m = new Minutes((tr, body) -> {
            if ("IVU10210".equals(tr)) {
                return Map.of("out2", List.of(Map.of("is_cd", "000001", "is_nm", "가"), Map.of("is_cd", "000002", "is_nm", "나"),
                        Map.of("is_cd", "000003", "is_nm", "다"), Map.of("is_cd", "000004", "is_nm", "라")));
            }
            kb.add((String) body.get("is_cd"));
            return Map.of("out2", List.of(bar("20261022", "090000", 100)));
        }, (path, q) -> {
            tossCalls.add(q);
            if ("000004".equals(q.get("symbol"))) {
                throw new java.io.IOException("토스 오류");
            }
            return q.containsKey("before") ? page2 : page1;
        }, Files.createTempDirectory("min").resolve("minutes.db"));
        LocalDate day = LocalDate.of(2026, 10, 22);
        assertArrayEquals(new int[] {4, 0, 1}, m.collect(day));
        assertEquals(List.of("000001", "000003", "000004"), kb.stream().sorted().collect(java.util.stream.Collectors.toList()));  // 000004는 토스 실패 -> KB
        assertEquals(List.of(List.of("0900", 100.0, 101.0, 99.0, 100.0, 10.0, 1000.0), List.of("0901", 100.0, 101.0, 99.0, 100.0, 30.0, 3000.0)),
                m.load(day, "000002"));  // 09:02 끝 봉 = 09:01, 거래량 0인 09:02 봉과 전날 봉은 뺌
        assertEquals("p2", tossCalls.stream().filter(q -> "000002".equals(q.get("symbol"))).skip(1).findFirst().get().get("before"));
        assertEquals("1m", tossCalls.get(0).get("interval"));
    }

    private static Map<String, Object> c(String ts, double v) {
        return Map.of("timestamp", ts, "openPrice", "100", "highPrice", "101", "lowPrice", "99", "closePrice", "100", "volume", String.valueOf((long) v));
    }

    @Test
    void runsOnWeekdaysAfterNxtClose() {
        assertEquals(LocalDateTime.of(2026, 10, 22, 20, 10), Minutes.nextRun(LocalDateTime.of(2026, 10, 22, 9, 0)));
        assertEquals(LocalDateTime.of(2026, 10, 26, 20, 10), Minutes.nextRun(LocalDateTime.of(2026, 10, 23, 20, 10)));  // 금요일 뒤는 월요일
    }
}
