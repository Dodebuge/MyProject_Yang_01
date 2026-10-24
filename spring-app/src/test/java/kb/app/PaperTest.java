package kb.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 가짜 시세·가짜 시계로 모의투자 주문·체결·정산 점검. */
class PaperTest {

    private final Map<String, Long> price = new HashMap<>();
    private ZonedDateTime now = ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));  // 월요일 장중
    private Paper paper;

    @BeforeEach
    void setUp() throws IOException {
        paper = new Paper(Sqlite.jdbc(Files.createTempFile("paper", ".db")), c -> price.getOrDefault(c, 0L), () -> now);
    }

    private long num(String key) throws IOException {
        return ((Number) paper.status().get(key)).longValue();
    }

    @Test
    void marketBuyAndSellSettleCashWithFeeAndTax() throws IOException {
        price.put("005930", 70_000L);
        paper.order("005930", "BUY", "MARKET", 10, 0);
        long buyCost = 700_000 + Paper.fee(700_000);
        assertEquals(Paper.INITIAL_CASH - buyCost, num("cash"));

        price.put("005930", 77_000L);
        Map<String, Object> sell = paper.order("005930", "SELL", "MARKET", 10, 0);
        long net = 770_000 - Paper.fee(770_000) - Math.round(770_000 * Paper.TAX);
        assertEquals(net - buyCost, ((Number) sell.get("pnl")).longValue());
        assertEquals(Paper.INITIAL_CASH - buyCost + net, num("cash"));
        assertEquals(List.of(), paper.status().get("positions"));
    }

    @Test
    void rejectsOverspendOversellAndMarketOrderAfterClose() {
        price.put("005930", 70_000L);
        assertThrows(IllegalArgumentException.class, () -> paper.order("005930", "BUY", "MARKET", 1_000, 0));  // 7천만 원 > 1천만 원
        assertThrows(IllegalArgumentException.class, () -> paper.order("005930", "SELL", "MARKET", 1, 0));
        assertThrows(IllegalArgumentException.class, () -> paper.order("5930", "BUY", "MARKET", 1, 0));
        now = now.withHour(16);
        assertThrows(IllegalArgumentException.class, () -> paper.order("005930", "BUY", "MARKET", 1, 0));
    }

    @Test
    void limitOrderReservesCashAndFillsWhenPriceCrosses() throws IOException {
        price.put("005930", 70_000L);
        paper.order("005930", "BUY", "LIMIT", 100, 69_000);
        assertEquals(Paper.INITIAL_CASH - 6_900_000 - Paper.fee(6_900_000), num("available"));
        assertThrows(IllegalArgumentException.class, () -> paper.order("005930", "BUY", "LIMIT", 50, 69_000));  // 묶인 금액 때문에 부족

        assertEquals(0, paper.match());
        price.put("005930", 68_500L);
        assertEquals(1, paper.match());
        assertEquals(Paper.INITIAL_CASH - 6_850_000 - Paper.fee(6_850_000), num("cash"));  // 지정가보다 낮은 현재가로 체결
        assertEquals(0, paper.match());
    }

    @Test
    void settlementCancelsOpenOrdersAndRecordsEquity() throws IOException {
        price.put("005930", 70_000L);
        paper.order("005930", "BUY", "MARKET", 10, 0);
        paper.order("005930", "SELL", "LIMIT", 5, 90_000);
        now = now.withHour(15).withMinute(40);
        price.put("005930", 72_000L);

        Map<String, Object> s = paper.settle();
        assertEquals(1L, ((Number) s.get("canceled")).longValue());
        assertEquals(720_000L, ((Number) s.get("stock")).longValue());
        assertEquals(num("cash") + 720_000, ((Number) s.get("equity")).longValue());
        assertEquals("005930:10", s.get("positions"));

        price.remove("005930");  // 시세가 없으면 틀린 기록을 남기지 않음
        assertThrows(IOException.class, () -> paper.settle());
    }
}
