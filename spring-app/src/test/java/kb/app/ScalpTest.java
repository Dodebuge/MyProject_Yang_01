package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 1분 이내 단타 (Scalp).
 * -Dscalp.live=true 를 주면 데이터 폴더 minutes.db의 마지막 두 날(어제·오늘)로 격자 탐색해 상위 조합을 출력합니다.
 *   ./gradlew test --tests ScalpTest -Dscalp.live=true -Dkb.data.dir=/opt/kb_openapi_sample -i
 */
class ScalpTest {

    /** 09:00부터 1분마다 거래량 100·가격 1000인 봉 25개 + 09:25 신호 봉(거래량 10배, +2%, 거래대금 10억) + 다음 봉 */
    private static Scalp.Stock stock(String nextAt, double h, double l, double c) {
        return stock(String.format("[\"%s\",1020,%s,%s,%s,500,500000000]", nextAt, h, l, c));
    }

    /** tail = 신호 봉 뒤의 봉들 "[...],[...]" */
    private static Scalp.Stock stock(String tail) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            b.append(String.format("[\"09%02d\",1000,1000,1000,1000,100,100000],", i));
        }
        b.append("[\"0925\",1000,1020,1000,1020,1000,1000000000],");
        b.append(tail).append("]");
        return Scalp.parse("000001", "가", b.toString());
    }

    private static List<Scalp.Trade> run(Scalp.Stock s) {
        return run(s, new Scalp.Params());  // 기본: volX 5, up 1%, 2억, 익절·손절 1%, 1분, 비용 0.23%
    }

    private static List<Scalp.Trade> run(Scalp.Stock s, Scalp.Params p) {
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        days.put("2026-10-26", List.of(s));
        return Scalp.run(days, p);
    }

    /** 손절은 손절가 지정가: 갭으로 손절가 아래면 다시 올 때까지 기다려 손절가에 팔고, 장마감 30분 전부터는 시장가 */
    @Test
    void stopLossIsLimitAtStopPriceUntilThirtyMinutesBeforeClose() {
        // 산 값 1020, 손절가 1009.8. 09:27 갭 하락(고가 1005) -> 09:28 고가 1012에서 손절가로 체결
        Scalp.Stock s = stock("[\"0926\",1020,1025,1015,1020,500,500000000],[\"0927\",1000,1005,995,1000,500,500000000],"
                + "[\"0928\",1000,1012,998,1010,500,500000000]");
        Scalp.Params p = new Scalp.Params();
        p.hold = 2;
        Scalp.Trade t = run(s, p).get(0);
        assertEquals("손절", t.reason);
        assertEquals(1009.8, t.sellPrice, 1e-9);
        assertEquals("09:28", Scalp.hm(t.sellAt));

        p.marketFrom = 9 * 60 + 27;  // 09:27부터 장마감 30분 전으로 치면 그 봉 시가에 시장가
        t = run(s, p).get(0);
        assertEquals("손절 시장가", t.reason);
        assertEquals(1000, t.sellPrice);
        assertEquals("09:27", Scalp.hm(t.sellAt));

        p.marketFrom = 9 * 60 + 28;  // 지정가 대기 중 장마감 30분 전이 되면 취소하고 시장가
        s = stock("[\"0926\",1020,1025,1015,1020,500,500000000],[\"0927\",1000,1005,995,1000,500,500000000],"
                + "[\"0928\",1001,1003,998,1000,500,500000000]");
        t = run(s, p).get(0);
        assertEquals("손절 시장가", t.reason);
        assertEquals(1001, t.sellPrice);
        assertEquals("09:28", Scalp.hm(t.sellAt));
    }

    @Test
    void buysNextOpenAfterVolumeSpikeAndSellsWithinOneMinute() {
        List<Scalp.Trade> t = run(stock("0926", 1035, 1015, 1025));
        assertEquals(1, t.size());
        assertEquals("09:26", Scalp.hm(t.get(0).buyAt));
        assertEquals(1020, t.get(0).buyPrice);
        assertEquals("익절", t.get(0).reason);
        assertEquals(1030.2, t.get(0).sellPrice, 1e-9);
        assertEquals(980, t.get(0).qty);
        assertEquals(Math.round(980 * 1030.2 * 0.9977 - 999600), t.get(0).pnl);

        assertEquals("손절", run(stock("0926", 1035, 1000, 1025)).get(0).reason);  // 한 봉에 둘 다면 손절로 봄
        Scalp.Trade time = run(stock("0926", 1025, 1015, 1022)).get(0);
        assertEquals("시간", time.reason);
        assertEquals(1022, time.sellPrice);
        assertEquals("09:26", Scalp.hm(time.sellAt));
        assertEquals(0, run(stock("0927", 1035, 1015, 1025)).size());  // 다음 분에 거래가 없으면 안 삼
    }

    @Test
    void gridKeepsCombosWithEnoughTradesSortedByPnl() {
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        days.put("2026-10-26", List.of(stock("0926", 1035, 1015, 1025)));
        Map<String, Object> g = Scalp.grid(days, new Scalp.Params(), 1, 5);
        assertEquals(4 * 4 * 3 * 5 * 4 * 2, g.get("combos"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) g.get("top");
        assertEquals(5, top.size());
        assertEquals(true, (long) top.get(0).get("pnl") >= (long) top.get(4).get("pnl"));
    }

    /** 15분봉(09:30~09:44)이 양봉·종가 = 고가면 09:45 봉 시가에 사서 +5% 익절 */
    @Test
    void buysAfterFifteenMinuteBarWithoutUpperWick() {
        StringBuilder b = new StringBuilder("[");
        for (int m = 0; m < 15; m++) {  // 09:30~09:44: 1000에서 1014까지 오르고 마지막 봉 종가가 고가
            b.append(String.format("[\"09%02d\",%d,%d,%d,%d,100,100000000],", 30 + m, 1000 + m, 1001 + m, 999 + m, 1001 + m));
        }
        b.append("[\"0945\",1015,1020,1010,1018,100,100000000],[\"0950\",1018,1070,1015,1060,100,100000000]]");
        Scalp.Params p = new Scalp.Params();
        p.wick15 = true;
        p.start = 9 * 60 + 30;
        p.tp = 5;
        p.sl = 3;
        p.hold = 400;
        p.minValue = 0;
        List<Scalp.Trade> t = run(Scalp.parse("000001", "가", b.toString()), p);
        assertEquals(1, t.size());
        assertEquals("09:30", Scalp.hm(t.get(0).signalAt));
        assertEquals("09:45", Scalp.hm(t.get(0).buyAt));
        assertEquals("익절", t.get(0).reason);
        assertEquals(1015 * 1.05, t.get(0).sellPrice, 1e-9);
        assertEquals("09:50", Scalp.hm(t.get(0).sellAt));

        String wick = b.toString().replace("[\"0944\",1014,1015,1013,1015,", "[\"0944\",1014,1016,1013,1015,");  // 고가 > 종가 = 꼬리
        assertEquals(0, run(Scalp.parse("000001", "가", wick), p).size());
    }

    /** 09:10~09:29 1000 횡보, 09:30~09:44 1000 -> 1045 장대양봉(+4.5%), 09:45~ 1050, 09:55 1015로 떨어짐. 봉마다 거래량 vol */
    private static String bigCandle(int vol) {
        StringBuilder b = new StringBuilder("[");
        for (int m = 10; m < 30; m++) {
            b.append(String.format("[\"09%02d\",1000,1000,1000,1000,%d,100000],", m, vol));
        }
        for (int m = 0; m < 15; m++) {  // 1000 -> 1045
            b.append(String.format("[\"09%02d\",%d,%d,%d,%d,%d,100000],", 30 + m, 1000 + 3 * m, 1003 + 3 * m, 1000 + 3 * m, 1003 + 3 * m, vol));
        }
        for (int m = 45; m < 55; m++) {
            b.append(String.format("[\"09%02d\",1050,1050,1050,1050,%d,100000],", m, vol));
        }
        // 1015 < 20봉 전 09:35 종가 1018
        b.append(String.format("[\"0955\",1050,1050,1015,1015,%d,100000],[\"0956\",1016,1020,1010,1012,%d,100000]]", vol, vol));
        return b.toString();
    }

    /** 이평 타기: 이평 위면 사고, +2% 익절 뒤 다시 사고, 이평 아래로 가면 팔고 그날 끝 (여기서는 3분 이평) */
    @Test
    void ridesAboveAverageRebuyingAfterTakeProfitUntilItBreaks() {
        String bars = "[[\"0905\",1000,1000,1000,1000,100,100000],[\"0906\",1000,1000,1000,1000,100,100000],"
                + "[\"0907\",1000,1010,1000,1010,100,100000],"   // 1010 > 이평 1003.3 -> 09:08 시가 1010에 삼
                + "[\"0908\",1010,1035,1010,1030,100,100000],"   // +2% 1030.2 익절, 종가 1030 > 이평 1016.7 -> 09:09에 다시 삼
                + "[\"0909\",1030,1035,1025,1032,100,100000],"
                + "[\"0910\",1030,1031,1000,1005,100,100000],"   // 1005 < 이평 1022.3 -> 09:11 시가에 팔고 끝
                + "[\"0911\",1004,1004,1000,1003,100,100000],"
                + "[\"0912\",1003,1100,1003,1100,100,100000],[\"0913\",1100,1100,1100,1100,100,100000]]";  // 다시 올라도 안 삼
        Scalp.Params p = new Scalp.Params();
        p.ride = true;
        p.maBelow = 3;
        p.tp = 2;
        p.sl = 0;
        p.hold = 400;
        List<Scalp.Trade> t = run(Scalp.parse("000001", "가", bars), p);
        assertEquals(2, t.size());
        assertEquals("09:08", Scalp.hm(t.get(0).buyAt));
        assertEquals("익절", t.get(0).reason);
        assertEquals(1030.2, t.get(0).sellPrice, 1e-9);
        assertEquals("09:09", Scalp.hm(t.get(1).buyAt));
        assertEquals(1030, t.get(1).buyPrice);
        assertEquals("이평 아래", t.get(1).reason);
        assertEquals("09:11", Scalp.hm(t.get(1).sellAt));
        assertEquals(1004, t.get(1).sellPrice);
    }

    /** -Dscalp.live=true: 최종 조합을 한 번 30만원으로, 하루 +2만원·−1만원 멈춤·동시 보유 30만원 한도를 더해 비교 */
    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveBudget() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : bars.days()) {
            days.put(d, bars.stocks(d));
        }
        // {하루 +멈춤 원, 하루 −멈춤 원, 동시 보유 한도 원}
        for (int[] v : new int[][] {{0, 0, 0}, {20000, 10000, 0}, {0, 0, 300000}, {20000, 10000, 300000}}) {
            Scalp.Params p = new Scalp.Params();
            p.ride = true;
            p.aligned = true;
            p.prevVol = 200;
            p.maBelow = 30;
            p.tp = 2;
            p.sl = 2;
            p.dip = 1.5;
            p.hold = 400;
            p.start = 9 * 60 + 5;
            p.until = 14 * 60 + 50;
            p.maxDayUp = 20;
            p.amount = 300_000;
            p.dayStop = v[0];
            p.dayLoss = v[1];
            p.budget = v[2];
            List<Scalp.Trade> ts = Scalp.run(days, p);
            System.out.println("[30만원씩 · +멈춤 " + v[0] + " · −멈춤 " + v[1] + " · 동시 보유 한도 " + v[2] + "] " + Scalp.summary(ts, null));
            for (String d : days.keySet()) {
                int last = ts.stream().filter(t -> t.date.equals(d)).mapToInt(t -> t.buyAt).max().orElse(-1);
                long low = 0;
                long run = 0;
                List<Scalp.Trade> sold = new ArrayList<>(ts.stream().filter(t -> t.date.equals(d)).collect(java.util.stream.Collectors.toList()));
                sold.sort(java.util.Comparator.comparingInt(t -> t.sellAt));
                for (Scalp.Trade t : sold) {
                    run += t.pnl;
                    low = Math.min(low, run);
                }
                System.out.println("  " + d + " " + Scalp.summary(ts, d) + (last >= 0 ? " 마지막 매수 " + Scalp.hm(last) + " 누계 최저 " + low : ""));
            }
        }
    }

    /** dip: 익절 뒤 판 값 −1.5%에 지정가로 다시 삼. 닿기 전에 이평 아래면 끝 */
    @Test
    void ridesRebuyingOnDipAfterTakeProfit() {
        String bars = "[[\"0905\",1000,1000,1000,1000,100,100000],[\"0906\",1000,1000,1000,1000,100,100000],"
                + "[\"0907\",1000,1010,1000,1010,100,100000],"   // 09:08 시가 1010에 삼
                + "[\"0908\",1010,1045,1010,1040,100,100000],"   // +3% 1040.3 익절 -> 다시 살 값 1040.3 × 0.985 = 1024.7
                + "[\"0909\",1040,1042,1030,1035,100,100000],"   // 저가 1030: 아직
                + "[\"0910\",1035,1036,1020,1030,100,100000],"   // 저가 1020 <= 1024.7 -> 1024.7에 삼, 종가 1030 < 이평 1035
                + "[\"0911\",1030,1031,1000,1001,100,100000]]";  // -> 09:11 시가 1030에 팔고 끝
        Scalp.Params p = new Scalp.Params();
        p.ride = true;
        p.maBelow = 3;
        p.tp = 3;
        p.sl = 0;
        p.hold = 400;
        p.dip = 1.5;
        List<Scalp.Trade> t = run(Scalp.parse("000001", "가", bars), p);
        assertEquals(2, t.size());
        assertEquals("익절", t.get(0).reason);
        assertEquals("09:10", Scalp.hm(t.get(1).buyAt));
        assertEquals(1010 * 1.03 * 0.985, t.get(1).buyPrice, 1e-9);
        assertEquals("이평 아래", t.get(1).reason);
        assertEquals("09:11", Scalp.hm(t.get(1).sellAt));
        assertEquals(1030, t.get(1).sellPrice);

        String noDip = bars.replace("[\"0910\",1035,1036,1020,1030,", "[\"0910\",1035,1036,1028,1010,");  // 1024.7까지 안 내려오고 이평 아래
        assertEquals(1, run(Scalp.parse("000001", "가", noDip), p).size());
    }

    private static Scalp.Trade tr(String date, String buyAt, String sellAt, long pnl) {
        Scalp.Trade t = new Scalp.Trade();
        t.date = date;
        t.buyAt = Scalp.mins(buyAt);
        t.sellAt = Scalp.mins(sellAt);
        t.pnl = pnl;
        return t;
    }

    /** dayStop: 그날 실현 손익 누계가 3만원 이상이 된 뒤에 산 거래는 뺌. 그 전에 산 것·다음 날은 그대로 */
    @Test
    void stopsBuyingForTheDayAfterProfitTarget() {
        List<Scalp.Trade> ts = List.of(
                tr("2026-10-23", "1000", "1010", 20000),
                tr("2026-10-23", "1005", "1020", 15000),   // 10:20에 누계 3.5만원
                tr("2026-10-23", "1015", "1030", -40000),  // 멈추기 전에 샀으니 남음
                tr("2026-10-23", "1020", "1025", 5000),    // 같은 분: 판 것은 산 뒤로 봄 -> 남음
                tr("2026-10-23", "1031", "1040", 9000),    // 누계가 -0.5만원으로 내려갔어도 한 번 넘었으니 멈춤
                tr("2026-10-26", "0930", "0940", 1000));   // 다음 날은 다시
        Scalp.Params p = new Scalp.Params();
        p.dayStop = 30000;
        List<Scalp.Trade> kept = Scalp.limitDay(new ArrayList<>(ts), p);
        assertEquals(List.of(ts.get(0), ts.get(1), ts.get(2), ts.get(3), ts.get(5)), kept);

        p.dayStop = 0;
        p.dayLoss = 30000;  // 누계 2 → 3.5 → 4 → 0 → 0.9만원: 손실 한도에 안 닿음
        assertEquals(6, Scalp.limitDay(new ArrayList<>(ts), p).size());
        List<Scalp.Trade> loss = List.of(
                tr("2026-10-23", "1000", "1010", -20000),
                tr("2026-10-23", "1005", "1020", -15000),  // 10:20에 누계 −3.5만원
                tr("2026-10-23", "1015", "1030", 50000),   // 그 전에 샀으니 남음
                tr("2026-10-23", "1021", "1025", 1000));   // 멈춤
        assertEquals(loss.subList(0, 3), Scalp.limitDay(new ArrayList<>(loss), p));
    }

    /** budget: 들고 있는 금액 + 이 거래가 한도를 넘으면 빼고, 그 종목은 그날 뒤 거래도 뺌 */
    @Test
    void budgetLimitsMoneyHeldAtOnce() {
        Scalp.Trade a = tr("2026-10-23", "1000", "1010", 0);
        Scalp.Trade b = tr("2026-10-23", "1005", "1008", 0);  // a를 들고 있는 중 -> 30만원 넘음
        Scalp.Trade b2 = tr("2026-10-23", "1012", "1015", 0); // b 종목의 재매수: 앞이 빠졌으니 뺌
        Scalp.Trade c = tr("2026-10-23", "1010", "1020", 0);  // a가 10:10에 팔림 (같은 분은 판 게 뒤) -> 아직 들고 있음 -> 뺌
        Scalp.Trade d = tr("2026-10-23", "1011", "1020", 0);  // a 판 뒤 -> 삼
        a.code = "A";
        b.code = "B";
        b2.code = "B";
        c.code = "C";
        d.code = "D";
        for (Scalp.Trade t : List.of(a, b, b2, c, d)) {
            t.invested = 300000;
        }
        Scalp.Params p = new Scalp.Params();
        p.budget = 300000;
        assertEquals(List.of(a, d), Scalp.limitDay(new ArrayList<>(List.of(a, b, c, d, b2)), p));
    }

    /** live: 후보(전날 대비 거래량·스팩·가격) 고르기, 끝난 봉만 보기, 첫 매수 조건이면 사고 들고 있는 종목은 이평 아래면 팖 */
    @Test
    void liveSignalBuysCandidatesAndSellsHeldBelowAverage() {
        List<List<Object>> up = new ArrayList<>();  // 09:00부터 130분 동안 1원씩 오름 (정배열, 이평 위, 시가 대비 +13%)
        for (int i = 0; i < 130; i++) {
            int t = 9 * 60 + i;
            up.add(List.of(String.format("%02d%02d", t / 60, t % 60), 1000.0 + i, 1000.0 + i, 1000.0 + i, 1000.0 + i, 100.0, 100000.0));
        }
        List<List<Object>> down = new ArrayList<>(up);
        down.set(129, List.of("1109", 1129.0, 1129.0, 900.0, 900.0, 100.0, 100000.0));  // 마지막 봉 급락: 이평 아래
        List<Map<String, Object>> stocks = List.of(
                Map.of("code", "A", "name", "가", "price", 1129, "change", 5, "prevRatio", 250),
                Map.of("code", "B", "name", "나", "price", 1129, "change", 5, "prevRatio", 150),      // 거래량 부족
                Map.of("code", "C", "name", "다스팩", "price", 1129, "change", 5, "prevRatio", 900), // 스팩
                Map.of("code", "D", "name", "라", "price", 400_000, "change", 5, "prevRatio", 900),  // 1주가 30만 원보다 비쌈
                Map.of("code", "E", "name", "마", "price", 1129, "change", 5, "prevRatio", 300));
        List<List<String>> asked = new ArrayList<>();
        Scalp.Params p = Scalp.params(Map.of());
        int now = 9 * 60 + 130;  // 11:10: 11:09 봉까지 끝남
        Map<String, Object> sig = Scalp.live(stocks, codes -> {
            asked.add(codes);
            Map<String, List<List<Object>>> m = new LinkedHashMap<>();
            for (String c : codes) {
                List<List<Object>> rows = new ArrayList<>("H".equals(c) ? down : up);
                rows.add(List.of("1110", 2000.0, 2000.0, 2000.0, 2000.0, 100.0, 100000.0));  // 아직 안 끝난 봉은 안 봄
                m.put(c, rows);
            }
            return m;
        }, p, now, java.util.Set.of("H"), 20);
        assertEquals(List.of(List.of("H", "E", "A")), asked, "보유 종목 + 거래량 증가율 큰 순 후보");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buys = (List<Map<String, Object>>) sig.get("buy");
        assertEquals(List.of("E", "A"), buys.stream().map(b -> b.get("code")).collect(java.util.stream.Collectors.toList()));
        assertEquals(1129.0, buys.get(0).get("price"));
        assertEquals(Map.of("H", "이평 아래"), sig.get("sell"));

        p.until = 11 * 60;  // 11:00 뒤에는 안 삼
        assertEquals(List.of(), Scalp.live(stocks, codes -> Map.of(), p, now, java.util.Set.of(), 20).get("buy"));
    }

    /** calm: 이평 +maxGap%·당일 시가 +maxDayUp%보다 위면 안 삼 */
    @Test
    void calmRejectsStretchedPrices() {
        Scalp.Stock st = Scalp.parse("000001", "가", "[[\"0900\",1000,1000,1000,1000,100,100000],[\"0901\",1000,1000,1000,1000,100,100000]]");
        Scalp.Params p = new Scalp.Params();
        p.maBelow = 30;
        assertEquals(true, Scalp.calm(st, 1, 1300, p));  // 0이면 안 봄
        p.maxGap = 5;
        assertEquals(true, Scalp.calm(st, 1, 1050, p));
        assertEquals(false, Scalp.calm(st, 1, 1051, p));
        p.maxGap = 0;
        p.maxDayUp = 20;
        assertEquals(true, Scalp.calm(st, 1, 1200, p));
        assertEquals(false, Scalp.calm(st, 1, 1201, p));
    }

    /** 1분봉 이평 정배열: 120봉 이상 오르기만 하면 정배열, 120봉이 안 되거나 마지막에 꺾이면 아님 */
    @Test
    void alignedNeedsFiveOverTwentyOverSixtyOverOneTwenty() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 130; i++) {
            int t = 9 * 60 + i;
            b.append(String.format("%s[\"%02d%02d\",%d,%d,%d,%d,100,100000]", i > 0 ? "," : "", t / 60, t % 60, 1000 + i, 1000 + i, 1000 + i, 1000 + i));
        }
        Scalp.Stock st = Scalp.parse("000001", "가", b + "]");
        assertEquals(true, Scalp.aligned(st, 129));
        assertEquals(false, Scalp.aligned(st, 118));
        st.c[129] = 900;  // 마지막 봉이 급락: 5분 이평이 20분 아래
        st.cumC[130] = st.cumC[129] + 900;
        assertEquals(false, Scalp.aligned(st, 129));
    }

    /** -Dscalp.live=true: 거래량 전날 대비 +300%↑ 종목을 30분 이평 위에서 반복 매매 (+2% 익절 후 재매수, 이평 아래면 팔고 끝) */
    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveRide() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : bars.days()) {
            days.put(d, bars.stocks(d));
        }
        // {시작 시각(분), 전날 대비 거래량 %, 익절 % ×10, 다시 살 값 판 값 −dip% (×10, 0 = 이평 위면 바로), 1 = 첫 매수는 1분봉 이평 정배열일 때만}
        // 익절 2% · 1분봉 정배열 · 거래량 +200%↑, 뒤 칸 = {손절 %, 이평 이격 % 한도, 당일 시가 대비 % 한도, 마지막 매수 시각(분)} (0 = 안 봄)
        int[][] vs = {{545, 200, 20, 15, 1, 0, 0, 0, 915}, {545, 200, 20, 15, 1, 2, 0, 0, 915},
            {545, 200, 20, 15, 1, 2, 5, 0, 915}, {545, 200, 20, 15, 1, 2, 0, 20, 915}, {545, 200, 20, 15, 1, 2, 0, 0, 890},
            {545, 200, 20, 15, 1, 2, 5, 20, 890}, {545, 200, 20, 15, 1, 0, 5, 20, 890},
            {545, 200, 20, 15, 1, 2, 0, 20, 890}, {545, 200, 20, 15, 1, 0, 0, 20, 890}};
        for (int[] v : vs) {
            Scalp.Params p = new Scalp.Params();
            p.aligned = v[4] == 1;
            p.dip = v[3] / 10.0;
            p.ride = true;
            p.start = v[0];
            p.prevVol = v[1];
            p.tp = v[2] / 10.0;
            p.sl = v[5];
            p.maxGap = v[6];
            p.maxDayUp = v[7];
            p.until = v[8];
            p.maBelow = 30;
            p.hold = 400;
            List<Scalp.Trade> ts = Scalp.run(days, p);
            Map<String, Integer> why = new java.util.TreeMap<>();
            java.util.Set<String> stocks = new java.util.HashSet<>();
            long held = 0;
            for (Scalp.Trade t : ts) {
                why.merge(t.reason, 1, Integer::sum);
                stocks.add(t.date + t.code);
                held += t.sellAt - t.buyAt;
            }
            System.out.println("[이평 타기 30분 · 시작 " + Scalp.hm(v[0]) + " · 거래량 +" + v[1] + "%↑ · 익절 " + p.tp + "% · 재매수 " + (v[3] > 0 ? "판 값 −" + p.dip + "%" : "바로") + (p.aligned ? " · 1분봉 정배열" : "") + (p.sl > 0 ? " · 손절 " + p.sl + "%" : "")
                    + (p.maxGap > 0 ? " · 이격≤" + p.maxGap + "%" : "") + (p.maxDayUp > 0 ? " · 시가대비≤" + p.maxDayUp + "%" : "") + " · ~" + Scalp.hm(p.until) + "] " + Scalp.summary(ts, null)
                    + " 이유 " + why + " 종목·날 " + stocks.size() + " 평균 보유 " + (ts.isEmpty() ? 0 : held / ts.size()) + "분");
            for (String d : days.keySet()) {
                System.out.println("  " + d + " " + Scalp.summary(ts, d));
            }
            if (v == vs[7]) {  // 손절 + 시가 대비 + 14:50에 '하루 3만원 벌면 멈춤'을 더하면
                Scalp.Params lim = new Scalp.Params();
                lim.dayStop = 30000;
                List<Scalp.Trade> st = Scalp.limitDay(new ArrayList<>(ts), lim);
                System.out.println("  [+ 하루 3만원 이상이면 그날 멈춤] " + Scalp.summary(st, null));
                for (String d : days.keySet()) {
                    int last = st.stream().filter(t -> t.date.equals(d)).mapToInt(t -> t.buyAt).max().orElse(-1);
                    System.out.println("    " + d + " " + Scalp.summary(st, d) + (last >= 0 ? " 마지막 매수 " + Scalp.hm(last) : ""));
                }
            }
            if (v == vs[7]) {  // 손절 + 시가 대비 + 14:50은 거래 하나하나
                for (Scalp.Trade t : ts) {
                    System.out.println("    " + t.date + " " + t.code + " " + t.name + " " + Scalp.hm(t.buyAt) + " " + t.buyPrice + " -> "
                            + Scalp.hm(t.sellAt) + " " + Math.round(t.sellPrice * 100) / 100.0 + " " + t.reason + " " + t.pnl);
                }
            }
        }
    }

    /** prevVol 100: 전날 같은 시각까지 누적 거래량의 2배 이상일 때만 (전날 출처가 같아야 함) */
    @Test
    void prevVolBuysOnlyWhenVolumeDoubledFromPreviousDay() {
        Scalp.Params p = new Scalp.Params();
        p.big = 3;
        p.start = 9 * 60 + 30;
        p.tp = 0;
        p.sl = 0;
        p.hold = 400;
        p.minValue = 0;
        p.maSell = 20;
        p.prevVol = 100;
        for (Object[] c : new Object[][] {{50, "kb", 1}, {60, "kb", 0}, {50, "toss", 0}}) {  // {전날 봉 거래량, 전날 출처, 거래 수}
            Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
            days.put("2026-10-23", List.of(Scalp.parse("000001", "가", (String) c[1], bigCandle((int) c[0]))));
            days.put("2026-10-26", List.of(Scalp.parse("000001", "가", "kb", bigCandle(100))));
            List<Scalp.Trade> t = Scalp.run(days, p);
            assertEquals((int) c[2], t.size(), "전날 " + c[0] + " " + c[1]);
            if (!t.isEmpty()) {
                assertEquals("2026-10-26", t.get(0).date);  // 첫날은 비교할 전날이 없음
            }
        }
    }

    /** 09:30~09:44 15분봉 장대양봉(+4.5%) -> 09:45 시가에 시장가 매수, 20분 이평이 꺾인 09:55 봉 다음 09:56 시가에 매도 */
    @Test
    void buysBigFifteenMinuteCandleAndSellsWhenTwentyMinuteAverageTurnsDown() {
        Scalp.Params p = new Scalp.Params();
        p.big = 3;
        p.start = 9 * 60 + 30;
        p.tp = 0;
        p.sl = 0;
        p.hold = 400;
        p.minValue = 0;
        p.maSell = 20;
        Scalp.Stock st = Scalp.parse("000001", "가", bigCandle(100));
        List<Scalp.Trade> t = run(st, p);
        assertEquals(1, t.size());
        assertEquals("09:45", Scalp.hm(t.get(0).buyAt));
        assertEquals(1050, t.get(0).buyPrice);
        assertEquals("이평 하락", t.get(0).reason);
        assertEquals("09:56", Scalp.hm(t.get(0).sellAt));
        assertEquals(1016, t.get(0).sellPrice);

        p.maSell = 0;
        p.maBelow = 60;  // 09:55 종가 1015 < 09:10부터 46봉 평균 약 1019 -> 이평 아래
        t = run(st, p);
        assertEquals("이평 아래", t.get(0).reason);
        assertEquals("09:56", Scalp.hm(t.get(0).sellAt));

        p.big = 5;  // 몸통 4.5%는 장대양봉 아님
        assertEquals(0, run(st, p).size());
    }

    /** vwap: 신호 봉 종가가 09:00부터의 VWAP 위일 때만, 토스(추정 거래대금) 종목은 안 봄 */
    @Test
    void vwapFilterBuysAboveVwapOnKbStocksOnly() {
        String signal = "[\"0925\",1000,1020,1000,1020,1000,1010000],[\"0926\",1020,1035,1015,1025,500,512000]]";
        StringBuilder low = new StringBuilder("[");
        StringBuilder high = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            low.append(String.format("[\"09%02d\",1000,1000,1000,1000,100,100000],", i));
            high.append(String.format("[\"09%02d\",1100,1100,1100,1100,100,110000],", i));
        }
        Scalp.Params p = new Scalp.Params();
        p.minValue = 0;
        p.vwap = true;
        assertEquals(1, run(Scalp.parse("000001", "가", "kb", low + signal), p).size());   // VWAP 약 1003 < 1020
        assertEquals(0, run(Scalp.parse("000001", "가", "kb", high + signal), p).size());  // VWAP 약 1074 > 1020
        assertEquals(0, run(Scalp.parse("000001", "가", "toss", low + signal), p).size()); // 토스는 VWAP를 못 냄
        p.vwap = false;
        assertEquals(1, run(Scalp.parse("000001", "가", "kb", high + signal), p).size());
    }

    /** -Dscalp.live=true: KB 실제 거래대금 종목(VWAP를 낼 수 있음)만으로 VWAP 조건 없음·있음 비교 + VWAP 조건 격자 탐색 */
    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveVwap() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : bars.days()) {
            List<Scalp.Stock> kb = new ArrayList<>();
            for (Scalp.Stock st : bars.stocks(d)) {
                if (st.realValue) {
                    kb.add(st);
                }
            }
            days.put(d, kb);
            System.out.println("[VWAP 종목] " + d + " " + kb.size());
        }
        // {wick15 0|1, 익절 %×10, 손절 %×10, 보유 분}
        for (int[] v : new int[][] {{0, 10, 10, 1}, {1, 10, 30, 400}, {1, 50, 30, 400}}) {
            for (boolean vw : new boolean[] {false, true}) {
                Scalp.Params p = new Scalp.Params();
                p.wick15 = v[0] == 1;
                if (p.wick15) {
                    p.start = 9 * 60 + 30;
                    p.minValue = 0;
                }
                p.tp = v[1] / 10.0;
                p.sl = v[2] / 10.0;
                p.hold = v[3];
                p.vwap = vw;
                List<Scalp.Trade> ts = Scalp.run(days, p);
                Map<String, Integer> why = new java.util.TreeMap<>();
                ts.forEach(t -> why.merge(t.reason, 1, Integer::sum));
                System.out.println("[" + (p.wick15 ? "15분봉 꼬리 없음" : "1분 거래량 급증") + " 익절 " + p.tp + " 손절 " + p.sl + " VWAP " + (vw ? "위만" : "무시")
                        + "] " + Scalp.summary(ts, null) + " 이유 " + why);
                for (String d : days.keySet()) {
                    System.out.println("  " + d + " " + Scalp.summary(ts, d));
                }
            }
        }
        Scalp.Params base = new Scalp.Params();
        base.vwap = true;
        Map<String, Object> g = Scalp.grid(days, base, 20, 15);
        System.out.println("[1분 단타 격자 · VWAP 위만] 조합 " + g.get("combos") + " 중 거래 20건↑ " + g.get("kept"));
        ((List<?>) g.get("top")).forEach(System.out::println);
    }

    /** -Dscalp.live=true: 09:30부터 15분봉 장대양봉 시장가 매수 -> 1분봉 종가가 60분 이평 아래면 시장가 매도 (익절·손절 없음, 장마감까지) */
    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveBigCandleMaSell() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : bars.days()) {
            days.put(d, bars.stocks(d));
        }
        // {장대양봉 몸통 %, 마지막 신호 15분봉이 끝나는 시각(분): 585 = 09:30~09:44 봉만, 915 = 15:15까지 전부, 15분봉 거래대금 백만원}
        for (int[] v : new int[][] {{2, 915, 0}, {3, 915, 0}, {5, 915, 0}, {3, 585, 0}, {5, 585, 0}, {3, 915, 1000}}) {
          for (int prevVol : new int[] {0, 100}) {
            Scalp.Params p = new Scalp.Params();
            p.prevVol = prevVol;
            p.big = v[0];
            p.start = 9 * 60 + 30;
            p.until = v[1];
            p.minValue = v[2];
            p.tp = 0;
            p.sl = 0;
            p.hold = 400;
            p.maBelow = 60;
            List<Scalp.Trade> ts = Scalp.run(days, p);
            Map<String, Integer> why = new java.util.TreeMap<>();
            long held = 0;
            for (Scalp.Trade t : ts) {
                why.merge(t.reason, 1, Integer::sum);
                held += t.sellAt - t.buyAt;
            }
            System.out.println("[장대양봉 " + v[0] + "% " + (v[1] == 585 ? "09:30 봉만" : "09:30~15:15") + " 거래대금≥" + v[2] + "백만"
                    + (prevVol > 0 ? " 전날 대비 거래량 +" + prevVol + "%↑" : "") + " · 60분 이평 아래면 매도] "
                    + Scalp.summary(ts, null) + " 이유 " + why + " 평균 보유 " + (ts.isEmpty() ? 0 : held / ts.size()) + "분");
            for (String d : days.keySet()) {
                System.out.println("  " + d + " " + Scalp.summary(ts, d));
            }
          }
        }
    }

    /** -Dscalp.live=true: 저장된 모든 날로 15분봉 꼬리 없음 + 익절·손절 몇 가지 (장마감까지 보유) */
    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveWick15() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : bars.days()) {
            days.put(d, bars.stocks(d));
        }
        // {첫 15분봉 시작, 15분봉 거래대금 백만원, 익절 %, 손절 %}
        for (int[] v : new int[][] {{570, 0, 5, 3}, {570, 0, 1, 3}, {570, 0, 1, 1}, {570, 100, 1, 3}, {570, 100, 1, 1}}) {
            Scalp.Params p = new Scalp.Params();
            p.wick15 = true;
            p.start = v[0];
            p.minValue = v[1];
            p.tp = v[2];
            p.sl = v[3];
            p.hold = 400;
            List<Scalp.Trade> ts = Scalp.run(days, p);
            Map<String, Integer> why = new java.util.TreeMap<>();
            ts.forEach(t -> why.merge(t.reason, 1, Integer::sum));
            System.out.println("[15분봉 꼬리 없음] 시작 " + Scalp.hm(v[0]) + " 거래대금≥" + v[1] + "백만 익절 " + v[2] + "% 손절 " + v[3] + "% 전체 " + Scalp.summary(ts, null) + " 이유 " + why);
            for (String d : days.keySet()) {
                System.out.println("  " + d + " " + Scalp.summary(ts, d));
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "scalp.live", matches = "true")
    void liveGridOnLastTwoDays() {
        Path dir = Path.of(System.getProperty("kb.data.dir", "..")).toAbsolutePath().normalize();
        MinuteBars bars = new MinuteBars(Sqlite.jdbc(dir.resolve("minutes.db")));
        List<String> stored = bars.days();
        Map<String, List<Scalp.Stock>> days = new LinkedHashMap<>();
        for (String d : stored.subList(Math.max(0, stored.size() - 2), stored.size())) {
            days.put(d, bars.stocks(d));
        }
        Map<String, Object> g = Scalp.grid(days, new Scalp.Params(), 20, 30);
        System.out.println("[단타 격자] " + g.get("days") + " 조합 " + g.get("combos") + " 중 거래 20건↑ " + g.get("kept"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = new ArrayList<>((List<Map<String, Object>>) g.get("top"));
        for (Map<String, Object> m : top) {
            System.out.println(m);
        }
    }
}
