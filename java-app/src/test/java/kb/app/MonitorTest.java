package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MonitorTest {

    /** 저장·실패 기록·빠진 종목 삭제 (monitor.py 자체 점검과 같은 경우). */
    @Test
    @SuppressWarnings("unchecked")
    void refreshSavesQuotesToDb() throws Exception {
        Path dir = Files.createTempDirectory("monitor");
        Chart chart = new Chart(null, dir.resolve("recent_searches.txt"));
        chart.saveMonitorItems("{\"items\": [{\"market\": \"us\", \"code\": \"NVDA\", \"exchange\": \"NAS\"}]}");
        Monitor monitor = new Monitor(chart, dir.resolve("kb.db"), (m, c, x) -> {
            assertTrue(!"SPY".equals(c) || "AMX".equals(x));
            if ("QQQ".equals(c)) {
                throw new IOException("조회 실패");
            }
            List<Map<String, Object>> cs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("d", String.format("2026%04d", i));
                k.put("c", (double) i);
                k.put("o", 0.0);
                cs.add(k);
            }
            return Map.of("candles", cs);
        });

        Map<String, Object> q = (Map<String, Object>) monitor.refresh().get("quotes");
        Set<String> want = new HashSet<>();
        for (Map<String, Object> ix : Monitor.INDICES) {
            want.add(ix.get("market") + ":" + ix.get("code"));
        }
        want.add("us:NVDA");
        assertEquals(want, q.keySet());
        Map<String, Object> qqq = (Map<String, Object>) q.get("us:QQQ");
        assertEquals("조회 실패", qqq.get("error"));
        assertFalse(qqq.containsKey("candles"));
        List<Map<String, Object>> spy = (List<Map<String, Object>>) ((Map<String, Object>) q.get("us:SPY")).get("candles");
        assertEquals(Monitor.KEEP_CANDLES, spy.size());
        assertEquals(Map.of("d", "20260049", "c", 49.0), spy.get(spy.size() - 1));  // 날짜·종가만 남김

        chart.saveMonitorItems("{\"items\": []}");
        assertFalse(((Map<String, Object>) monitor.refresh().get("quotes")).containsKey("us:NVDA"));  // 목록에서 뺀 종목은 지움
        assertEquals(Monitor.INDICES, monitor.listing().get("indices"));
        assertEquals(List.of(), monitor.listing().get("items"));
    }
}
