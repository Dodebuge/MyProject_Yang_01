package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChartTest {

    /** 분봉: 시각 t(HHMM)를 붙이고 시각 순, 같은 시각 중복 제거 (chart.py 자체 점검과 같음). tm은 문자열·숫자 둘 다 */
    @Test
    void minuteCandlesCarryTimeInOrder() {
        java.util.function.BiFunction<String, Object, Map<String, Object>> m = (d, tm) -> Map.of("dt", d, "tm", tm,
                "opn_prc_p2", 1, "hgh_prc_p2", 1, "lw_prc_p2", 1, "cls_prc_p2", 1, "vlm", 1, "dl_tw_amt", 1);
        List<Map<String, Object>> cs = Chart.candles(List.of(m.apply("20261022", "091000"), m.apply("20261022", 90500L),
                m.apply("20261021", "153000"), m.apply("20261022", "091000")), "p2", 1);
        assertEquals(List.of("20261021 1530", "20261022 0905", "20261022 0910"),
                cs.stream().map(c -> c.get("d") + " " + c.get("t")).collect(java.util.stream.Collectors.toList()));
    }

    /** 모니터 종목: 없으면 빈 목록, 잘못된 종목·중복은 버림, 통째로 교체, 깨진 파일은 빈 목록 (chart.py 자체 점검과 같은 경우). */
    @Test
    void monitorItemsAreValidatedAndSaved() throws Exception {
        Path dir = Files.createTempDirectory("chart");
        Chart chart = new Chart(null, dir.resolve("recent_searches.txt"));
        assertEquals(Map.of("items", List.of()), chart.monitorItems());

        Map<String, Object> saved = chart.saveMonitorItems("{\"items\": ["
                + "{\"market\": \"us\", \"code\": \"nvda\", \"name\": \"NVDA\", \"exchange\": \"NAS\", \"etf\": \"미국 NAS\"},"
                + "{\"market\": \"us\", \"code\": \"NVDA\", \"exchange\": \"NAS\"},"
                + "{\"market\": \"kr\", \"code\": \"005930\", \"name\": \" 삼성\\t전자 \"},"
                + "{\"market\": \"kr\", \"code\": \"../x\"}, {\"market\": \"us\", \"code\": \"AAPL\", \"exchange\": \"EVIL\"}, \"x\"]}");
        List<Map<String, Object>> want = List.of(
                Map.of("market", "us", "code", "NVDA", "name", "NVDA", "exchange", "NAS", "etf", "미국 NAS"),
                Map.of("market", "kr", "code", "005930", "name", "삼성 전자", "exchange", "", "etf", ""));
        assertEquals(Map.of("items", want), saved);
        assertEquals(saved, chart.monitorItems());

        StringBuilder many = new StringBuilder("{\"items\": [");
        for (int i = 0; i < 40; i++) {
            many.append(i > 0 ? "," : "").append(String.format("{\"market\": \"kr\", \"code\": \"%06d\"}", i));
        }
        assertEquals(Chart.MONITOR_MAX, ((List<?>) chart.saveMonitorItems(many + "]}").get("items")).size());

        assertThrows(IllegalArgumentException.class, () -> chart.saveMonitorItems("[]"));
        assertThrows(IllegalArgumentException.class, () -> chart.saveMonitorItems("{\"items\": \"x\"}"));
        assertThrows(IllegalArgumentException.class, () -> chart.saveMonitorItems("{broken"));
        Files.writeString(dir.resolve("monitor_items.json"), "{broken");
        assertEquals(Map.of("items", List.of()), chart.monitorItems());
    }
}
