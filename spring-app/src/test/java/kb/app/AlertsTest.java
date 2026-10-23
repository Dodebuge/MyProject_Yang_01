package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** alerts.py 자체 점검과 같은 내용: 새 시그널 고르기와 같은 날 중복 막기. */
class AlertsTest {

    private static Map<String, Object> g(String text, String d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", 1);
        m.put("text", text);
        if (d != null) {
            m.put("d", d);
        }
        return m;
    }

    @Test
    void picksOnlyNewBuySignals() {
        Map<String, Object> gc = g("5·20일선 골든크로스", "20261016");
        Map<String, Object> old = g("MACD 시그널 상향 돌파", "20261013");
        Map<String, Object> rsi1 = g("RSI 과매도 28", null);
        Map<String, Object> rsi2 = g("RSI 과매도 25", null);
        assertEquals("RSI 과매도|", Alerts.sigKey(rsi1));
        assertEquals(Alerts.sigKey(rsi1), Alerts.sigKey(rsi2));
        assertEquals(Arrays.asList(gc), Alerts.newSignals(Arrays.asList(gc, old, rsi1), null, "20261016"));  // 처음 보는 종목: 오늘 교차만
        assertEquals(Arrays.asList(gc), Alerts.newSignals(Arrays.asList(gc, old, rsi2),
                new HashSet<>(Arrays.asList(Alerts.sigKey(old), Alerts.sigKey(rsi1))), "20261016"));  // RSI 값만 바뀐 건 새것 아님
        assertEquals(Collections.emptyList(), Alerts.newSignals(Arrays.asList(old), new HashSet<>(Arrays.asList(Alerts.sigKey(old))), "20261016"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsEachSignalOncePerDay() throws Exception {
        Alerts alerts = new Alerts(null, null, Sqlite.jdbc(Files.createTempDirectory("alerts").resolve("alerts.db")), KbProperties.Ntfy.OFF, e -> { });
        Alerts.Market m = new Alerts.Market("kosdaq", "코스닥", ZoneId.of("Asia/Seoul"), LocalTime.of(9, 5), LocalTime.of(15, 35),
                null, null, s -> "market=kr&code=" + s.get("code"), s -> false);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", "000001");
        s.put("name", "가");
        s.put("ratio", 50.0);
        s.put("price", 1000L);
        s.put("change", 1.5);
        Map<String, Object> gc = g("5·20일선 골든크로스", "20261016");
        Map<String, Object> a = alerts.record(m, "20261016", s, gc);
        assertNotNull(a);
        assertEquals("market=kr&code=000001", a.get("chart"));
        assertNull(alerts.record(m, "20261016", s, gc));
        assertNotNull(alerts.record(m, "20261017", s, gc));  // 다음 날은 다시
        List<Map<String, Object>> rows = (List<Map<String, Object>>) alerts.recent(0).get("alerts");
        assertEquals(Arrays.asList("20261017", "20261016"), rows.stream().map(r -> r.get("day")).collect(Collectors.toList()));
        List<Map<String, Object>> after = (List<Map<String, Object>>) alerts.recent(((Number) a.get("id")).longValue()).get("alerts");
        assertEquals(Arrays.asList("20261017"), after.stream().map(r -> r.get("day")).collect(Collectors.toList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentCarriesLatestFlowRecord() throws Exception {
        // 알림 종목 체결강도·호가 기록: /api/alerts에 마지막 기록만 실림 (기록이 없으면 at null)
        Alerts alerts = new Alerts(null, null, Sqlite.jdbc(Files.createTempDirectory("alerts").resolve("alerts.db")), KbProperties.Ntfy.OFF, e -> { });
        assertNull(((Map<String, Object>) alerts.recent(0).get("flow")).get("at"));
        alerts.flow.save("2026-10-22T09:05", List.of(Arrays.<Object>asList("065450", 3320, 120.5, 1000, 3000, 3300)));
        alerts.flow.save("2026-10-22T09:10", List.of(Arrays.<Object>asList("065450", 3195, 95.9, 22567, 46531, 3290)));
        Map<String, Object> flow = (Map<String, Object>) alerts.recent(0).get("flow");
        assertEquals("2026-10-22T09:10", flow.get("at"));
        assertEquals(List.of(List.of("065450", 3195L, 95.9, 22567L, 46531L, 3290L)), flow.get("rows"));
    }

    @Test
    void firstTimesConvertsAlertTimeToMarketZone() throws Exception {
        java.nio.file.Path db = Files.createTempDirectory("alerts").resolve("alerts.db");
        Alerts alerts = new Alerts(null, null, Sqlite.jdbc(db), KbProperties.Ntfy.OFF, e -> { });
        alerts.recent(0);  // 표 만들기
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             java.sql.Statement st = c.createStatement()) {
            // 러셀 알림 시각(한국 2026-10-20 23:20)은 뉴욕 10:20 (서머타임). 같은 종목은 첫 알림만 (alerts.py 자체 점검과 같음)
            st.execute("INSERT INTO signal_alert (market, day, at, code, name, text, d, sig, chart) VALUES "
                    + "('russell', '20261020', '2026-10-20 23:20', 'ABC', 'abc', 't', '', 's', 'c'),"
                    + "('russell', '20261020', '2026-10-20 23:50', 'ABC', 'abc', 't2', '', 's2', 'c'),"
                    + "('kosdaq', '20261020', '2026-10-20 10:16', '206560', '덱스터', 't', '', 's', 'c')");
        }
        assertEquals(Map.of("2026-10-20", Map.of("ABC", "10:20")), alerts.firstTimes("russell"));
        assertEquals(Map.of("2026-10-20", Map.of("206560", "10:16")), alerts.firstTimes("kosdaq"));
    }
}
