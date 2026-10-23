package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 설정 파일 매수 조건 (sim_modules.py 자체 점검과 같은 경우). */
class SimModulesTest {

    @AfterEach
    void reset() {
        SimModules.rulesFile = null;
    }

    @Test
    void buyRulesComeFromConfigAndAreValidated() throws Exception {
        SimModules.rulesFile = Files.createTempDirectory("rules").resolve("sim_buy_rules.json");
        assertEquals(List.of("volume-open-up", "volume-up", "surge", "surge300", "surge-vwap", "vwap-up"), ids());
        double[] s = new double[] {1100.0, 6.0, 600.0, 1000.0, 1050.0, 1.0};
        assertTrue(SimModules.test(SimModules.buy("surge"), s, 100.0));
        assertTrue(SimModules.test(SimModules.buy("surge300"), new double[] {1.0, 6.0, 300.0, 1.0, 1.0, 1.0}, 999.0));
        assertFalse(SimModules.test(SimModules.buy("surge"), new double[] {1.0, 6.0, 300.0, 1.0, 1.0, 1.0}, 0.0));
        assertTrue(SimModules.test(SimModules.buy("volume-up"), s, 600.0));
        assertFalse(SimModules.test(SimModules.buy("volume-up"), s, 601.0));  // min = 화면 거래량 기준

        Map<String, Object> out = SimModules.saveBuyRules("{\"buy\": [{\"id\": \"my-1\", \"label\": \" 내  조건 \", \"all\": [{\"left\": \"price\", \"op\": \"<=\", \"right\": \"vwap\"}, {\"left\": \"change\", \"op\": \">\", \"right\": 3}]}]}");
        assertEquals(List.of(Map.of("id", "my-1", "label", "내 조건", "all", List.of(Map.of("left", "price", "op", "<=", "right", "vwap"),
                Map.of("left", "change", "op", ">", "right", 3L)))), out.get("buy"));
        assertEquals(List.of("my-1"), ids());
        assertFalse(SimModules.test(SimModules.buy("my-1"), s, 0.0));
        assertThrows(IllegalArgumentException.class, () -> SimModules.buy("surge"));  // 저장한 목록에 없는 조건
        for (String bad : new String[] {"{\"buy\": []}", "{\"buy\": [{\"id\": \"A\", \"all\": [{\"left\": \"price\", \"op\": \">\", \"right\": 1}]}]}",
                "{\"buy\": [{\"id\": \"a\", \"all\": []}]}", "{\"buy\": [{\"id\": \"a\", \"all\": [{\"left\": \"price\", \"op\": \"==\", \"right\": 1}]}]}",
                "{\"buy\": [{\"id\": \"a\", \"all\": [{\"left\": \"evil\", \"op\": \">\", \"right\": 1}]}]}",
                "{\"buy\": [{\"id\": \"a\", \"all\": [{\"left\": \"price\", \"op\": \">\", \"right\": \"x\"}]}]}",
                "{\"buy\": [{\"id\": \"a\", \"all\": [{\"left\": \"price\", \"op\": \">\", \"right\": true}]}]}", "{broken"}) {
            assertThrows(IllegalArgumentException.class, () -> SimModules.saveBuyRules(bad), bad);
        }
        assertEquals(List.of("my-1"), ids());  // 잘못된 저장은 파일을 안 바꿈
        Files.writeString(SimModules.rulesFile, "{broken");
        assertEquals("volume-open-up", ids().get(0));  // 깨진 파일은 기본 규칙
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids() {
        return ((List<Map<String, Object>>) SimModules.catalog().get("buy")).stream().map(r -> r.get("id")).collect(Collectors.toList());
    }
}
