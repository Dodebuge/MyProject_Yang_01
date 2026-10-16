package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 토스 자동매매 (실제 주문). 화면은 trader.html (/trader), 설정·기록은 데이터 폴더의 trader.db.
 *
 * 매수: 켜져 있고 장중(정규장 시작 ~ 마감 10분 전)이면
 *   - 내가 고른 종목: 매일 한 번 시장가 매수
 *   - 시그널 알림 종목(시장별로 켤 수 있음): 코스닥 → 국내, 러셀 2000 → 미국. 알림이 뜬 날 한 번 시장가 매수
 *   종목당 금액 = 하루 최대 매수금액 ÷ 최대 종목 수, 수량은 현재가로 나눈 정수(1주가 더 비싸면 건너뜀). 하루 매수 합계는 최대 매수금액을 넘지 않음.
 * 매도 (이 자동매매가 산 종목만, 다른 보유 종목은 건드리지 않음):
 *   - 평균 체결가 대비 +익절% 이상 → 시장가 매도, -손절% 이하 → 시장가 매도
 *   - 정규장 마감 10분 전: 미체결 매수 취소, 남은 종목 전량 시장가 매도, 그날 새 매수 중단
 *   - 꺼도 이미 산 종목의 익절·손절·장마감 매도는 계속 (켜고 끄는 건 새 매수만)
 *   - 서버가 꺼져 있어 전날 못 판 종목은 다음 장 시작 후 바로 매도
 * 안전장치:
 *   - 같은 종목은 시장별 하루 한 번만 매수 (trader.db UNIQUE)
 *   - 주문마다 clientOrderId(멱등성 키): 응답을 못 받아 다시 보내도 두 번 체결되지 않음 (10분 안)
 *   - trader.db 임대(lease): 같은 데이터 폴더를 쓰는 서버가 여러 개여도 한 프로세스만 주문
 *   - 토스 API 키(TOSS_OPENAPI_CLIENT_ID/SECRET)가 없으면 아무것도 하지 않음
 * 5초마다 확인합니다 (시세 1번 + 체결 확인). 토스 장 운영 정보(/market-calendar)로 휴장·장 시간을 알아냅니다.
 */
final class Trader {

    /** 토스 API (TossClient). 시험은 가짜로 바꿔 끼웁니다. */
    interface Api {
        Object get(String path, Map<String, String> params, boolean account) throws IOException;

        Object post(String path, Map<String, Object> body, boolean account) throws IOException;
    }

    static final List<String> MARKETS = Arrays.asList("KR", "US");
    static final int TICK_SECONDS = 5;
    static final int CUTOFF_MINUTES = 10;            // 정규장 마감 몇 분 전에 전량 매도·매수 중단
    static final int ACCOUNT_SECONDS = 30;           // 화면의 토스 계좌 금액 갱신 간격
    private static final long LEASE_MS = 30_000;      // 주문하는 프로세스 임대 시간 (5초마다 연장)
    private static final long RESEND_LIMIT_MS = 9 * 60_000;  // clientOrderId 멱등성은 10분: 그 안에서만 같은 키로 다시 보냄
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Gson GSON = new GsonBuilder().serializeNulls().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();
    private static final String SCHEMA = "CREATE TABLE IF NOT EXISTS trader_config (k TEXT PRIMARY KEY, v TEXT NOT NULL);"
            + "CREATE TABLE IF NOT EXISTS trader_pick (market TEXT NOT NULL, symbol TEXT NOT NULL, name TEXT NOT NULL, added TEXT NOT NULL,"
            + " PRIMARY KEY (market, symbol));"
            + "CREATE TABLE IF NOT EXISTS trader_signal (market TEXT NOT NULL, day TEXT NOT NULL, symbol TEXT NOT NULL, name TEXT NOT NULL,"
            + " at TEXT NOT NULL, PRIMARY KEY (market, day, symbol));"
            + "CREATE TABLE IF NOT EXISTS trader_position (id INTEGER PRIMARY KEY AUTOINCREMENT, market TEXT NOT NULL, day TEXT NOT NULL,"
            + " symbol TEXT NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL,"  // BUYING OPEN SELLING CLOSED FAILED
            + " order_qty REAL, est REAL, qty REAL, avg REAL, amount REAL,"
            + " buy_client TEXT, buy_order TEXT, buy_sent INTEGER, sell_client TEXT, sell_order TEXT, sell_sent INTEGER,"
            + " sell_qty REAL, sell_avg REAL, reason TEXT, note TEXT, opened TEXT, closed TEXT, bought TEXT, UNIQUE (market, day, symbol));"
            + "CREATE TABLE IF NOT EXISTS trader_log (id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, market TEXT NOT NULL, text TEXT NOT NULL);"
            + "CREATE TABLE IF NOT EXISTS trader_lease (name TEXT PRIMARY KEY, owner TEXT NOT NULL, until INTEGER NOT NULL)";

    private final Api api;
    private final Path db;
    private final Supplier<ZonedDateTime> clock;
    private final String owner = UUID.randomUUID().toString();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();   // "KR:005930" -> 현재가 (화면 손익 표시용)
    private final Map<String, String> phase = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    private volatile boolean leader;
    private volatile boolean migrated;  // 예전 trader.db에 새 칸 추가 (프로세스당 한 번)
    private volatile Map<String, Object> account;  // 토스 계좌 원화·달러 (accountSummary, ACCOUNT_SECONDS마다)
    private volatile long accountAt;
    private ScheduledExecutorService timer;

    /** 그날 정규장 (현지 날짜, 시작, 마감). 휴장이면 open이 null. */
    static final class Session {
        final String day;
        final ZonedDateTime open;
        final ZonedDateTime close;
        final long fetchedAt;

        Session(String day, ZonedDateTime open, ZonedDateTime close, long fetchedAt) {
            this.day = day;
            this.open = open;
            this.close = close;
            this.fetchedAt = fetchedAt;
        }
    }

    /** api가 null이면(토스 키 없음) 화면에 안내만 하고 아무것도 하지 않습니다. */
    Trader(Api api, Path db, Supplier<ZonedDateTime> clock) {
        this.api = api;
        this.db = db;
        this.clock = clock;
    }

    Trader(Api api, Path db) {
        this(api, db, () -> ZonedDateTime.now(SEOUL));
    }

    // ------------------------------------------------------------------ 시작·중지

    synchronized void start() {
        if (api == null) {
            System.out.println("[자동매매] 토스 API 키가 없어 꺼져 있습니다 (.env의 TOSS_OPENAPI_CLIENT_ID/SECRET)");
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "trader");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (RuntimeException e) {  // 한 번 실패해도 다음 확인은 계속
                log("ALL", "오류: " + e);
            }
        }, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        try {
            Map<String, Object> c = config();
            System.out.println("[자동매매] " + (Boolean.TRUE.equals(c.get("enabled")) ? "켜짐 (실제 주문)" : "꺼짐") + " · trader.db");
        } catch (SQLException e) {
            System.out.println("[자동매매] trader.db 오류: " + e.getMessage());
        }
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 설정

    static Map<String, Object> defaults() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", false);
        c.put("takeProfit", 3.0);   // %
        c.put("stopLoss", 2.0);     // %
        c.put("KR", market(1_000_000, 5, false));  // 원
        c.put("US", market(1_000, 5, false));      // 달러
        return c;
    }

    private static Map<String, Object> market(double budget, int maxStocks, boolean auto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budget", budget);       // 하루 최대 매수금액
        m.put("maxStocks", maxStocks); // 하루 최대 종목 수 (종목당 금액 = budget ÷ maxStocks)
        m.put("auto", auto);           // 시그널 알림 종목 자동 매수
        return m;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> config() throws SQLException {
        Map<String, Object> c = defaults();
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement("SELECT v FROM trader_config WHERE k = 'config'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> saved = GSON.fromJson(rs.getString(1), MAP);
                    for (String k : saved.keySet()) {
                        if (c.get(k) instanceof Map && saved.get(k) instanceof Map) {
                            ((Map<String, Object>) c.get(k)).putAll((Map<String, Object>) saved.get(k));
                        } else if (c.containsKey(k)) {
                            c.put(k, saved.get(k));
                        }
                    }
                }
            }
        }
        return c;
    }

    /** 화면에서 받은 설정을 검사해 저장. 잘못된 값은 IllegalArgumentException (화면에 그대로 보임). */
    @SuppressWarnings("unchecked")
    void saveConfig(Map<String, Object> in) throws SQLException {
        Map<String, Object> c = config();
        if (in.containsKey("enabled")) {
            c.put("enabled", Boolean.TRUE.equals(in.get("enabled")));
        }
        for (String k : new String[]{"takeProfit", "stopLoss"}) {
            if (in.containsKey(k)) {
                double v = number(in.get(k), k);
                if (v < 0.1 || v > 100) {
                    throw new IllegalArgumentException((k.equals("takeProfit") ? "익절" : "손절") + " %는 0.1~100 사이여야 합니다: " + v);
                }
                c.put(k, v);
            }
        }
        for (String m : MARKETS) {
            if (!(in.get(m) instanceof Map)) {
                continue;
            }
            Map<String, Object> src = (Map<String, Object>) in.get(m);
            Map<String, Object> dst = (Map<String, Object>) c.get(m);
            if (src.containsKey("budget")) {
                double v = number(src.get("budget"), "최대 매수금액");
                if (v < 0) {
                    throw new IllegalArgumentException("최대 매수금액은 0 이상이어야 합니다.");
                }
                dst.put("budget", v);
            }
            if (src.containsKey("maxStocks")) {
                double v = number(src.get("maxStocks"), "최대 종목 수");
                if (v < 1 || v > 50 || v != Math.rint(v)) {
                    throw new IllegalArgumentException("최대 종목 수는 1~50 사이 정수여야 합니다.");
                }
                dst.put("maxStocks", (int) v);
            }
            if (src.containsKey("auto")) {
                dst.put("auto", Boolean.TRUE.equals(src.get("auto")));
            }
        }
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO trader_config (k, v) VALUES ('config', ?)")) {
            ps.setString(1, GSON.toJson(c));
            ps.executeUpdate();
        }
        log("ALL", "설정 저장: " + (Boolean.TRUE.equals(c.get("enabled")) ? "켜짐" : "꺼짐") + " · 익절 " + c.get("takeProfit")
                + "% · 손절 " + c.get("stopLoss") + "% · 국내 " + c.get("KR") + " · 미국 " + c.get("US"));
    }

    private static double number(Object v, String name) {
        Double d = Values.numOrNull(v);
        if (d == null || "".equals(Values.str(v).trim()) || d.isNaN() || d.isInfinite()) {
            throw new IllegalArgumentException(name + " 값이 숫자가 아닙니다: " + v);
        }
        return d;
    }

    // ------------------------------------------------------------------ 화면 명령

    /** POST /api/trader {"action": "config"|"pick"|"unpick"|"sellAll", ...} -> status() */
    @SuppressWarnings("unchecked")
    Map<String, Object> command(Map<String, Object> body) throws IOException {
        if (api == null) {
            throw new IllegalArgumentException("토스 API 키가 없습니다. .env에 TOSS_OPENAPI_CLIENT_ID·TOSS_OPENAPI_CLIENT_SECRET을 넣고 서버를 다시 시작하세요.");
        }
        String action = Values.str(body.get("action"));
        String m = Values.str(body.get("market"));
        try {
            switch (action) {
                case "config":
                    saveConfig(body.get("config") instanceof Map ? (Map<String, Object>) body.get("config") : new HashMap<>());
                    break;
                case "pick":
                    market(m);
                    String symbol = symbol(m, Values.str(body.get("symbol")));
                    String name = Values.str(body.get("name")).trim();
                    try (Connection con = connect();
                         PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO trader_pick (market, symbol, name, added) VALUES (?, ?, ?, ?)")) {
                        ps.setString(1, m);
                        ps.setString(2, symbol);
                        ps.setString(3, name.isEmpty() ? symbol : name);
                        ps.setString(4, now().format(AT));
                        ps.executeUpdate();
                    }
                    log(m, "매수 대상 추가: " + symbol + " " + name);
                    break;
                case "unpick":
                    market(m);
                    try (Connection con = connect();
                         PreparedStatement ps = con.prepareStatement("DELETE FROM trader_pick WHERE market = ? AND symbol = ?")) {
                        ps.setString(1, m);
                        ps.setString(2, Values.str(body.get("symbol")).trim().toUpperCase());
                        ps.executeUpdate();
                    }
                    log(m, "매수 대상 삭제: " + body.get("symbol"));
                    break;
                case "sellAll":
                    market(m);
                    setFlag("halt:" + m + ":" + todayKey(m), "1");  // 다음 확인(5초 안)에 처리하고 그날 새 매수 중단
                    log(m, "전량 매도 요청 (오늘 새 매수 중단)");
                    break;
                default:
                    throw new IllegalArgumentException("모르는 명령입니다: " + action);
            }
        } catch (SQLException e) {
            throw new IOException("trader.db 오류: " + e.getMessage(), e);
        }
        return status();
    }

    private static void market(String m) {
        if (!MARKETS.contains(m)) {
            throw new IllegalArgumentException("시장은 KR 또는 US입니다: " + m);
        }
    }

    private static String symbol(String m, String raw) {
        String s = raw.trim().toUpperCase();
        if ("KR".equals(m) ? !s.matches("[0-9A-Z]{6}") : !s.matches("[A-Z][A-Z0-9.\\-]{0,9}")) {
            throw new IllegalArgumentException("KR".equals(m) ? "국내 종목번호는 6자리입니다: " + raw : "미국 티커가 올바르지 않습니다: " + raw);
        }
        return s;
    }

    /** 시그널 알림(Alerts)이 새 상승 시그널을 기록할 때. market: KR(코스닥) | US(러셀 2000). 그날 한 번만 대기열에 넣음 */
    void onSignal(String m, String symbol, String name) {
        if (api == null || !MARKETS.contains(m)) {
            return;
        }
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO trader_signal (market, day, symbol, name, at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, m);
            ps.setString(2, todayKey(m));
            ps.setString(3, symbol.trim().toUpperCase());
            ps.setString(4, name);
            ps.setString(5, now().format(AT));
            ps.executeUpdate();
        } catch (SQLException | RuntimeException e) {
            System.out.println("[자동매매] 시그널 기록 실패: " + e);
        }
    }

    // ------------------------------------------------------------------ 5초마다

    /** 한 번 확인: 주문을 맡은 프로세스(임대)만 시장마다 처리합니다. */
    void tick() {
        if (api == null) {
            return;
        }
        if (System.currentTimeMillis() - accountAt >= ACCOUNT_SECONDS * 1000L) {
            refreshAccount();  // 주문을 맡지 않은 서버도 화면에 보여 줄 수 있게 임대와 상관없이
        }
        try {
            leader = lease();
        } catch (SQLException e) {
            leader = false;
            log("ALL", "trader.db 오류: " + e.getMessage());
            return;
        }
        if (!leader) {
            return;
        }
        Map<String, Object> cfg;
        try {
            cfg = config();
        } catch (SQLException e) {
            log("ALL", "trader.db 오류: " + e.getMessage());
            return;
        }
        for (String m : MARKETS) {
            try {
                tickMarket(m, cfg);
                lastError.remove(m);
            } catch (IOException | SQLException | RuntimeException e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (!msg.equals(lastError.put(m, msg))) {  // 같은 오류를 5초마다 남기지 않게
                    log(m, "오류: " + msg);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void tickMarket(String m, Map<String, Object> cfg) throws IOException, SQLException {
        refreshPending(m);
        Session s = session(m);
        ZonedDateTime now = now();
        if (s.open == null) {
            phase.put(m, "휴장");
            return;
        }
        ZonedDateTime cutoff = s.close.minusMinutes(CUTOFF_MINUTES);
        if (now.isBefore(s.open)) {
            phase.put(m, "장 시작 전");
            return;
        }
        if (!now.isBefore(s.close)) {
            phase.put(m, "장 마감");
            return;
        }
        List<Map<String, Object>> open = positions(m, "status = 'OPEN'");
        if (!now.isBefore(cutoff)) {  // 마감 10분 전: 미체결 매수 취소, 전량 매도, 새 매수 없음
            phase.put(m, "마감 정리");
            for (Map<String, Object> p : positions(m, "status = 'BUYING' AND buy_order IS NOT NULL")) {
                cancel(m, p);
            }
            for (Map<String, Object> p : open) {
                sell(m, p, "장마감");
            }
            return;
        }
        boolean halted = getFlag("halt:" + m + ":" + s.day) != null;
        phase.put(m, halted ? "매수 중단 (전량 매도 요청)" : Boolean.TRUE.equals(cfg.get("enabled")) ? "매매 중" : "꺼짐 (보유분 익절·손절만)");
        if (halted) {
            for (Map<String, Object> p : positions(m, "status = 'BUYING' AND buy_order IS NOT NULL")) {
                cancel(m, p);
            }
            for (Map<String, Object> p : open) {
                sell(m, p, "수동 전량 매도");
            }
            return;
        }
        // 익절·손절 (전날 못 판 종목은 바로 매도)
        double tp = Values.num(cfg.get("takeProfit")) / 100;
        double sl = Values.num(cfg.get("stopLoss")) / 100;
        Map<String, Double> prices = prices(m, open.stream().map(p -> Values.str(p.get("symbol"))).toArray(String[]::new));
        for (Map<String, Object> p : open) {
            if (!s.day.equals(p.get("day"))) {
                sell(m, p, "전날 미청산");
                continue;
            }
            Double price = prices.get(Values.str(p.get("symbol")));
            double avg = Values.num(p.get("avg"));
            if (price == null || avg <= 0) {
                continue;
            }
            double r = price / avg - 1;
            if (r >= tp) {
                sell(m, p, "익절 " + pct(r));
            } else if (r <= -sl) {
                sell(m, p, "손절 " + pct(r));
            }
        }
        if (!Boolean.TRUE.equals(cfg.get("enabled"))) {
            return;
        }
        // 새 매수: 내가 고른 종목, 그다음 시그널 알림 종목 (알림 순서)
        Map<String, Object> mc = (Map<String, Object>) cfg.get(m);
        double budget = Values.num(mc.get("budget"));
        int maxStocks = (int) Values.num(mc.get("maxStocks"));
        List<String[]> candidates = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT symbol, name FROM trader_pick WHERE market = ? ORDER BY added, rowid", m)) {
            candidates.add(new String[]{Values.str(r.get("symbol")), Values.str(r.get("name")), "직접 고름"});
        }
        if (Boolean.TRUE.equals(mc.get("auto"))) {
            for (Map<String, Object> r : rows("SELECT symbol, name FROM trader_signal WHERE market = ? AND day = ? ORDER BY at, rowid", m, s.day)) {
                candidates.add(new String[]{Values.str(r.get("symbol")), Values.str(r.get("name")), "시그널"});
            }
        }
        for (String[] c : candidates) {
            if (!rows("SELECT id FROM trader_position WHERE market = ? AND day = ? AND symbol = ?", m, s.day, c[0]).isEmpty()) {
                continue;  // 오늘 이미 샀거나 시도함
            }
            List<Map<String, Object>> today = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED'", m, s.day);
            double spent = today.stream().mapToDouble(r -> Values.num(r.get("est"))).sum();
            if (today.size() >= maxStocks || spent >= budget) {
                break;
            }
            double amount = Math.min(budget / maxStocks, budget - spent);
            Double price = prices(m, c[0]).get(c[0]);
            if (price == null || price <= 0) {
                continue;  // 현재가를 못 받음: 다음 확인에 다시
            }
            long qty = (long) Math.floor(amount / price);
            if (qty < 1) {
                failed(m, s.day, c, "1주 가격(" + price + ")이 종목당 금액(" + round(amount) + ")보다 큼");
                continue;
            }
            buy(m, s.day, c, qty, qty * price);
        }
    }

    // ------------------------------------------------------------------ 주문

    private void buy(String m, String day, String[] c, long qty, double est) throws SQLException, IOException {
        String client = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT INTO trader_position (market, day, symbol, name, source, status, order_qty, est, qty,"
                     + " buy_client, buy_sent, opened) VALUES (?, ?, ?, ?, ?, 'BUYING', ?, ?, 0, ?, ?, ?)")) {
            ps.setString(1, m);
            ps.setString(2, day);
            ps.setString(3, c[0]);
            ps.setString(4, c[1]);
            ps.setString(5, c[2]);
            ps.setDouble(6, qty);
            ps.setDouble(7, est);
            ps.setString(8, client);
            ps.setLong(9, System.currentTimeMillis());
            ps.setString(10, now().format(AT));
            ps.executeUpdate();  // 주문 전에 기록: 응답을 못 받아도 같은 키로 다시 보내 확인
        }
        log(m, "매수 주문: " + c[1] + "(" + c[0] + ") " + qty + "주 · 약 " + round(est) + " · " + c[2]);
        Map<String, Object> p = rows("SELECT * FROM trader_position WHERE buy_client = ?", client).get(0);
        sendBuy(m, p, false);
    }

    private void sendBuy(String m, Map<String, Object> p, boolean resend) throws SQLException {
        try {
            Map<String, Object> r = order(m, Values.str(p.get("buy_client")), Values.str(p.get("symbol")), "BUY", Values.num(p.get("order_qty")), resend);
            update(p, "buy_order", Values.str(r.get("orderId")));
        } catch (TossClient.TossApiException e) {
            if (e.status == 409 || e.status >= 500) {
                return;  // 처리 중·서버 오류: 다음 확인에 같은 키로 다시
            }
            update(p, "status", "FAILED");
            update(p, "note", e.getMessage());
            log(m, "매수 실패: " + p.get("name") + "(" + p.get("symbol") + ") " + e.getMessage());
        } catch (IOException e) {
            // 응답을 못 받음: 다음 확인에 같은 clientOrderId로 다시 보내 결과를 받음 (두 번 체결 안 됨)
        }
    }

    private void sell(String m, Map<String, Object> p, String reason) throws SQLException, IOException {
        double qty = Values.num(p.get("qty"));
        Map<String, Object> sq = map(api.get("/api/v1/sellable-quantity", param("symbol", Values.str(p.get("symbol"))), true));
        double sellable = Values.num(sq.get("sellableQuantity"));
        if (sellable <= 0) {
            update(p, "status", "CLOSED");
            update(p, "reason", reason);
            update(p, "note", "판매 가능 수량 0 (직접 판 것으로 봄)");
            update(p, "closed", now().format(AT));
            log(m, p.get("name") + "(" + p.get("symbol") + ") 판매 가능 수량 0 → 정리만 함");
            return;
        }
        String client = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        update(p, "status", "SELLING");
        update(p, "sell_client", client);
        update(p, "sell_qty", Math.min(qty, sellable));
        update(p, "sell_sent", System.currentTimeMillis());
        update(p, "reason", reason);
        log(m, "매도 주문: " + p.get("name") + "(" + p.get("symbol") + ") " + fmtQty(Math.min(qty, sellable)) + "주 · " + reason);
        sendSell(m, rows("SELECT * FROM trader_position WHERE id = ?", p.get("id")).get(0), false);
    }

    private void sendSell(String m, Map<String, Object> p, boolean resend) throws SQLException {
        try {
            Map<String, Object> r = order(m, Values.str(p.get("sell_client")), Values.str(p.get("symbol")), "SELL", Values.num(p.get("sell_qty")), resend);
            update(p, "sell_order", Values.str(r.get("orderId")));
        } catch (TossClient.TossApiException e) {
            if (e.status == 409 || e.status >= 500) {
                return;  // 다음 확인에 같은 키로 다시
            }
            update(p, "status", "OPEN");  // 매도 실패: 보유로 되돌려 다음 확인에 다시 판단
            update(p, "sell_client", null);
            update(p, "note", "매도 실패: " + e.getMessage());
            log(m, "매도 실패: " + p.get("name") + "(" + p.get("symbol") + ") " + e.getMessage());
        } catch (IOException e) {
            // 응답을 못 받음: 다음 확인에 같은 키로 다시
        }
    }

    /** 시장가 주문. 보내기 전·후를 터미널과 trader.log에 남깁니다 (resend: 응답을 못 받았던 주문을 같은 키로 다시 보냄). */
    private Map<String, Object> order(String m, String client, String symbol, String side, double qty, boolean resend) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientOrderId", client);
        body.put("symbol", symbol);
        body.put("side", side);
        body.put("orderType", "MARKET");
        body.put("quantity", fmtQty(qty));
        String what = ("BUY".equals(side) ? "매수" : "매도") + " " + symbol + " " + fmtQty(qty) + "주 시장가 (키 " + client + ")";
        trace(m, (resend ? "주문 다시 보냄 (응답을 못 받았던 주문, 같은 키라 두 번 체결 안 됨): " : "주문 요청: ") + what);
        try {
            Map<String, Object> r = map(api.post("/api/v1/orders", body, true));
            trace(m, "주문 접수: " + what + " -> 주문번호 " + r.get("orderId"));
            return r;
        } catch (TossClient.TossApiException e) {
            trace(m, "주문 거절: " + what + " -> " + e.getMessage()
                    + (e.status == 409 || e.status >= 500 ? " (잠시 뒤 같은 키로 다시)" : ""));
            throw e;
        } catch (IOException e) {
            trace(m, "주문 응답 없음: " + what + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage() + " (다음 확인에 같은 키로 다시)");
            throw e;
        }
    }

    private void cancel(String m, Map<String, Object> p) throws SQLException {
        trace(m, "취소 요청: 매수 " + p.get("symbol") + " 주문번호 " + p.get("buy_order"));
        try {
            api.post("/api/v1/orders/" + p.get("buy_order") + "/cancel", new LinkedHashMap<>(), true);
            log(m, "미체결 매수 취소: " + p.get("name") + "(" + p.get("symbol") + ")");
        } catch (IOException e) {
            trace(m, "취소 결과: " + e.getMessage());
            if (!(e instanceof TossClient.TossApiException) || ((TossClient.TossApiException) e).status >= 500) {
                return;  // 다음 확인에 다시
            }
            // 이미 체결·취소된 주문 등: 체결 확인(refreshPending)이 정리
        }
        refreshPending(m);
    }

    /** 매수·매도 주문의 체결을 확인하고, 응답을 못 받은 주문은 같은 키로 다시 보냅니다. */
    private void refreshPending(String m) throws SQLException {
        for (Map<String, Object> p : positions(m, "status IN ('BUYING', 'SELLING')")) {
            boolean buying = "BUYING".equals(p.get("status"));
            String orderId = Values.str(p.get(buying ? "buy_order" : "sell_order"));
            if (orderId.isEmpty()) {
                if (System.currentTimeMillis() - Values.lng(p.get(buying ? "buy_sent" : "sell_sent")) > RESEND_LIMIT_MS) {
                    update(p, "note", "주문 결과를 10분 동안 확인하지 못함: 토스 앱에서 확인하세요");
                    update(p, "status", buying ? "FAILED" : "OPEN");
                    log(m, "주문 확인 실패: " + p.get("name") + "(" + p.get("symbol") + ") 토스 앱에서 확인하세요");
                } else if (buying) {
                    sendBuy(m, p, true);
                } else {
                    sendSell(m, p, true);
                }
                continue;
            }
            Map<String, Object> o;
            try {
                o = map(api.get("/api/v1/orders/" + orderId, null, true));
            } catch (IOException e) {
                continue;  // 다음 확인에 다시
            }
            String st = Values.str(o.get("status"));
            Map<String, Object> ex = o.get("execution") instanceof Map ? map(o.get("execution")) : new HashMap<>();
            double filled = Values.num(ex.get("filledQuantity"));
            double avg = Values.num(ex.get("averageFilledPrice"));
            double amount = Values.num(ex.get("filledAmount"));
            boolean done = Arrays.asList("FILLED", "CANCELED", "REJECTED", "REPLACED").contains(st);
            if (buying) {
                update(p, "qty", filled);
                if (filled > 0) {
                    update(p, "avg", avg);
                    update(p, "amount", amount);
                }
                if (done) {
                    if (filled > 0) {
                        update(p, "est", amount > 0 ? amount : filled * avg);
                        update(p, "bought", filledAt(ex.get("filledAt")));  // 매수 내역: 체결 시각
                        update(p, "status", "OPEN");
                        log(m, "매수 체결: " + p.get("name") + "(" + p.get("symbol") + ") " + fmtQty(filled) + "주 · 평균 " + avg);
                    } else {
                        update(p, "status", "FAILED");
                        update(p, "note", "매수 " + st);
                        log(m, "매수 " + st + ": " + p.get("name") + "(" + p.get("symbol") + ")");
                    }
                }
            } else if (done) {
                double left = Values.num(p.get("qty")) - filled;
                if (filled > 0) {
                    update(p, "sell_avg", avg);
                }
                if (left > 1e-9 && !"FILLED".equals(st)) {  // 일부만 팔림: 남은 수량은 보유로 되돌려 다시 판단
                    update(p, "qty", left);
                    update(p, "status", "OPEN");
                    update(p, "sell_order", null);
                    update(p, "sell_client", null);
                    log(m, "매도 " + st + ": " + p.get("name") + "(" + p.get("symbol") + ") 남은 " + fmtQty(left) + "주 다시 판단");
                } else {
                    update(p, "status", "CLOSED");
                    update(p, "closed", now().format(AT));
                    double buyAvg = Values.num(p.get("avg"));
                    log(m, "매도 체결: " + p.get("name") + "(" + p.get("symbol") + ") 평균 " + avg
                            + (buyAvg > 0 ? " · 손익 " + pct(avg / buyAvg - 1) : "") + " · " + p.get("reason"));
                }
            }
        }
    }

    /** 시세 (최대 200종목 한 번에). 화면 손익 표시용으로 기억해 둡니다. */
    @SuppressWarnings("unchecked")
    private Map<String, Double> prices(String m, String... symbols) throws IOException {
        Map<String, Double> out = new HashMap<>();
        if (symbols.length == 0) {
            return out;
        }
        Object r = api.get("/api/v1/prices", param("symbols", String.join(",", symbols)), false);
        if (r instanceof List) {
            for (Object o : (List<Object>) r) {
                Map<String, Object> q = map(o);
                Double v = Values.numOrNull(q.get("lastPrice"));
                if (v != null && v > 0) {
                    out.put(Values.str(q.get("symbol")), v);
                    lastPrice.put(m + ":" + q.get("symbol"), v);
                }
            }
        }
        return out;
    }

    /** 그날 정규장 (/market-calendar, 10분마다 다시 받음). 국내는 KRX·NXT 통합 정규장, 미국은 정규장. */
    @SuppressWarnings("unchecked")
    Session session(String m) throws IOException {
        Session s = sessions.get(m);
        if (s != null && System.currentTimeMillis() - s.fetchedAt < 600_000) {
            return s;
        }
        Map<String, Object> cal = map(api.get("/api/v1/market-calendar/" + m, null, false));
        Map<String, Object> today = map(cal.get("today"));
        Object regular = "KR".equals(m) ? (today.get("integrated") instanceof Map ? map(today.get("integrated")).get("regularMarket") : null)
                : today.get("regularMarket");
        String day = Values.str(today.get("date"));
        s = regular instanceof Map
                ? new Session(day, zoned(map(regular).get("startTime")), zoned(map(regular).get("endTime")), System.currentTimeMillis())
                : new Session(day, null, null, System.currentTimeMillis());
        sessions.put(m, s);
        return s;
    }

    /** 토스 체결 시각(ISO, KST) -> '2026-10-19 09:00:05'. 없으면 지금 */
    private String filledAt(Object iso) {
        try {
            return zoned(iso).format(AT);
        } catch (RuntimeException e) {
            return now().format(AT);
        }
    }

    private static ZonedDateTime zoned(Object iso) {
        return OffsetDateTime.parse(Values.str(iso)).atZoneSameInstant(SEOUL);
    }

    /** 시장의 오늘 (정규장 정보가 있으면 그 날짜, 없으면 국내는 한국 날짜, 미국은 뉴욕 날짜). */
    private String todayKey(String m) {
        Session s = sessions.get(m);
        if (s != null) {
            return s.day;
        }
        return now().withZoneSameInstant("KR".equals(m) ? SEOUL : ZoneId.of("America/New_York")).toLocalDate().toString();
    }

    private ZonedDateTime now() {
        return clock.get().withZoneSameInstant(SEOUL);
    }

    private void failed(String m, String day, String[] c, String why) throws SQLException {
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO trader_position (market, day, symbol, name, source, status, est, qty,"
                     + " note, opened) VALUES (?, ?, ?, ?, ?, 'FAILED', 0, 0, ?, ?)")) {
            ps.setString(1, m);
            ps.setString(2, day);
            ps.setString(3, c[0]);
            ps.setString(4, c[1]);
            ps.setString(5, c[2]);
            ps.setString(6, why);
            ps.setString(7, now().format(AT));
            ps.executeUpdate();
        }
        log(m, "매수 건너뜀: " + c[1] + "(" + c[0] + ") " + why);
    }

    // ------------------------------------------------------------------ 화면 상태

    /**
     * 토스 계좌 금액을 원화·달러로 나눠 받아 둡니다 (화면 '토스 계좌'). 자동매매가 산 종목만이 아니라 계좌 전체입니다.
     * cash: 주문 가능 현금(/buying-power, 미수 미발생 기준), stock: 주식 평가금액, purchase: 매입금액, profit: 손익 (/holdings)
     */
    @SuppressWarnings("unchecked")
    void refreshAccount() {
        accountAt = System.currentTimeMillis();
        Map<String, Object> a = new LinkedHashMap<>();
        try {
            Map<String, Object> h = map(api.get("/api/v1/holdings", null, true));
            Map<String, Object> mv = map(map(h.get("marketValue")).get("amount"));
            Map<String, Object> pa = map(h.get("totalPurchaseAmount"));
            Map<String, Object> pl = map(map(h.get("profitLoss")).get("amount"));
            int krCount = 0;
            int usCount = 0;
            if (h.get("items") instanceof List) {
                for (Object o : (List<Object>) h.get("items")) {
                    if ("US".equals(map(o).get("marketCountry"))) {
                        usCount++;
                    } else {
                        krCount++;
                    }
                }
            }
            for (String[] c : new String[][]{{"KRW", "krw", String.valueOf(krCount)}, {"USD", "usd", String.valueOf(usCount)}}) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("cash", Values.numOrNull(map(api.get("/api/v1/buying-power", param("currency", c[0]), true)).get("cashBuyingPower")));
                x.put("stock", Values.numOrNull(mv.get(c[1])));
                x.put("purchase", Values.numOrNull(pa.get(c[1])));
                x.put("profit", Values.numOrNull(pl.get(c[1])));
                x.put("count", Integer.parseInt(c[2]));
                a.put(c[0], x);
            }
            a.put("at", now().format(AT));
        } catch (IOException | RuntimeException e) {
            Map<String, Object> prev = account;
            a = prev == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prev);  // 이전 값은 그대로 두고 오류만 표시
            a.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        account = a;
    }

    /** GET /api/trader -> 설정, 매수 대상, 시장별 장 시간·단계·사용 금액, 오늘(과 아직 안 끝난) 매매, 최근 기록. 토스를 부르지 않습니다. */
    Map<String, Object> status() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", api != null);
        try {
            out.put("config", config());
            out.put("picks", rows("SELECT market, symbol, name, added FROM trader_pick ORDER BY market, added, rowid"));
            Map<String, Object> ms = new LinkedHashMap<>();
            List<Map<String, Object>> positions = new ArrayList<>();
            for (String m : MARKETS) {
                Session s = sessions.get(m);
                String day = todayKey(m);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("day", day);
                x.put("phase", api == null ? "토스 미설정" : leader ? phase.getOrDefault(m, "확인 전") : "다른 서버가 주문 중이거나 확인 전");
                x.put("open", s == null || s.open == null ? null : s.open.format(AT));
                x.put("cutoff", s == null || s.close == null ? null : s.close.minusMinutes(CUTOFF_MINUTES).format(AT));
                x.put("close", s == null || s.close == null ? null : s.close.format(AT));
                List<Map<String, Object>> today = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED'", m, day);
                x.put("spent", today.stream().mapToDouble(r -> Values.num(r.get("est"))).sum());
                x.put("count", today.size());
                x.put("halted", getFlag("halt:" + m + ":" + day) != null);
                x.put("signals", rows("SELECT symbol, name, at FROM trader_signal WHERE market = ? AND day = ? ORDER BY at", m, day));
                ms.put(m, x);
                for (Map<String, Object> p : rows("SELECT * FROM trader_position WHERE market = ? AND (day = ? OR status IN ('BUYING', 'OPEN', 'SELLING'))"
                        + " ORDER BY id", m, day)) {
                    Double last = lastPrice.get(m + ":" + p.get("symbol"));
                    p.put("last", last);
                    double avg = Values.num(p.get("avg"));
                    p.put("pnl", "CLOSED".equals(p.get("status")) && avg > 0 && p.get("sell_avg") != null
                            ? (Object) ((Values.num(p.get("sell_avg")) / avg - 1) * 100)
                            : last != null && avg > 0 ? (Object) ((last / avg - 1) * 100) : null);
                    positions.add(p);
                }
            }
            out.put("markets", ms);
            out.put("positions", positions);
            // 매수 내역: 체결된 매수 전부 (날짜와 상관없이, 최신 먼저 500건). 실패·미체결은 빼고
            List<Map<String, Object>> history = rows("SELECT id, market, day, symbol, name, source, status, qty, avg, amount, est, bought,"
                    + " sell_qty, sell_avg, reason, closed FROM trader_position WHERE bought IS NOT NULL ORDER BY bought DESC, id DESC LIMIT 500");
            for (Map<String, Object> h : history) {
                double avg = Values.num(h.get("avg"));
                double sellAvg = Values.num(h.get("sell_avg"));
                boolean closed = "CLOSED".equals(h.get("status")) && sellAvg > 0 && avg > 0;
                h.put("pnl", closed ? (Object) ((sellAvg / avg - 1) * 100) : null);
                h.put("pnlAmount", closed ? (Object) ((sellAvg - avg) * Values.num(h.get("sell_qty") != null ? h.get("sell_qty") : h.get("qty"))) : null);
            }
            out.put("history", history);
            out.put("log", rows("SELECT at, market, text FROM trader_log ORDER BY id DESC LIMIT 100"));
        } catch (SQLException e) {
            throw new IOException("trader.db 오류: " + e.getMessage(), e);
        }
        out.put("account", account);
        out.put("tickSeconds", TICK_SECONDS);
        out.put("cutoffMinutes", CUTOFF_MINUTES);
        return out;
    }

    // ------------------------------------------------------------------ trader.db

    /** 주문을 맡을 프로세스 임대. 같은 trader.db를 쓰는 다른 서버가 30초 안에 연장했으면 false. */
    boolean lease() throws SQLException {
        long t = System.currentTimeMillis();
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT INTO trader_lease (name, owner, until) VALUES ('trader', ?, ?)"
                     + " ON CONFLICT (name) DO UPDATE SET owner = excluded.owner, until = excluded.until"
                     + " WHERE trader_lease.owner = excluded.owner OR trader_lease.until < ?")) {
            ps.setString(1, owner);
            ps.setLong(2, t + LEASE_MS);
            ps.setLong(3, t);
            ps.executeUpdate();
        }
        List<Map<String, Object>> r = rows("SELECT owner FROM trader_lease WHERE name = 'trader'");
        return !r.isEmpty() && owner.equals(r.get(0).get("owner"));
    }

    private List<Map<String, Object>> positions(String m, String where) throws SQLException {
        return rows("SELECT * FROM trader_position WHERE market = ? AND " + where + " ORDER BY id", m);
    }

    private void update(Map<String, Object> p, String col, Object v) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement("UPDATE trader_position SET " + col + " = ? WHERE id = ?")) {
            ps.setObject(1, v);
            ps.setObject(2, p.get("id"));
            ps.executeUpdate();
        }
        p.put(col, v);
    }

    private String getFlag(String k) throws SQLException {
        List<Map<String, Object>> r = rows("SELECT v FROM trader_config WHERE k = ?", k);
        return r.isEmpty() ? null : Values.str(r.get(0).get("v"));
    }

    private void setFlag(String k, String v) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO trader_config (k, v) VALUES (?, ?)")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        }
    }

    /** 화면 '기록'(trader.db)에도 남기는 줄. 터미널·trader.log에도 같이 */
    void log(String m, String text) {
        trace(m, text);
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT INTO trader_log (at, market, text) VALUES (?, ?, ?)");
             PreparedStatement prune = con.prepareStatement("DELETE FROM trader_log WHERE id <= (SELECT MAX(id) - 2000 FROM trader_log)")) {
            ps.setString(1, now().format(AT));
            ps.setString(2, m);
            ps.setString(3, text);
            ps.executeUpdate();
            prune.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[자동매매] 기록 실패: " + e.getMessage());
        }
    }

    /**
     * 터미널(서버 로그, systemd면 journalctl)과 데이터 폴더의 trader.log에 한 줄. 주문 요청·접수·거절·재전송·취소처럼 자세한 줄은
     * 화면 '기록'에는 넣지 않고 여기만 남깁니다. trader.log는 5MB가 넘으면 trader.log.1로 넘기고 새로 씁니다.
     *   tail -f trader.log        # 서버 폴더에서 실시간으로 보기
     */
    void trace(String m, String text) {
        String line = "[자동매매] " + now().format(AT) + " " + ("ALL".equals(m) ? "전체" : "KR".equals(m) ? "국내" : "미국") + " " + text;
        System.out.println(line);
        synchronized (Trader.class) {
            try {
                Path file = logFile();
                if (java.nio.file.Files.exists(file) && java.nio.file.Files.size(file) > 5_000_000) {
                    java.nio.file.Files.move(file, file.resolveSibling("trader.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.write(file, (line + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.out.println("[자동매매] trader.log 쓰기 실패: " + e.getMessage());
            }
        }
    }

    /** trader.db와 같은 폴더의 trader.log */
    Path logFile() {
        return db.toAbsolutePath().resolveSibling("trader.log");
    }

    private List<Map<String, Object>> rows(String sql, Object... args) throws SQLException {
        try (Connection con = connect(); PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        r.put(md.getColumnName(i), rs.getObject(i));
                    }
                    out.add(r);
                }
            }
            return out;
        }
    }

    private Connection connect() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc가 classpath에 없습니다", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");
            for (String sql : SCHEMA.split(";")) {
                st.execute(sql);
            }
            if (!migrated) {  // 이 기능 전에 만든 trader.db: 매수 체결 시각 칸 추가
                boolean has = false;
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(trader_position)")) {
                    while (rs.next()) {
                        has |= "bought".equals(rs.getString("name"));
                    }
                }
                if (!has) {
                    st.execute("ALTER TABLE trader_position ADD COLUMN bought TEXT");
                    st.execute("UPDATE trader_position SET bought = opened WHERE avg > 0");  // 예전 체결분은 주문 시각으로
                }
                migrated = true;
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    // ------------------------------------------------------------------ 도우미

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new HashMap<>();
    }

    private static Map<String, String> param(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    /** 수량 문자열: 10.0 -> "10", 5.5 -> "5.5" (토스는 정수 수량만 받는 주문이 대부분) */
    static String fmtQty(double q) {
        return q == Math.rint(q) ? String.valueOf((long) q) : new java.math.BigDecimal(q).setScale(6, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }

    private static String pct(double r) {
        return String.format("%+.2f%%", r * 100);
    }

    private static String round(double v) {
        return v >= 1000 ? String.format("%,.0f", v) : String.format("%.2f", v);
    }
}
