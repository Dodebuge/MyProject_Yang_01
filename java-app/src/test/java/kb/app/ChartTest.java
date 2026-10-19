package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChartTest {

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
