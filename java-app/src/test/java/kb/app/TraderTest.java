package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 가짜 토스 API로 자동매매 흐름 점검 (실제 주문 없음). */
class TraderTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 토스 흉내: 장 운영 정보, 현재가, 시장가 주문(바로 체결 또는 대기), 주문 조회·취소, 판매 가능 수량. */
    static final class FakeToss implements Trader.Api {
        final Map<String, Double> price = new HashMap<>();
        final Map<String, Double> prevClose = new HashMap<>();  // 일봉 전일 종가 (지수 ETF)
        final Map<String, Double> held = new HashMap<>();
        final Map<String, Map<String, Object>> orders = new LinkedHashMap<>();
        final Map<String, String> byClient = new HashMap<>();
        boolean krOpen = true;
        boolean fillNow = true;
        int failNextPosts;  // 주문은 접수하되 응답을 못 받은 것처럼 IOException
        boolean rejectBuys;  // 매수를 주문 가능 금액 부족으로 거절

        @Override
        public Object get(String path, Map<String, String> params, boolean account) throws IOException {
            // 토스처럼 date(현지 날짜)를 받아 그날 정보를 줌. 날짜를 안 넘기면 한국 날짜로 답하는 것까지 흉내 (2026-10-19 월요일만 영업일)
            String date = params != null && params.containsKey("date") ? params.get("date") : "2026-10-20";
            if (path.equals("/api/v1/market-calendar/KR")) {
                Map<String, Object> reg = Map.of("startTime", "2026-10-19T09:00:00+09:00", "endTime", "2026-10-19T15:30:00+09:00");
                Map<String, Object> today = new HashMap<>();
                today.put("date", date);
                today.put("integrated", krOpen && date.equals("2026-10-19") ? Map.of("regularMarket", reg) : null);
                return Map.of("today", today);
            }
            if (path.equals("/api/v1/market-calendar/US")) {
                Map<String, Object> today = new HashMap<>();
                today.put("date", date);
                today.put("regularMarket", date.equals("2026-10-19")
                        ? Map.of("startTime", "2026-10-19T22:30:00+09:00", "endTime", "2026-10-20T05:00:00+09:00") : null);
                return Map.of("today", today);
            }
            if (path.equals("/api/v1/prices")) {
                List<Object> out = new ArrayList<>();
                for (String s : params.get("symbols").split(",")) {
                    if (price.containsKey(s)) {
                        out.add(Map.of("symbol", s, "lastPrice", String.valueOf(price.get(s))));
                    }
                }
                return out;
            }
            if (path.equals("/api/v1/candles")) {  // 최신순: 오늘 봉(2026-10-19), 전일 봉
                String sym = params.get("symbol");
                return Map.of("candles", List.of(Map.of("timestamp", "2026-10-19T09:00:00+09:00", "closePrice", String.valueOf(price.get(sym))),
                        Map.of("timestamp", "2026-10-16T09:00:00+09:00", "closePrice", String.valueOf(prevClose.get(sym)))));
            }
            if (path.equals("/api/v1/sellable-quantity")) {
                return Map.of("sellableQuantity", String.valueOf(held.getOrDefault(params.get("symbol"), 0.0)));
            }
            if (path.equals("/api/v1/holdings")) {
                return Map.of("marketValue", Map.of("amount", Map.of("krw", "1500000", "usd", "820.50")),
                        "totalPurchaseAmount", Map.of("krw", "1400000", "usd", "800.00"),
                        "profitLoss", Map.of("amount", Map.of("krw", "100000", "usd", "20.50")),
                        "items", List.of(Map.of("marketCountry", "KR"), Map.of("marketCountry", "KR"), Map.of("marketCountry", "US")));
            }
            if (path.equals("/api/v1/buying-power")) {
                return Map.of("cashBuyingPower", "KRW".equals(params.get("currency")) ? "250000" : "123.45");
            }
            if (path.startsWith("/api/v1/orders/")) {
                return orders.get(path.substring("/api/v1/orders/".length()));
            }
            throw new IOException("모르는 경로 " + path);
        }

        @Override
        public Object post(String path, Map<String, Object> body, boolean account) throws IOException {
            if (path.endsWith("/cancel")) {
                Map<String, Object> o = orders.get(path.split("/")[4]);
                o.put("status", "CANCELED");
                return Map.of();
            }
            if (rejectBuys && "BUY".equals(body.get("side"))) {
                throw new TossClient.TossApiException(422, "insufficient-buying-power", "주문 가능 금액이 부족합니다.", "req-1");
            }
            String client = (String) body.get("clientOrderId");
            String id = byClient.get(client);
            if (id == null) {  // 같은 clientOrderId면 같은 주문 (멱등성)
                id = "o" + (orders.size() + 1);
                byClient.put(client, id);
                Map<String, Object> o = new HashMap<>(body);
                o.put("status", "PENDING");
                o.put("execution", Map.of("filledQuantity", "0"));
                orders.put(id, o);
                if (fillNow) {
                    fill(id);
                }
            }
            if (failNextPosts > 0) {
                failNextPosts--;
                throw new IOException("응답 시간 초과 (주문은 접수됨)");
            }
            return Map.of("orderId", id, "clientOrderId", client);
        }

        void fill(String id) {
            Map<String, Object> o = orders.get(id);
            String s = (String) o.get("symbol");
            double q = Double.parseDouble((String) o.get("quantity"));
            double p = price.get(s);
            o.put("status", "FILLED");
            o.put("execution", Map.of("filledQuantity", String.valueOf(q), "averageFilledPrice", String.valueOf(p),
                    "filledAmount", String.valueOf(q * p)));
            held.merge(s, "BUY".equals(o.get("side")) ? q : -q, Double::sum);
        }

        List<String> sides() {
            return orders.values().stream().map(o -> o.get("side") + " " + o.get("symbol") + " " + o.get("quantity")).collect(Collectors.toList());
        }
    }

    private FakeToss toss;
    private Path db;
    private ZonedDateTime now;
    private Trader trader;

    @BeforeEach
    void setUp() throws Exception {
        toss = new FakeToss();
        db = Files.createTempDirectory("trader").resolve("trader.db");
        now = ZonedDateTime.of(2026, 10, 19, 9, 1, 0, 0, SEOUL);
        trader = new Trader(toss, db, () -> now);
        toss.price.put("005930", 70_000.0);
        toss.price.put("229200", 10_100.0);  // 코스닥(추종 ETF) 전일 대비 +1%: 기본은 매수 가능
        toss.prevClose.put("229200", 10_000.0);
        toss.price.put("IWM", 202.0);
        toss.prevClose.put("IWM", 200.0);
    }

    private void config(boolean enabled, double budget, int maxStocks, boolean auto) throws Exception {
        Map<String, Object> kr = new HashMap<>();
        kr.put("budget", budget);
        kr.put("maxStocks", maxStocks);
        kr.put("auto", auto);
        Map<String, Object> c = new HashMap<>();
        c.put("enabled", enabled);
        c.put("takeProfit", 3.0);
        c.put("stopLoss", 2.0);
        c.put("KR", kr);
        trader.command(Map.of("action", "config", "config", c));
    }

    private void pick(String symbol) throws Exception {
        trader.command(Map.of("action", "pick", "market", "KR", "symbol", symbol, "name", symbol));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> positions() throws Exception {
        return (List<Map<String, Object>>) trader.status().get("positions");
    }

    @Test
    void buysPickAtOpenThenTakesProfit() throws Exception {
        config(true, 1_000_000, 5, false);  // 종목당 20만 원 -> 7만 원짜리 2주
        pick("005930");
        trader.tick();
        assertEquals(List.of("BUY 005930 2"), toss.sides());
        trader.tick();  // 체결 확인 -> 보유
        assertEquals("OPEN", positions().get(0).get("status"));
        toss.price.put("005930", 72_100.0);  // +3.0% (익절 3%)
        trader.tick();
        assertEquals(List.of("BUY 005930 2", "SELL 005930 2"), toss.sides());
        trader.tick();
        Map<String, Object> p = positions().get(0);
        assertEquals("CLOSED", p.get("status"));
        assertTrue(String.valueOf(p.get("reason")).startsWith("익절"));
        trader.tick();
        assertEquals(2, toss.orders.size(), "같은 날 같은 종목은 다시 사지 않음");
    }

    @Test
    void buysOnlyWhenIndexIsUpFromPrevClose() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        toss.price.put("229200", 10_000.0);  // 보합: 상승 아님
        trader.tick();
        toss.price.put("229200", 9_900.0);   // 하락
        trader.tick();
        assertTrue(toss.sides().isEmpty(), "코스닥이 전일 대비 상승이 아니면 사지 않음");
        assertTrue(String.valueOf(((Map<?, ?>) ((Map<?, ?>) trader.status().get("markets")).get("KR")).get("phase")).contains("코스닥"));
        toss.price.put("229200", 10_050.0);  // 장중에 상승 전환 -> 매수
        trader.tick();
        assertEquals(List.of("BUY 005930 2"), toss.sides());
    }

    @Test
    void stopLossSells() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        trader.tick();
        trader.tick();
        toss.price.put("005930", 68_500.0);  // -2.1%
        trader.tick();
        trader.tick();
        assertTrue(String.valueOf(positions().get(0).get("reason")).startsWith("손절"));
    }

    @Test
    void sellsEverythingTenMinutesBeforeCloseAndStopsBuying() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        trader.tick();
        trader.tick();
        now = ZonedDateTime.of(2026, 10, 19, 15, 20, 0, 0, SEOUL);  // 15:30 마감 10분 전
        toss.price.put("000660", 200_000.0);
        pick("000660");
        trader.tick();
        trader.tick();
        assertEquals(List.of("BUY 005930 2", "SELL 005930 2"), toss.sides(), "마감 10분 전에는 새로 사지 않고 전량 매도");
        assertEquals("장마감", positions().get(0).get("reason"));
        assertEquals("CLOSED", positions().get(0).get("status"));
    }

    @Test
    void cancelsUnfilledBuyAtCutoff() throws Exception {
        config(true, 1_000_000, 5, false);
        toss.fillNow = false;
        pick("005930");
        trader.tick();
        now = ZonedDateTime.of(2026, 10, 19, 15, 21, 0, 0, SEOUL);
        trader.tick();
        assertEquals("CANCELED", toss.orders.get("o1").get("status"));
        assertEquals("FAILED", positions().get(0).get("status"));
    }

    @Test
    void respectsBudgetAndMaxStocks() throws Exception {
        config(true, 300_000, 2, false);  // 종목당 15만 원, 최대 2종목
        toss.price.put("000660", 200_000.0);  // 1주가 종목당 금액보다 비쌈 -> 건너뜀
        toss.price.put("035720", 50_000.0);
        toss.price.put("035420", 30_000.0);
        pick("005930");
        pick("000660");
        pick("035720");
        pick("035420");
        trader.tick();
        assertEquals(List.of("BUY 005930 2", "BUY 035720 3"), toss.sides());
        assertEquals("FAILED", positions().stream().filter(p -> "000660".equals(p.get("symbol"))).findFirst().get().get("status"));
    }

    @Test
    void offStopsNewBuysButStillProtectsHoldings() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        trader.tick();
        trader.tick();
        config(false, 1_000_000, 5, false);
        toss.price.put("000660", 100_000.0);
        pick("000660");
        toss.price.put("005930", 68_000.0);
        trader.tick();
        assertEquals(List.of("BUY 005930 2", "SELL 005930 2"), toss.sides(), "꺼도 손절은 하고 새로 사지 않음");
    }

    @Test
    void doesNothingOnHoliday() throws Exception {
        config(true, 1_000_000, 5, false);
        toss.krOpen = false;
        pick("005930");
        trader.tick();
        assertTrue(toss.orders.isEmpty());
    }

    @Test
    void resendsWithSameKeyWhenResponseIsLost() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        toss.failNextPosts = 1;  // 주문은 들어갔는데 응답을 못 받음
        trader.tick();
        assertEquals(1, toss.orders.size());
        trader.tick();  // 같은 clientOrderId로 다시 보내 결과를 받음 -> 두 번 사지 않음
        trader.tick();
        assertEquals(1, toss.orders.size());
        assertEquals("OPEN", positions().get(0).get("status"));
    }

    @Test
    void onlyOneServerTradesWithSameDb() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        FakeToss other = new FakeToss();
        other.price.put("005930", 70_000.0);
        Trader second = new Trader(other, db, () -> now);
        trader.tick();
        second.tick();
        assertEquals(1, toss.orders.size());
        assertTrue(other.orders.isEmpty(), "임대를 가진 서버만 주문");
    }

    @Test
    void buysSignalStocksOnlyWhenAutoIsOn() throws Exception {
        config(true, 1_000_000, 5, false);
        toss.price.put("247540", 100_000.0);
        trader.tick();
        trader.onSignal("KR", "247540", "에코프로비엠");
        trader.tick();
        assertTrue(toss.orders.isEmpty(), "자동 매수가 꺼져 있으면 사지 않음");
        config(true, 1_000_000, 5, true);
        trader.tick();
        assertEquals(List.of("BUY 247540 2"), toss.sides());
    }

    @Test
    void sellAllSellsAndHaltsForTheDay() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        trader.tick();
        trader.tick();
        trader.command(Map.of("action", "sellAll", "market", "KR"));
        toss.price.put("000660", 100_000.0);
        pick("000660");
        trader.tick();
        trader.tick();
        assertEquals(List.of("BUY 005930 2", "SELL 005930 2"), toss.sides());
        assertEquals("CLOSED", positions().get(0).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsTossAccountByCurrency() throws Exception {
        trader.tick();
        Map<String, Object> a = (Map<String, Object>) trader.status().get("account");
        Map<String, Object> krw = (Map<String, Object>) a.get("KRW");
        Map<String, Object> usd = (Map<String, Object>) a.get("USD");
        assertEquals(250000.0, krw.get("cash"));
        assertEquals(1500000.0, krw.get("stock"));
        assertEquals(2, krw.get("count"));
        assertEquals(123.45, usd.get("cash"));
        assertEquals(820.5, usd.get("stock"));
        assertEquals(20.5, usd.get("profit"));
        assertEquals(1, usd.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsBuyHistoryWithFillTimeAndProfit() throws Exception {
        config(true, 1_000_000, 5, false);
        toss.price.put("000660", 300_000.0);  // 종목당 20만 원보다 비싸 실패 -> 내역에 없음
        pick("005930");
        pick("000660");
        trader.tick();
        trader.tick();
        toss.price.put("005930", 72_100.0);
        trader.tick();
        trader.tick();
        List<Map<String, Object>> h = (List<Map<String, Object>>) trader.status().get("history");
        assertEquals(1, h.size());
        Map<String, Object> x = h.get(0);
        assertEquals("005930", x.get("symbol"));
        assertEquals("2026-10-19 09:01:00", x.get("bought"));  // 토스 체결 시각이 없으면 지금
        assertEquals("CLOSED", x.get("status"));
        assertEquals(3.0, (Double) x.get("pnl"), 1e-9);
        assertEquals(4_200.0, (Double) x.get("pnlAmount"), 1e-9);  // (72,100 - 70,000) × 2주
    }

    @Test
    @SuppressWarnings("unchecked")
    void upgradesOldDbAndKeepsEarlierBuys() throws Exception {
        Path old = Files.createTempDirectory("trader-old").resolve("trader.db");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + old);
             java.sql.Statement st = c.createStatement()) {  // 매수 내역 기능 전의 trader_position (bought 칸 없음)
            st.execute("CREATE TABLE trader_position (id INTEGER PRIMARY KEY AUTOINCREMENT, market TEXT NOT NULL, day TEXT NOT NULL,"
                    + " symbol TEXT NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL,"
                    + " order_qty REAL, est REAL, qty REAL, avg REAL, amount REAL,"
                    + " buy_client TEXT, buy_order TEXT, buy_sent INTEGER, sell_client TEXT, sell_order TEXT, sell_sent INTEGER,"
                    + " sell_qty REAL, sell_avg REAL, reason TEXT, note TEXT, opened TEXT, closed TEXT, UNIQUE (market, day, symbol))");
            st.execute("INSERT INTO trader_position (market, day, symbol, name, source, status, qty, avg, est, opened)"
                    + " VALUES ('KR', '2026-10-16', '005930', '삼성전자', '직접 고름', 'CLOSED', 2, 70000, 140000, '2026-10-16 09:00:05')");
            st.execute("INSERT INTO trader_position (market, day, symbol, name, source, status, qty, est, opened)"
                    + " VALUES ('KR', '2026-10-16', '000660', 'SK하이닉스', '직접 고름', 'FAILED', 0, 0, '2026-10-16 09:00:05')");
        }
        List<Map<String, Object>> h = (List<Map<String, Object>>) new Trader(toss, old, () -> now).status().get("history");
        assertEquals(1, h.size());
        assertEquals("2026-10-16 09:00:05", h.get(0).get("bought"));
    }

    @Test
    void writesEveryOrderAttemptToTraderLog() throws Exception {
        config(true, 1_000_000, 5, false);
        pick("005930");
        toss.failNextPosts = 1;
        trader.tick();  // 요청 -> 응답 없음
        trader.tick();  // 같은 키로 다시 -> 접수
        toss.rejectBuys = true;
        toss.price.put("000660", 100_000.0);
        pick("000660");
        trader.tick();  // 거절
        String log = new String(Files.readAllBytes(trader.logFile()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(log.contains("국내 주문 요청: 매수 005930 2주 시장가"), log);
        assertTrue(log.contains("주문 응답 없음: 매수 005930 2주"), log);
        assertTrue(log.contains("주문 다시 보냄 (응답을 못 받았던 주문, 같은 키라 두 번 체결 안 됨): 매수 005930 2주"), log);
        assertTrue(log.contains("주문 접수: 매수 005930 2주 시장가") && log.contains("-> 주문번호 o1"), log);
        assertTrue(log.contains("주문 거절: 매수 000660 2주 시장가") && log.contains("insufficient-buying-power"), log);
        assertTrue(log.contains("[자동매매] 2026-10-19 09:01:00 국내 매수 체결: 005930(005930) 2주"), log);
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainsWhyNothingIsBoughtOncePerChange() throws Exception {
        config(true, 1_000_000, 5, false);
        trader.tick();
        trader.tick();
        Map<String, Object> kr = (Map<String, Object>) ((Map<String, Object>) trader.status().get("markets")).get("KR");
        assertEquals("매매 중 · 매수 대상 없음: 직접 고른 국내 종목이 없고 시그널 자동 매수가 꺼져 있음", kr.get("phase"));
        pick("005930");
        trader.tick();
        trader.tick();
        String log = new String(Files.readAllBytes(trader.logFile()), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, log.split("상태: 매매 중 · 매수 대상 없음").length - 1, "같은 이유는 한 번만 남김\n" + log);
        assertTrue(log.contains("주문 담당: 이 서버"), log);
        assertTrue(log.contains("상태: 매매 중 · 오늘 매수 대상 1종목을 모두 처리함"), log);
        toss.price.put("000660", 100_000.0);
        config(true, 100_000, 1, false);
        pick("000660");
        trader.tick();
        kr = (Map<String, Object>) ((Map<String, Object>) trader.status().get("markets")).get("KR");
        assertEquals("매매 중 · 오늘 최대 종목 수(1) 도달, 남은 매수 대상은 사지 않음", kr.get("phase"));
        now = ZonedDateTime.of(2026, 10, 19, 8, 30, 0, 0, SEOUL);
        trader.tick();
        kr = (Map<String, Object>) ((Map<String, Object>) trader.status().get("markets")).get("KR");
        assertEquals("장 시작 전 (정규장 10-19 09:00 시작, 한국 시간)", kr.get("phase"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesNewYorkDateForUsMarket() throws Exception {
        // 한국 화요일 새벽 02:00 = 뉴욕 월요일 13:00 (장중). 한국 날짜로 물으면 휴장이라고 잘못 판단했던 경우
        now = ZonedDateTime.of(2026, 10, 20, 2, 0, 0, 0, SEOUL);
        Map<String, Object> us = new HashMap<>();
        us.put("budget", 500.0);
        us.put("maxStocks", 1);
        us.put("auto", false);
        Map<String, Object> c = new HashMap<>();
        c.put("enabled", true);
        c.put("US", us);
        trader.command(Map.of("action", "config", "config", c));
        trader.command(Map.of("action", "pick", "market", "US", "symbol", "AAPL", "name", "Apple"));
        toss.price.put("AAPL", 230.0);
        trader.tick();
        Map<String, Object> m = (Map<String, Object>) ((Map<String, Object>) trader.status().get("markets")).get("US");
        assertEquals("2026-10-19", m.get("day"));
        assertEquals(List.of("BUY AAPL 2"), toss.sides());
        Map<String, Object> kr = (Map<String, Object>) ((Map<String, Object>) trader.status().get("markets")).get("KR");
        assertEquals("휴장", kr.get("phase"), "국내는 한국 날짜(10-20)로 물어 휴장");
    }

    @Test
    void rejectsBadSettings() {
        assertThrows(IllegalArgumentException.class, () -> trader.command(Map.of("action", "config", "config", Map.of("takeProfit", 0))));
        assertThrows(IllegalArgumentException.class, () -> trader.command(Map.of("action", "pick", "market", "KR", "symbol", "12")));
        assertThrows(IllegalArgumentException.class, () -> trader.command(Map.of("action", "nope")));
        assertFalse(Trader.fmtQty(2.0).contains("."));
        assertEquals("0.5", Trader.fmtQty(0.5));
    }
}
