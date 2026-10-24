package kb.app;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 모의투자: 실제 KB 시세(IVU10140 현재가)로 가상 계좌의 주문을 체결합니다. 실제 주문은 내지 않습니다. 계좌·주문·정산 기록은 데이터 폴더의 paper.db.
 *
 * 주문 (국내 주식, 원화, 한 번에 전량 체결):
 *   - 시장가: 접수하면 바로 현재가로 체결. 장 시간(평일 09:00~15:30)이 아니거나 시세가 없으면 거절
 *   - 지정가: 접수해 두고 장중 5초마다 현재가와 비교해, 매수는 현재가 ≤ 지정가, 매도는 현재가 ≥ 지정가면 현재가로 체결
 *   - 매수는 주문 가능 금액(현금 - 미체결 매수에 묶인 금액·수수료) 안에서만, 매도는 보유 - 미체결 매도 수량 안에서만 접수
 *   - 수수료 FEE(매수·매도), 매도 거래세 TAX. 보유 원가(cost)는 수수료 포함 매수 금액, 평균단가 = cost ÷ 수량
 * 정산 (평일 15:40 배치, 그 시각에 서버가 꺼져 있었으면 켤 때, 또는 POST {"action": "settle"}):
 *   - 남은 미체결 주문 취소 (주문은 그날만 유효)
 *   - 보유 종목을 그때 현재가(장 마감 후라 종가)로 평가해 하루 기록(paper_settlement, 같은 날 다시 하면 덮어씀):
 *     현금, 평가금액, 총자산, 그날 실현손익·수수료·세금·체결·취소 건수, 보유 종목
 * Trader(토스 실제 주문)와는 따로 동작합니다.
 */
@Service
class Paper {

    private static final Logger log = LoggerFactory.getLogger(Paper.class);
    static final String SETTLE_CRON = "0 40 15 * * MON-FRI";
    static final LocalTime OPEN = LocalTime.of(9, 0);
    static final LocalTime CLOSE = LocalTime.of(15, 30);
    static final LocalTime SETTLE_AT = LocalTime.of(15, 40);
    // ponytail: 수수료·세금 고정 (ETF는 실제로 거래세 없음). 종목·증권사별로 달라야 하면 설정으로
    static final double FEE = 0.00015;
    static final double TAX = 0.0020;
    static final long INITIAL_CASH = 10_000_000;
    static final long MAX_QTY = 1_000_000;
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS paper_account (id INTEGER PRIMARY KEY CHECK (id = 1),"
            + " cash INTEGER NOT NULL, initial INTEGER NOT NULL, opened TEXT NOT NULL);"
            + "CREATE TABLE IF NOT EXISTS paper_position (code TEXT PRIMARY KEY, qty INTEGER NOT NULL CHECK (qty > 0), cost INTEGER NOT NULL);"
            + "CREATE TABLE IF NOT EXISTS paper_order (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL, at TEXT NOT NULL, code TEXT NOT NULL,"
            + " side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')), type TEXT NOT NULL CHECK (type IN ('MARKET', 'LIMIT')),"
            + " qty INTEGER NOT NULL CHECK (qty > 0), limit_price INTEGER,"
            + " status TEXT NOT NULL CHECK (status IN ('OPEN', 'FILLED', 'CANCELED')),"
            + " filled_at TEXT, price INTEGER, fee INTEGER, tax INTEGER, pnl INTEGER, reason TEXT);"
            + "CREATE INDEX IF NOT EXISTS paper_order_status ON paper_order (status);"
            + "CREATE TABLE IF NOT EXISTS paper_settlement (day TEXT PRIMARY KEY, at TEXT NOT NULL, cash INTEGER NOT NULL, stock INTEGER NOT NULL,"
            + " equity INTEGER NOT NULL, realized INTEGER NOT NULL, fee INTEGER NOT NULL, tax INTEGER NOT NULL,"
            + " filled INTEGER NOT NULL, canceled INTEGER NOT NULL, positions TEXT NOT NULL)";

    /** 현재가 (운영은 KB IVU10140, 테스트는 가짜). 0 이하면 시세 없음 */
    interface Quote {
        long price(String code) throws IOException;
    }

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Quote quote;
    private final Supplier<ZonedDateTime> clock;

    Paper(JdbcTemplate db, Quote quote, Supplier<ZonedDateTime> clock) {
        this.db = db;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        this.quote = quote;
        this.clock = clock;
        for (String sql : SCHEMA.split(";")) {
            db.execute(sql);
        }
        db.update("INSERT OR IGNORE INTO paper_account (id, cash, initial, opened) VALUES (1, ?, ?, ?)",
                INITIAL_CASH, INITIAL_CASH, clock.get().format(AT));
    }

    @Autowired
    Paper(@Qualifier("paperDb") JdbcTemplate db, KBClient client, Supplier<ZonedDateTime> clock) {
        this(db, code -> Values.lng(client.call("IVU10140", Heatmap.body("excg_clsf", "0", "shrt_cd", code)).get("now_prc")), clock);
    }

    // ------------------------------------------------------------------ 주문

    /**
     * 주문 접수. side BUY|SELL, type MARKET|LIMIT, limit은 지정가(시장가면 무시). 시장가는 바로 체결합니다.
     * 접수할 수 없으면 IllegalArgumentException (화면에 400으로 보임). 돌려주는 값은 주문 한 행.
     */
    synchronized Map<String, Object> order(String code, String side, String type, long qty, long limit) throws IOException {
        if (code == null || !code.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드는 6자리 (예: 005930)");
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            throw new IllegalArgumentException("side는 BUY 또는 SELL");
        }
        if (!"MARKET".equals(type) && !"LIMIT".equals(type)) {
            throw new IllegalArgumentException("type은 MARKET 또는 LIMIT");
        }
        if (qty <= 0 || qty > MAX_QTY) {
            throw new IllegalArgumentException("수량은 1 ~ " + MAX_QTY);
        }
        if ("LIMIT".equals(type) && limit <= 0) {
            throw new IllegalArgumentException("지정가는 0보다 커야 함");
        }
        ZonedDateTime now = clock.get();
        boolean market = "MARKET".equals(type);
        long price = 0;
        if (market) {
            if (!open(now)) {
                throw new IllegalArgumentException("장 시간(평일 " + OPEN + "~" + CLOSE + ")에만 시장가 주문");
            }
            price = quote.price(code);  // 시세 조회는 DB 트랜잭션 밖에서
            if (price <= 0) {
                throw new IllegalArgumentException("시세 없음: " + code);
            }
        }
        long need = market ? price : limit;
        long fillPrice = price;
        Long id = tx.execute(s -> {
            if ("BUY".equals(side)) {
                long cost = qty * need + fee(qty * need);
                long available = cash() - reserved();
                if (cost > available) {
                    throw new IllegalArgumentException("주문 가능 금액 부족: 필요 " + cost + ", 가능 " + available);
                }
            } else {
                long sellable = held(code) - db.queryForObject("SELECT COALESCE(SUM(qty), 0) FROM paper_order"
                        + " WHERE status = 'OPEN' AND side = 'SELL' AND code = ?", Long.class, code);
                if (qty > sellable) {
                    throw new IllegalArgumentException("매도 가능 수량 부족: 가능 " + sellable);
                }
            }
            db.update("INSERT INTO paper_order (day, at, code, side, type, qty, limit_price, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN')",
                    now.toLocalDate().toString(), now.format(AT), code, side, type, qty, market ? null : limit);
            long newId = db.queryForObject("SELECT last_insert_rowid()", Long.class);
            if (market) {
                fill(newId, code, side, qty, fillPrice, now);
            }
            return newId;
        });
        return db.queryForMap("SELECT * FROM paper_order WHERE id = ?", id);
    }

    /** 미체결 주문 취소 */
    synchronized void cancel(long id, String reason) {
        if (db.update("UPDATE paper_order SET status = 'CANCELED', reason = ? WHERE id = ? AND status = 'OPEN'", reason, id) == 0) {
            throw new IllegalArgumentException("미체결 주문이 아님: " + id);
        }
    }

    // ------------------------------------------------------------------ 체결 (장중 5초마다)

    @Scheduled(cron = "*/5 * 9-15 * * MON-FRI", zone = "Asia/Seoul")
    void tick() {
        try {
            match();
        } catch (IOException | RuntimeException e) {
            log.warn("모의투자 체결 확인 실패: {}", e.toString());
        }
    }

    /**
     * 미체결 지정가를 현재가와 비교해 닿은 주문을 체결. 체결 건수를 돌려줍니다. 시세를 못 받은 종목은 다음 번에.
     * ponytail: 체결가는 현재가 하나, 호가·잔량·부분 체결은 보지 않음. 큰 수량이 비현실적으로 체결되면 호가(IVU10070)로
     */
    synchronized int match() throws IOException {
        ZonedDateTime now = clock.get();
        if (!open(now)) {
            return 0;
        }
        List<Map<String, Object>> orders = db.queryForList("SELECT id, code, side, qty, limit_price FROM paper_order WHERE status = 'OPEN' ORDER BY id");
        Map<String, Long> prices = prices(orders.stream().map(o -> (String) o.get("code")).distinct().toList());
        int filled = 0;
        for (Map<String, Object> o : orders) {
            Long p = prices.get((String) o.get("code"));
            long limit = ((Number) o.get("limit_price")).longValue();
            if (p == null || ("BUY".equals(o.get("side")) ? p > limit : p < limit)) {
                continue;
            }
            tx.executeWithoutResult(s -> fill(((Number) o.get("id")).longValue(), (String) o.get("code"), (String) o.get("side"),
                    ((Number) o.get("qty")).longValue(), p, now));
            filled++;
        }
        return filled;
    }

    /** 한 주문을 price로 전량 체결: 현금·보유 원가 반영 (트랜잭션 안에서 부름) */
    private void fill(long id, String code, String side, long qty, long price, ZonedDateTime now) {
        long amount = qty * price;
        long fee = fee(amount);
        long tax = 0;
        Long pnl = null;
        if ("BUY".equals(side)) {
            db.update("UPDATE paper_account SET cash = cash - ? WHERE id = 1", amount + fee);
            db.update("INSERT INTO paper_position (code, qty, cost) VALUES (?, ?, ?)"
                    + " ON CONFLICT (code) DO UPDATE SET qty = qty + excluded.qty, cost = cost + excluded.cost", code, qty, amount + fee);
        } else {
            tax = Math.round(amount * TAX);
            Map<String, Object> pos = db.queryForMap("SELECT qty, cost FROM paper_position WHERE code = ?", code);
            long held = ((Number) pos.get("qty")).longValue();
            long cost = ((Number) pos.get("cost")).longValue();
            long costOut = qty == held ? cost : Math.round((double) cost * qty / held);
            pnl = amount - fee - tax - costOut;
            db.update("UPDATE paper_account SET cash = cash + ? WHERE id = 1", amount - fee - tax);
            if (qty == held) {
                db.update("DELETE FROM paper_position WHERE code = ?", code);
            } else {
                db.update("UPDATE paper_position SET qty = qty - ?, cost = cost - ? WHERE code = ?", qty, costOut, code);
            }
        }
        db.update("UPDATE paper_order SET status = 'FILLED', filled_at = ?, price = ?, fee = ?, tax = ?, pnl = ? WHERE id = ?",
                now.format(AT), price, fee, tax, pnl, id);
    }

    // ------------------------------------------------------------------ 정산 (하루 끝 배치)

    @Scheduled(cron = SETTLE_CRON, zone = "Asia/Seoul")
    void settleJob() {
        try {
            settle();
        } catch (IOException | RuntimeException e) {
            log.warn("모의투자 정산 실패 (서버를 다시 켜거나 POST /api/paper settle로 다시): {}", e.toString());
        }
    }

    /** 서버 시작: 오늘이 평일이고 정산 시각이 지났는데 오늘 정산이 없으면 지금 정산 */
    @EventListener(ApplicationReadyEvent.class)
    void catchUp() {
        ZonedDateTime now = clock.get();
        if (weekday(now) && !now.toLocalTime().isBefore(SETTLE_AT)
                && db.queryForObject("SELECT COUNT(*) FROM paper_settlement WHERE day = ?", Long.class, now.toLocalDate().toString()) == 0) {
            settleJob();
        }
    }

    /**
     * 미체결 취소 + 보유 평가 + 하루 기록. 보유 종목 시세를 하나라도 못 받으면 기록하지 않고 IOException (틀린 총자산을 남기지 않게).
     * ponytail: 휴장일을 모름 (KB에 장 운영 정보 TR이 없음). 평일 휴장일엔 전날 종가로 같은 기록이 한 줄 더 생김, 필요하면 토스 /market-calendar로
     */
    synchronized Map<String, Object> settle() throws IOException {
        ZonedDateTime now = clock.get();
        String day = now.toLocalDate().toString();
        List<Map<String, Object>> positions = db.queryForList("SELECT code, qty, cost FROM paper_position ORDER BY code");
        Map<String, Long> prices = prices(positions.stream().map(p -> (String) p.get("code")).toList());
        List<String> missing = positions.stream().map(p -> (String) p.get("code")).filter(c -> !prices.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new IOException("시세를 못 받아 정산하지 않음: " + missing);
        }
        tx.executeWithoutResult(s -> {
            int canceled = db.update("UPDATE paper_order SET status = 'CANCELED', reason = '장 마감 정산' WHERE status = 'OPEN'");
            long stock = 0;
            StringBuilder held = new StringBuilder();
            for (Map<String, Object> p : positions) {
                long qty = ((Number) p.get("qty")).longValue();
                stock += qty * prices.get((String) p.get("code"));
                held.append(held.length() == 0 ? "" : ",").append(p.get("code")).append(':').append(qty);
            }
            Map<String, Object> d = db.queryForMap("SELECT COUNT(*) AS filled, COALESCE(SUM(pnl), 0) AS realized, COALESCE(SUM(fee), 0) AS fee,"
                    + " COALESCE(SUM(tax), 0) AS tax FROM paper_order WHERE status = 'FILLED' AND substr(filled_at, 1, 10) = ?", day);
            long cash = cash();
            db.update("INSERT OR REPLACE INTO paper_settlement (day, at, cash, stock, equity, realized, fee, tax, filled, canceled, positions)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", day, now.format(AT), cash, stock, cash + stock,
                    d.get("realized"), d.get("fee"), d.get("tax"), d.get("filled"), canceled, held.toString());
        });
        log.info("모의투자 정산 {}", day);
        return db.queryForMap("SELECT * FROM paper_settlement WHERE day = ?", day);
    }

    // ------------------------------------------------------------------ 화면·API

    /** 계좌: 현금, 주문 가능 금액, 보유(현재가 평가), 총자산, 최근 주문 100건, 최근 정산 30일 */
    synchronized Map<String, Object> status() throws IOException {
        List<Map<String, Object>> positions = db.queryForList("SELECT code, qty, cost FROM paper_position ORDER BY code");
        Map<String, Long> prices = prices(positions.stream().map(p -> (String) p.get("code")).toList());
        long cash = cash();
        long stock = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> p : positions) {
            long qty = ((Number) p.get("qty")).longValue();
            long cost = ((Number) p.get("cost")).longValue();
            Long price = prices.get((String) p.get("code"));
            Map<String, Object> r = new LinkedHashMap<>(p);
            r.put("avg", (double) cost / qty);
            r.put("price", price);  // 시세를 못 받으면 null, 평가는 원가로
            long value = price == null ? cost : qty * price;
            r.put("value", value);
            r.put("pnl", value - cost);
            stock += value;
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cash", cash);
        out.put("available", cash - reserved());
        out.put("initial", db.queryForObject("SELECT initial FROM paper_account WHERE id = 1", Long.class));
        out.put("stock", stock);
        out.put("equity", cash + stock);
        out.put("positions", rows);
        out.put("orders", db.queryForList("SELECT * FROM paper_order ORDER BY id DESC LIMIT 100"));
        out.put("settlements", db.queryForList("SELECT * FROM paper_settlement ORDER BY day DESC LIMIT 30"));
        return out;
    }

    /**
     * POST /api/paper
     *   {"action": "order", "code": "005930", "side": "BUY"|"SELL", "qty": 10, "type": "MARKET"|"LIMIT", "price": 70000}
     *   {"action": "cancel", "id": 3}
     *   {"action": "settle"}               정산을 지금 (배치와 같음)
     *   {"action": "reset", "cash": 10000000}  보유·주문·정산 기록을 지우고 새 계좌
     * -> status()
     */
    Map<String, Object> command(Map<String, Object> body) throws IOException {
        String action = Values.str(body.get("action"));
        switch (action) {
            case "order" -> order(Values.str(body.get("code")).trim().toUpperCase(), Values.str(body.get("side")).toUpperCase(),
                    body.get("type") == null ? "MARKET" : Values.str(body.get("type")).toUpperCase(),
                    Values.lng(body.get("qty")), Values.lng(body.get("price")));
            case "cancel" -> cancel(Values.lng(body.get("id")), "사용자 취소");
            case "settle" -> settle();
            case "reset" -> reset(body.get("cash") == null ? INITIAL_CASH : Values.lng(body.get("cash")));
            default -> throw new IllegalArgumentException("action은 order|cancel|settle|reset");
        }
        return status();
    }

    synchronized void reset(long cash) {
        if (cash <= 0) {
            throw new IllegalArgumentException("시작 현금은 0보다 커야 함");
        }
        tx.executeWithoutResult(s -> {
            db.update("DELETE FROM paper_position");
            db.update("DELETE FROM paper_order");
            db.update("DELETE FROM paper_settlement");
            db.update("UPDATE paper_account SET cash = ?, initial = ?, opened = ? WHERE id = 1", cash, cash, clock.get().format(AT));
        });
    }

    // ------------------------------------------------------------------ 도우미

    static boolean weekday(ZonedDateTime t) {
        return t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static boolean open(ZonedDateTime t) {
        LocalTime x = t.toLocalTime();
        return weekday(t) && !x.isBefore(OPEN) && x.isBefore(CLOSE);
    }

    static long fee(long amount) {
        return Math.round(amount * FEE);
    }

    private long cash() {
        return db.queryForObject("SELECT cash FROM paper_account WHERE id = 1", Long.class);
    }

    private long held(String code) {
        List<Long> q = db.queryForList("SELECT qty FROM paper_position WHERE code = ?", Long.class, code);
        return q.isEmpty() ? 0 : q.get(0);
    }

    /** 미체결 매수에 묶인 금액 (지정가 × 수량 + 수수료) */
    private long reserved() {
        long sum = 0;
        for (Map<String, Object> o : db.queryForList("SELECT qty, limit_price FROM paper_order WHERE status = 'OPEN' AND side = 'BUY'")) {
            long amount = ((Number) o.get("qty")).longValue() * ((Number) o.get("limit_price")).longValue();
            sum += amount + fee(amount);
        }
        return sum;
    }

    /** 종목별 현재가. 못 받은 종목은 빠짐 */
    private Map<String, Long> prices(List<String> codes) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String c : codes) {
            try {
                long p = quote.price(c);
                if (p > 0) {
                    out.put(c, p);
                }
            } catch (IOException | RuntimeException e) {
                log.debug("모의투자 시세 실패 {}: {}", c, e.toString());
            }
        }
        return out;
    }
}
