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
 *   지수가 전일 대비 상승일 때만 새 매수: 국내는 코스닥, 미국은 러셀 2000 (토스엔 지수 시세가 없어 추종 ETF INDEX로 봄). 하락·보합이면 대기하다 오르면 매수
 *   - 미장 연동 (시장 모니터 화면, 따로 켜고 끔): 등록한 미국 티커가 직전 미장 종가 기준 전일 대비 모두 상승이면 KODEX 200,
 *     모두 하락이면 KODEX 인버스(종목·수량은 설정)를 코스피 장 시작 후 PAIR_WINDOW_MINUTES분 안에 시장가 매수. 섞이거나 보합이 있으면 안 삼.
 *     위의 최대 매수금액·종목 수·지수 조건과 따로 셈
 *   종목당 금액 = 하루 최대 매수금액 ÷ 최대 종목 수, 수량은 현재가로 나눈 정수(1주가 더 비싸면 건너뜀). 하루 매수 합계는 최대 매수금액을 넘지 않음.
 * 매도 (이 자동매매가 산 종목만, 다른 보유 종목은 건드리지 않음):
 *   - 평균 체결가 대비 +익절% 이상 → 시장가 매도, -손절% 이하 → 시장가 매도
 *   - 정규장 마감 10분 전: 미체결 매수 취소, 남은 종목 전량 시장가 매도, 그날 새 매수 중단
 *     (미장 연동 종목은 '장마감 전량 매도'를 끄면 팔지 않고 보유, 다음 날부터도 익절·손절만)
 *   - 꺼도 이미 산 종목의 익절·손절·장마감 매도는 계속 (켜고 끄는 건 새 매수만)
 *   - 서버가 꺼져 있어 전날 못 판 종목은 다음 장 시작 후 바로 매도
 *   - 시뮬레이션 전략 (모니터 화면 '매수 시뮬레이션'의 실제 자동매매, 따로 켜고 끔): 코스닥(KR) 또는 러셀 2000(US) 새 기록마다
 *     시뮬레이션 엔진(Ticks.live)을 오늘 기록으로 돌려, 그 시각에 산다고 나온 종목을 시장가 매수하고 판다고 나온 보유분을 매도.
 *     투자 금액 = 하루 최대 매수 합계, 종목당 금액 = 투자 금액 ÷ 하루 최대 종목 수. 익절 %·손절 %(tp-sl 모듈)는 5초마다도 확인.
 *     위의 최대 매수금액·종목 수·지수 조건과 따로 셈. 기록이 늦으면(기록 간격 + 10분) 주문하지 않음
 * 안전장치:
 *   - 같은 종목은 시장별 하루 한 번만 매수 (trader.db UNIQUE)
 *   - 주문마다 clientOrderId(멱등성 키): 응답을 못 받아 다시 보내도 두 번 체결되지 않음 (10분 안)
 *   - trader.db 임대(lease): 같은 데이터 폴더를 쓰는 서버가 여러 개여도 한 프로세스만 주문
 *   - 토스 API 키(TOSS_OPENAPI_CLIENT_ID/SECRET)가 없으면 아무것도 하지 않음
 * 5초마다 확인합니다 (시세 1번 + 체결 확인). 토스 장 운영 정보(/market-calendar)로 휴장·장 시간을 알아냅니다.
 */
final class Trader {

    /** 시뮬레이션 전략의 신호 (Ticks.live): 시장 KR|US, 시뮬레이션 화면 값 -> {date, at, every, buy: [{code, name, price}], sell: {종목: 이유}} */
    interface Strategy {
        Map<String, Object> live(String market, Map<String, Object> params) throws IOException;
    }

    /** 토스 API (TossClient). 시험은 가짜로 바꿔 끼웁니다. */
    interface Api {
        Object get(String path, Map<String, String> params, boolean account) throws IOException;

        Object post(String path, Map<String, Object> body, boolean account) throws IOException;
    }

    static final List<String> MARKETS = Arrays.asList("KR", "US");
    // ponytail: 지수 대신 추종 ETF(코스닥150·IWM)로 봄. 코스닥 종합지수와 조금 다를 수 있음, 정확히 필요하면 KB 지수 시세로 바꿀 것
    static final Map<String, String[]> INDEX = Map.of("KR", new String[]{"229200", "코스닥"}, "US", new String[]{"IWM", "러셀 2000"});
    static final int TICK_SECONDS = 5;
    static final String PAIR = "미장 연동";       // 미장 연동 매수의 source
    static final String SIM = "시뮬레이션";       // 시뮬레이션 전략 매수의 source
    static final int SIM_CHECK_SECONDS = 30;      // 시뮬레이션 신호 확인 간격 (새 기록은 5분·30분마다)
    static final int PAIR_WINDOW_MINUTES = 30;   // 코스피 장 시작 후 몇 분 안에만 미장 연동 매수 (서버가 늦게 켜져 오후에 사지 않게)
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
            + "CREATE TABLE IF NOT EXISTS trader_lease (name TEXT PRIMARY KEY, owner TEXT NOT NULL, until INTEGER NOT NULL);"
            // 미장 연동 미국 티커: 모두 상승·하락인지 볼 종목
            + "CREATE TABLE IF NOT EXISTS trader_us_watch (signal TEXT PRIMARY KEY, added TEXT NOT NULL)";

    private final Api api;
    private final Path db;
    private final Supplier<ZonedDateTime> clock;
    private final String owner = UUID.randomUUID().toString();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Double> lastPrice = new ConcurrentHashMap<>();   // "KR:005930" -> 현재가 (화면 손익 표시용)
    private final Map<String, Double> prevClose = new ConcurrentHashMap<>();   // "KR:2026-10-19" -> 지수 ETF 전일 종가 (하루 한 번 받음)
    private final Map<String, Map<String, Object>> pairChecks = new ConcurrentHashMap<>();  // "NVDA" -> 직전 미장 종가 비교 (미장 날짜·마감 여부가 바뀌면 다시)
    private volatile String pairPhase = "확인 전";
    private volatile String simPhase = "확인 전";
    private volatile long simCheckedAt;
    private volatile String simDoneAt;  // 이미 처리한 기록 'KR 2026-10-21 10:05'
    /** 시뮬레이션 전략 신호 (서버가 코스닥·러셀 기록으로 연결). null이면 시뮬레이션 전략은 대기만 */
    volatile Strategy strategy;
    private final Map<String, String> phase = new ConcurrentHashMap<>();
    private final Map<String, String> lastError = new ConcurrentHashMap<>();
    private volatile Boolean lastLeader;  // 주문 담당이 바뀔 때만 남기려고
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
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("enabled", false);    // 미장 연동 매수 (위 enabled와 따로)
        pair.put("closeSell", true);   // 미장 연동 종목도 장마감 10분 전 전량 매도. 끄면 보유하고 익절·손절만
        pair.put("up", etf("069500", "KODEX 200"));          // 미국 티커가 모두 상승이면 살 종목
        pair.put("inverse", etf("114800", "KODEX 인버스"));  // 모두 하락이면 살 종목
        c.put("pair", pair);
        Map<String, Object> sim = new LinkedHashMap<>();
        sim.put("enabled", false);   // 시뮬레이션 전략 실제 매매 (위 enabled·pair와 따로)
        sim.put("market", "KR");     // KR(코스닥 기록) | US(러셀 2000 기록)
        sim.put("budget", 0.0);      // 투자 금액 = 하루 최대 매수 합계 (KR 원, US 달러)
        sim.put("params", new LinkedHashMap<>());  // 시뮬레이션 화면 값 (simParams)
        c.put("sim", sim);
        return c;
    }

    private static Map<String, Object> etf(String symbol, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("name", name);
        m.put("qty", 1);
        m.put("takeProfit", 3.0);  // 이 종목의 익절·손절 % (자동매매 화면의 takeProfit·stopLoss와 따로)
        m.put("stopLoss", 2.0);
        return m;
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
                    // 미장 연동 종목: 저장된 값에 없는 칸은 기본값으로. 예전 공통 익절·손절(pair.takeProfit·stopLoss)이 있으면 두 종목에 그대로
                    Map<String, Object> pair = (Map<String, Object>) c.get("pair");
                    Map<String, Object> dflt = (Map<String, Object>) defaults().get("pair");
                    Map<String, Object> savedPair = saved.get("pair") instanceof Map ? (Map<String, Object>) saved.get("pair") : new HashMap<>();
                    for (String k : new String[]{"up", "inverse"}) {
                        Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) dflt.get(k));
                        Map<String, Object> cur = savedPair.get(k) instanceof Map ? (Map<String, Object>) savedPair.get(k) : new HashMap<>();
                        for (String t : new String[]{"takeProfit", "stopLoss"}) {
                            if (!cur.containsKey(t) && savedPair.get(t) != null) {
                                e.put(t, savedPair.get(t));
                            }
                        }
                        e.putAll(cur);
                        pair.put(k, e);
                    }
                    pair.remove("takeProfit");
                    pair.remove("stopLoss");
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
        putPercents(in, c);
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
        if (in.get("pair") instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) in.get("pair");
            Map<String, Object> dst = (Map<String, Object>) c.get("pair");
            for (String k : new String[]{"enabled", "closeSell"}) {
                if (src.containsKey(k)) {
                    dst.put(k, Boolean.TRUE.equals(src.get(k)));
                }
            }
            for (String k : new String[]{"up", "inverse"}) {
                if (!(src.get(k) instanceof Map)) {
                    continue;
                }
                Map<String, Object> in2 = (Map<String, Object>) src.get(k);
                Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) dst.get(k));
                String what = "up".equals(k) ? "모두 상승 종목" : "모두 하락 종목";
                if (in2.containsKey("symbol")) {
                    e.put("symbol", symbol("KR", Values.str(in2.get("symbol"))));
                    String n = Values.str(in2.get("name")).trim();
                    e.put("name", n.isEmpty() ? e.get("symbol") : n.length() > 40 ? n.substring(0, 40) : n);
                }
                if (in2.containsKey("qty")) {
                    double q = number(in2.get("qty"), what + " 수량");
                    if (q < 1 || q > 10_000 || q != Math.rint(q)) {
                        throw new IllegalArgumentException(what + " 수량은 1~10000 사이 정수여야 합니다: " + in2.get("qty"));
                    }
                    e.put("qty", (int) q);
                }
                putPercents(in2, e);
                dst.put(k, e);
            }
        }
        if (in.get("sim") instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) in.get("sim");
            Map<String, Object> dst = (Map<String, Object>) c.get("sim");
            if (src.containsKey("market")) {
                market(Values.str(src.get("market")));
                dst.put("market", Values.str(src.get("market")));
            }
            if (src.containsKey("budget")) {
                double v = number(src.get("budget"), "투자 금액");
                if (v < 0) {
                    throw new IllegalArgumentException("투자 금액은 0 이상이어야 합니다.");
                }
                dst.put("budget", v);
            }
            if (src.get("params") instanceof Map) {
                dst.put("params", simParams((Map<String, Object>) src.get("params")));
            }
            if (src.containsKey("enabled")) {
                dst.put("enabled", Boolean.TRUE.equals(src.get("enabled")));
            }
            if (Boolean.TRUE.equals(dst.get("enabled"))) {
                if (Values.num(dst.get("budget")) <= 0) {
                    throw new IllegalArgumentException("투자 금액을 넣어 주세요.");
                }
                simParams((Map<String, Object>) dst.get("params"));  // 켤 때 저장된 값도 다시 검사
            }
        }
        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement("INSERT OR REPLACE INTO trader_config (k, v) VALUES ('config', ?)")) {
            ps.setString(1, GSON.toJson(c));
            ps.executeUpdate();
        }
        log("ALL", "설정 저장: " + (Boolean.TRUE.equals(c.get("enabled")) ? "켜짐" : "꺼짐") + " · 익절 " + c.get("takeProfit")
                + "% · 손절 " + c.get("stopLoss") + "% · 국내 " + c.get("KR") + " · 미국 " + c.get("US") + " · 미장 연동 " + c.get("pair")
                + " · 시뮬레이션 " + c.get("sim"));
    }

    /** 시뮬레이션 화면 값 검사 (Ticks.live에 그대로 넘김). 하루 최대 종목 수는 1~50: 종목당 금액 = 투자 금액 ÷ 이 값 */
    static Map<String, Object> simParams(Map<String, Object> in) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (String k : new String[]{"buy", "sell"}) {
            String v = Values.str(in.getOrDefault(k, "buy".equals(k) ? "volume-open-up" : "tp-sl"));
            if ("buy".equals(k)) {  // 없는 모듈이면 IllegalArgumentException
                SimModules.pick(SimModules.BUY, v);
            } else {
                SimModules.pick(SimModules.SELL, v);
            }
            p.put(k, v);
        }
        String[][] nums = {{"min", "거래량 기준"}, {"hold", "유지 시간"}, {"tp", "익절 %"}, {"sl", "손절 %"}, {"maxHold", "최대 보유 시간"}, {"maxBuys", "하루 최대 종목 수"}};
        for (String[] k : nums) {
            double v = number(in.getOrDefault(k[0], "maxBuys".equals(k[0]) ? 5 : 0), k[1]);
            if (v < 0 && !"min".equals(k[0])) {
                throw new IllegalArgumentException(k[1] + " 값은 0 이상이어야 합니다: " + v);
            }
            p.put(k[0], v);
        }
        double mb = Values.num(p.get("maxBuys"));
        if (mb < 1 || mb > 50 || mb != Math.rint(mb)) {
            throw new IllegalArgumentException("실제 자동매매는 하루 최대 종목 수를 1~50으로 넣어 주세요 (종목당 금액 = 투자 금액 ÷ 종목 수).");
        }
        for (String k : new String[]{"until", "start"}) {
            String v = Values.str(in.getOrDefault(k, "until".equals(k) ? "15:00" : "")).trim();
            if (!(v.isEmpty() && "start".equals(k)) && !v.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
                throw new IllegalArgumentException(("until".equals(k) ? "매수 마감" : "매수 시작") + " 시각이 올바르지 않습니다: " + v);
            }
            p.put(k, v);
        }
        p.put("spac", !Boolean.FALSE.equals(in.get("spac")));
        p.put("alerts", Boolean.TRUE.equals(in.get("alerts")));
        return p;
    }

    /**
     * 이 보유분의 {익절, 손절} 비율. 미장 연동 종목은 같은 종목번호인 KODEX 200(up)·인버스(inverse) 설정의 %,
     * 산 뒤에 설정 종목을 바꿔 맞는 것이 없으면 자동매매 화면의 % (tp, sl).
     */
    @SuppressWarnings("unchecked")
    private static double[] limits(Map<String, Object> p, Map<String, Object> pc, Map<String, Object> sc, double tp, double sl) {
        if (SIM.equals(p.get("source"))) {  // 시뮬레이션 화면의 익절·손절 % (0이면 안 함, 손절은 tp-sl 모듈일 때만: 추적 손절은 기록마다 신호로)
            Map<String, Object> sp = (Map<String, Object>) sc.get("params");
            double stp = Values.num(sp.get("tp"));
            double ssl = Values.num(sp.get("sl"));
            return new double[]{stp > 0 ? stp / 100 : Double.POSITIVE_INFINITY,
                    "tp-sl".equals(sp.get("sell")) && ssl > 0 ? ssl / 100 : Double.POSITIVE_INFINITY};
        }
        if (PAIR.equals(p.get("source"))) {
            for (String k : new String[]{"up", "inverse"}) {
                Map<String, Object> e = (Map<String, Object>) pc.get(k);
                if (Values.str(e.get("symbol")).equals(Values.str(p.get("symbol")))) {
                    return new double[]{Values.num(e.get("takeProfit")) / 100, Values.num(e.get("stopLoss")) / 100};
                }
            }
        }
        return new double[]{tp, sl};
    }

    /** 익절·손절 %: 0.1~100 */
    private static void putPercents(Map<String, Object> src, Map<String, Object> dst) {
        for (String k : new String[]{"takeProfit", "stopLoss"}) {
            if (src.containsKey(k)) {
                double v = number(src.get(k), k);
                if (v < 0.1 || v > 100) {
                    throw new IllegalArgumentException((k.equals("takeProfit") ? "익절" : "손절") + " %는 0.1~100 사이여야 합니다: " + v);
                }
                dst.put(k, v);
            }
        }
    }

    private static double number(Object v, String name) {
        Double d = Values.numOrNull(v);
        if (d == null || "".equals(Values.str(v).trim()) || d.isNaN() || d.isInfinite()) {
            throw new IllegalArgumentException(name + " 값이 숫자가 아닙니다: " + v);
        }
        return d;
    }

    // ------------------------------------------------------------------ 화면 명령

    /** POST /api/trader {"action": "config"|"pick"|"unpick"|"watch"|"unwatch"|"sellAll", ...} -> status() */
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
                case "watch": {  // 미장 연동 미국 티커 추가: {signal}
                    String signal = symbol("US", Values.str(body.get("signal")));
                    try (Connection con = connect();
                         PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO trader_us_watch (signal, added) VALUES (?, ?)")) {
                        ps.setString(1, signal);
                        ps.setString(2, now().format(AT));
                        ps.executeUpdate();
                    }
                    log("KR", "미장 연동 미국 티커 추가: " + signal);
                    break;
                }
                case "unwatch":
                    try (Connection con = connect();
                         PreparedStatement ps = con.prepareStatement("DELETE FROM trader_us_watch WHERE signal = ?")) {
                        ps.setString(1, Values.str(body.get("signal")).trim().toUpperCase());
                        ps.executeUpdate();
                    }
                    log("KR", "미장 연동 미국 티커 삭제: " + body.get("signal"));
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
        if (!Boolean.valueOf(leader).equals(lastLeader)) {
            lastLeader = leader;
            log("ALL", leader ? "주문 담당: 이 서버 (5초마다 확인)" : "다른 서버가 주문을 맡고 있어 이 서버는 기다림 (같은 trader.db 임대)");
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
            setPhase(m, "휴장");
            return;
        }
        ZonedDateTime cutoff = s.close.minusMinutes(CUTOFF_MINUTES);
        if ("KR".equals(m)) {
            watchChecks();  // 화면 작은 히트맵용으로 장 시작 전에도 미장 종가 확인 (미장이 끝날 때마다 새로)
        }
        if (now.isBefore(s.open)) {
            setPhase(m, "장 시작 전 (정규장 " + s.open.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " 시작, 한국 시간)");
            return;
        }
        if (!now.isBefore(s.close)) {
            setPhase(m, "장 마감");
            return;
        }
        List<Map<String, Object>> open = positions(m, "status = 'OPEN'");
        Map<String, Object> pc = (Map<String, Object>) cfg.get("pair");
        boolean holdPairs = !Boolean.TRUE.equals(pc.get("closeSell"));  // 미장 연동 종목은 장마감에 팔지 않고 보유
        if (!now.isBefore(cutoff)) {  // 마감 10분 전: 미체결 매수 취소, 전량 매도, 새 매수 없음
            setPhase(m, "마감 정리 (새 매수 없음, 남은 종목 전량 매도" + (holdPairs ? ", 미장 연동 종목은 보유" : "") + ")");
            for (Map<String, Object> p : positions(m, "status = 'BUYING' AND buy_order IS NOT NULL")) {
                cancel(m, p);
            }
            for (Map<String, Object> p : open) {
                if (!(holdPairs && PAIR.equals(p.get("source")))) {
                    sell(m, p, "장마감");
                }
            }
            return;
        }
        boolean halted = getFlag("halt:" + m + ":" + s.day) != null;
        if (halted || !Boolean.TRUE.equals(cfg.get("enabled"))) {  // 켜져 있으면 아래에서 매수 상황(대기 이유)으로 정함
            setPhase(m, halted ? "매수 중단 (전량 매도 요청)" : "꺼짐 (새 매수 없음, 보유분 익절·손절만)");
        }
        if (halted) {
            for (Map<String, Object> p : positions(m, "status = 'BUYING' AND buy_order IS NOT NULL")) {
                cancel(m, p);
            }
            for (Map<String, Object> p : open) {
                sell(m, p, "수동 전량 매도");
            }
            return;
        }
        // 익절·손절 (전날 못 판 종목은 바로 매도). 미장 연동 종목은 미장 연동 %로
        double tp = Values.num(cfg.get("takeProfit")) / 100;
        double sl = Values.num(cfg.get("stopLoss")) / 100;
        Map<String, Double> prices = prices(m, open.stream().map(p -> Values.str(p.get("symbol"))).toArray(String[]::new));
        for (Map<String, Object> p : open) {
            if (!s.day.equals(p.get("day")) && !(holdPairs && PAIR.equals(p.get("source")))) {  // 보유로 둔 미장 연동 종목은 익절·손절만
                sell(m, p, "전날 미청산");
                continue;
            }
            Double price = prices.get(Values.str(p.get("symbol")));
            double avg = Values.num(p.get("avg"));
            if (price == null || avg <= 0) {
                continue;
            }
            double r = price / avg - 1;
            double[] limit = limits(p, pc, (Map<String, Object>) cfg.get("sim"), tp, sl);
            if (r >= limit[0]) {
                sell(m, p, "익절 " + pct(r));
            } else if (r <= -limit[1]) {
                sell(m, p, "손절 " + pct(r));
            }
        }
        if ("KR".equals(m)) {
            buyWatch(s, now, pc);
        }
        simTick(m, s, cfg);
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
        String name = "KR".equals(m) ? "국내" : "미국";
        if (candidates.isEmpty()) {  // 왜 사지 않는지 화면·로그에 (바뀔 때 한 번)
            setPhase(m, "매매 중 · 매수 대상 없음: 직접 고른 " + name + " 종목이 없고 "
                    + (Boolean.TRUE.equals(mc.get("auto")) ? "오늘 " + name + " 시그널 알림이 아직 없음" : "시그널 자동 매수가 꺼져 있음"));
            return;
        }
        String waiting = "매매 중 · 오늘 매수 대상 " + candidates.size() + "종목을 모두 처리함 (같은 종목은 하루 한 번)";
        Boolean up = null;  // 지수 전일 대비 상승: 실제로 살 종목이 있을 때만 확인 (시세 1번)
        for (String[] c : candidates) {
            if (!rows("SELECT id FROM trader_position WHERE market = ? AND day = ? AND symbol = ?", m, s.day, c[0]).isEmpty()) {
                continue;  // 오늘 이미 샀거나 시도함
            }
            List<Map<String, Object>> today = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED'"
                    + " AND source NOT IN (?, ?)", m, s.day, PAIR, SIM);  // 미장 연동·시뮬레이션은 따로 셈
            double spent = today.stream().mapToDouble(r -> Values.num(r.get("est"))).sum();
            if (today.size() >= maxStocks || spent >= budget) {
                waiting = "매매 중 · " + (today.size() >= maxStocks ? "오늘 최대 종목 수(" + maxStocks + ") 도달" : "오늘 최대 매수금액 다 씀")
                        + ", 남은 매수 대상은 사지 않음";
                break;
            }
            if (up == null) {
                up = indexUp(m, s.day);
            }
            if (!up) {  // 다음 확인(5초 뒤)에 다시 봐서 오르면 매수
                waiting = "매매 중 · " + INDEX.get(m)[1] + " 지수가 전일 대비 상승이 아니라 새 매수 대기 (상승하면 매수)";
                break;
            }
            double amount = Math.min(budget / maxStocks, budget - spent);
            Double price = prices(m, c[0]).get(c[0]);
            if (price == null || price <= 0) {
                waiting = "매매 중 · " + c[1] + "(" + c[0] + ") 현재가를 받지 못해 다시 시도";
                continue;  // 현재가를 못 받음: 다음 확인에 다시
            }
            long qty = (long) Math.floor(amount / price);
            if (qty < 1) {
                failed(m, s.day, c, "1주 가격(" + price + ")이 종목당 금액(" + round(amount) + ")보다 큼");
                continue;
            }
            buy(m, s.day, c, qty, qty * price);
        }
        setPhase(m, waiting);
    }

    /**
     * 시뮬레이션 전략: SIM_CHECK_SECONDS마다 신호(Ticks.live)를 받아, 새 기록 시각이면 판다는 보유분을 매도하고 산다는 종목을 매수.
     * 같은 기록은 한 번만, 같은 종목은 하루 한 번 (trader_position UNIQUE). 오늘 기록이 아니거나 늦으면 주문하지 않음.
     */
    @SuppressWarnings("unchecked")
    private void simTick(String m, Session s, Map<String, Object> cfg) throws SQLException, IOException {
        Map<String, Object> sc = (Map<String, Object>) cfg.get("sim");
        if (!Boolean.TRUE.equals(sc.get("enabled")) || !m.equals(sc.get("market"))) {
            return;
        }
        if (strategy == null) {
            setSimPhase(m, "이 서버에 코스닥·러셀 기록이 연결되지 않아 대기");
            return;
        }
        long t = now().toInstant().toEpochMilli();
        if (t - simCheckedAt < SIM_CHECK_SECONDS * 1000L) {
            return;
        }
        simCheckedAt = t;
        double budget = Values.num(sc.get("budget"));
        Map<String, Object> p = new LinkedHashMap<>((Map<String, Object>) sc.get("params"));
        int maxBuys = (int) Values.num(p.get("maxBuys"));
        if (budget <= 0 || maxBuys < 1) {
            setSimPhase(m, "투자 금액·하루 최대 종목 수가 없어 대기");
            return;
        }
        p.put("amount", budget / maxBuys);  // 엔진도 종목당 금액보다 비싼 종목은 뺌
        Map<String, Object> sig = strategy.live(m, p);
        String at = Values.str(sig.get("at"));
        if (!s.day.equals(sig.get("date"))) {
            setSimPhase(m, "매매 중 · 오늘 기록이 아직 없음 (" + ("KR".equals(m) ? "코스닥 5분" : "러셀 2000 30분") + " 기록을 기다림)");
            return;
        }
        ZonedDateTime local = now().withZoneSameInstant("KR".equals(m) ? SEOUL : ZoneId.of("America/New_York"));
        int late = local.getHour() * 60 + local.getMinute() - (Integer.parseInt(at.substring(0, 2)) * 60 + Integer.parseInt(at.substring(3, 5)));
        if (late > Values.num(sig.get("every")) + 10) {
            setSimPhase(m, "매매 중 · 마지막 기록(" + at + ")이 " + late + "분 전이라 주문하지 않음 (기록이 멈췄는지 확인)");
            return;
        }
        String key = m + " " + s.day + " " + at;
        if (key.equals(simDoneAt)) {
            return;
        }
        simDoneAt = key;
        Map<String, Object> sells = map(sig.get("sell"));
        int sold = 0;
        for (Map<String, Object> pos : rows("SELECT * FROM trader_position WHERE market = ? AND status = 'OPEN' AND source = ? ORDER BY id", m, SIM)) {
            Object why = sells.get(Values.str(pos.get("symbol")));
            if (why != null) {
                sell(m, pos, "시뮬레이션 " + why);
                sold++;
            }
        }
        int bought = 0;
        String note = "";
        for (Object o : sig.get("buy") instanceof List ? (List<Object>) sig.get("buy") : new ArrayList<>()) {
            Map<String, Object> b = map(o);
            String[] c = {Values.str(b.get("code")).trim().toUpperCase(), Values.str(b.get("name")), SIM};
            if (!rows("SELECT id FROM trader_position WHERE market = ? AND day = ? AND symbol = ?", m, s.day, c[0]).isEmpty()) {
                continue;  // 오늘 이미 샀거나 시도함 (다른 출처 포함)
            }
            List<Map<String, Object>> today = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED' AND source = ?",
                    m, s.day, SIM);
            double spent = today.stream().mapToDouble(r -> Values.num(r.get("est"))).sum();
            if (today.size() >= maxBuys || spent >= budget) {
                note = " · 오늘 " + (today.size() >= maxBuys ? "최대 종목 수(" + maxBuys + ")" : "투자 금액") + "를 다 써서 남은 신호는 사지 않음";
                break;
            }
            double amount = Math.min(budget / maxBuys, budget - spent);
            Double price = prices(m, c[0]).get(c[0]);
            if (price == null || price <= 0) {
                failed(m, s.day, c, "현재가를 받지 못함 (토스에 없는 종목일 수 있음)");
                continue;
            }
            long qty = (long) Math.floor(amount / price);
            if (qty < 1) {
                failed(m, s.day, c, "1주 가격(" + price + ")이 종목당 금액(" + round(amount) + ")보다 큼");
                continue;
            }
            buy(m, s.day, c, qty, qty * price);
            bought++;
        }
        setSimPhase(m, "매매 중 · 마지막 기록 " + at + " · 이번 매수 " + bought + " · 매도 " + sold + note);
    }

    private void setSimPhase(String m, String text) {
        if (!text.equals(simPhase)) {
            simPhase = text;
            log(m, "시뮬레이션: " + text);
        }
    }

    private List<String> watchList() throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT signal FROM trader_us_watch ORDER BY added, rowid")) {
            out.add(Values.str(r.get("signal")));
        }
        return out;
    }

    /** 등록한 미국 티커의 미장 종가 비교를 미리 받아 둠. 실패한 티커는 다음 확인에 다시 (매수 판단은 buyWatch) */
    private void watchChecks() throws SQLException {
        for (String signal : watchList()) {
            try {
                pairCheck(signal);
            } catch (IOException | RuntimeException e) {
                // 다음 확인에 다시
            }
        }
    }

    /**
     * 미장 연동 매수 (코스피, 장 시작 후 PAIR_WINDOW_MINUTES분 안): 등록한 미국 티커가 직전 미장 종가 기준 전일 대비
     * 모두 상승이면 pair.up(KODEX 200), 모두 하락이면 pair.inverse(KODEX 인버스)를 지정 수량 시장가 매수. 섞이거나 보합이 있으면 안 삼.
     * 아직 확인하지 못한 티커가 있으면 판단을 미룸. 같은 종목은 하루 한 번 (trader_position UNIQUE).
     */
    @SuppressWarnings("unchecked")
    private void buyWatch(Session s, ZonedDateTime now, Map<String, Object> pc) throws SQLException, IOException {
        List<String> tickers = watchList();
        String text;
        if (!Boolean.TRUE.equals(pc.get("enabled"))) {
            text = "꺼짐 (새 매수 없음, 보유분 익절·손절" + (Boolean.TRUE.equals(pc.get("closeSell")) ? "·장마감 매도)" : "만)");
        } else if (tickers.isEmpty()) {
            text = "켜짐 · 미국 티커가 없음";
        } else if (!now.isBefore(s.open.plusMinutes(PAIR_WINDOW_MINUTES))) {
            text = "켜짐 · 오늘 매수 시간(장 시작 후 " + PAIR_WINDOW_MINUTES + "분) 지남";
        } else {
            text = null;
            int up = 0;
            int down = 0;
            for (String signal : tickers) {
                Map<String, Object> chk;
                try {
                    chk = pairCheck(signal);  // 이미 확인한 티커는 기억한 값
                } catch (IOException e) {
                    text = "켜짐 · " + signal + " 미장 종가를 받지 못해 다시 시도 (" + e.getMessage() + ")";
                    break;
                }
                if (chk == null) {
                    text = "켜짐 · " + signal + " 미장 종가가 부족해 다시 시도";
                    break;
                }
                double c = Values.num(chk.get("change"));
                up += c > 0 ? 1 : 0;
                down += c < 0 ? 1 : 0;
            }
            if (text == null) {
                String k = up == tickers.size() ? "up" : down == tickers.size() ? "inverse" : null;
                if (k == null) {
                    text = "켜짐 · 미국 티커 " + tickers.size() + "개 중 상승 " + up + " · 하락 " + down + " → 모두 같은 방향이 아니라 오늘은 안 삼";
                } else {
                    Map<String, Object> e = (Map<String, Object>) pc.get(k);
                    String symbol = Values.str(e.get("symbol"));
                    String label = "켜짐 · 미국 티커 " + tickers.size() + "개 모두 " + ("up".equals(k) ? "상승" : "하락") + " → " + e.get("name") + "(" + symbol + ")";
                    if (!rows("SELECT id FROM trader_position WHERE market = 'KR' AND day = ? AND symbol = ?", s.day, symbol).isEmpty()) {
                        text = label + " 오늘 매수함";
                    } else {
                        Double price = prices("KR", symbol).get(symbol);
                        if (price == null || price <= 0) {
                            text = label + " 현재가를 받지 못해 다시 시도";
                        } else {
                            long qty = Values.lng(e.get("qty"));
                            buy("KR", s.day, new String[]{symbol, Values.str(e.get("name")), PAIR}, qty, qty * price);
                            text = label + " 매수함";
                        }
                    }
                }
            }
        }
        if (!text.equals(pairPhase)) {
            pairPhase = text;
            log("KR", "미장 연동: " + text);
        }
    }

    /**
     * 미국 종목의 직전 미장 종가 비교 {usDay, close, prev, change(%), up}. 미장 날짜·마감 여부가 같으면 기억한 값.
     * (예전엔 코스피 날짜마다 한 번이라, 한국 자정~미장 마감 사이에 받으면 전전일 종가가 다음 날 장 시작까지 남았음)
     * 일봉(최신순)에서 아직 끝나지 않은 오늘 미장 봉은 빼고, 남은 맨 앞 두 봉의 종가를 비교. 봉이 모자라면 null (다음 확인에 다시)
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> pairCheck(String signal) throws IOException {
        Session us = session("US");
        boolean closed = us.open != null && !now().isBefore(us.close);  // 오늘 미장이 끝났으면 오늘 봉도 확정
        String key = us.day + (closed ? " 마감" : "");
        Map<String, Object> got = pairChecks.get(signal);
        if (got != null && key.equals(got.get("checked"))) {
            return got;
        }
        Map<String, String> q = param("symbol", signal);
        q.put("interval", "1d");
        q.put("count", "5");
        Object candles = map(api.get("/api/v1/candles", q, false)).get("candles");
        List<Map<String, Object>> done = new ArrayList<>();
        for (Object o : candles instanceof List ? (List<Object>) candles : new ArrayList<>()) {
            String d = Values.str(map(o).get("timestamp"));
            d = d.length() >= 10 ? d.substring(0, 10) : d;
            if (d.compareTo(us.day) < 0 || d.equals(us.day) && closed) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("d", d);
                c.put("c", Values.numOrNull(map(o).get("closePrice")));
                done.add(c);
            }
        }
        done.sort((a, b) -> Values.str(b.get("d")).compareTo(Values.str(a.get("d"))));
        if (done.size() < 2 || done.get(0).get("c") == null || done.get(1).get("c") == null || Values.num(done.get(1).get("c")) <= 0) {
            return null;
        }
        double close = Values.num(done.get(0).get("c"));
        double prev = Values.num(done.get(1).get("c"));
        got = new LinkedHashMap<>();
        got.put("usDay", done.get(0).get("d"));
        got.put("close", close);
        got.put("prev", prev);
        got.put("change", (close / prev - 1) * 100);
        got.put("up", close > prev);
        got.put("checked", key);
        pairChecks.put(signal, got);
        log("KR", "미장 연동 확인: " + signal + " " + got.get("usDay") + " 종가 " + close + " (전일 " + prev + ", " + pct(close / prev - 1) + ") → "
                + (close > prev ? "상승" : close < prev ? "하락" : "보합"));
        return got;
    }

    /** 시장 단계 (화면에 표시). 바뀔 때만 기록·터미널·trader.log에 한 줄 남겨 '왜 주문이 없는지' 알 수 있게 */
    private void setPhase(String m, String text) {
        if (!text.equals(phase.put(m, text))) {
            log(m, "상태: " + text);
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

    /** 지수(추종 ETF) 현재가 > 전일 종가. 전일 종가는 일봉(/candles)에서 오늘이 아닌 가장 최근 봉, 시장·날짜마다 한 번. 시세를 못 받으면 false */
    @SuppressWarnings("unchecked")
    private boolean indexUp(String m, String day) throws IOException {
        String symbol = INDEX.get(m)[0];
        Double prev = prevClose.get(m + ":" + day);
        if (prev == null) {
            Map<String, String> q = param("symbol", symbol);
            q.put("interval", "1d");
            q.put("count", "2");
            Object candles = map(api.get("/api/v1/candles", q, false)).get("candles");
            for (Object o : candles instanceof List ? (List<Object>) candles : new ArrayList<>()) {
                if (!Values.str(map(o).get("timestamp")).startsWith(day)) {  // 장중이면 맨 앞은 오늘 봉
                    prev = Values.numOrNull(map(o).get("closePrice"));
                    break;
                }
            }
            if (prev == null || prev <= 0) {
                return false;
            }
            prevClose.put(m + ":" + day, prev);
        }
        Double last = prices(m, symbol).get(symbol);
        return last != null && last > prev;
    }

    /** 그날 정규장 (/market-calendar, 10분마다 다시 받음). 국내는 KRX·NXT 통합 정규장, 미국은 정규장. */
    @SuppressWarnings("unchecked")
    Session session(String m) throws IOException {
        Session s = sessions.get(m);
        if (s != null && System.currentTimeMillis() - s.fetchedAt < 600_000 && s.day.equals(localDate(m))) {  // 현지 날짜가 바뀌면 바로 다시
            return s;
        }
        // date: 그 시장의 현지 날짜. 안 넘기면 토스가 한국 날짜로 답해서, 한국 토요일 새벽(뉴욕 금요일 장중)에 미국을 휴장으로 판단했음
        Map<String, Object> cal = map(api.get("/api/v1/market-calendar/" + m, param("date", localDate(m)), false));
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

    /** 시장의 오늘 (정규장 정보가 있으면 그 날짜, 없으면 현지 날짜). */
    private String todayKey(String m) {
        Session s = sessions.get(m);
        return s != null ? s.day : localDate(m);
    }

    /** 현지 날짜: 국내는 한국, 미국은 뉴욕 (서머타임 자동). 예: 한국 10-17 02:44 -> 미국 2026-10-16 */
    String localDate(String m) {
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
                List<Map<String, Object>> today = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED'"
                        + " AND source NOT IN (?, ?)", m, day, PAIR, SIM);  // 미장 연동·시뮬레이션은 따로 셈
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
            // 미장 연동 미국 티커: 마지막으로 확인한 미장 종가 비교와 함께
            List<Map<String, Object>> watch = rows("SELECT signal, added FROM trader_us_watch ORDER BY added, rowid");
            for (Map<String, Object> w : watch) {
                w.put("check", pairChecks.get(Values.str(w.get("signal"))));
            }
            out.put("watch", watch);
            out.put("pairPhase", api == null ? "토스 미설정" : leader ? pairPhase : "다른 서버가 주문 중이거나 확인 전");
            out.put("pairWindowMinutes", PAIR_WINDOW_MINUTES);
            Map<String, Object> sc = (Map<String, Object>) ((Map<String, Object>) out.get("config")).get("sim");
            String sm = Values.str(sc.get("market"));
            List<Map<String, Object>> simToday = rows("SELECT est FROM trader_position WHERE market = ? AND day = ? AND status <> 'FAILED' AND source = ?",
                    sm, todayKey(sm), SIM);
            Map<String, Object> simOut = new LinkedHashMap<>();
            simOut.put("phase", api == null ? "토스 미설정" : !Boolean.TRUE.equals(sc.get("enabled")) ? "꺼짐 (새 매수 없음, 산 종목은 익절·손절·장마감 매도)"
                    : leader ? simPhase : "다른 서버가 주문 중이거나 확인 전");
            simOut.put("day", todayKey(sm));
            simOut.put("spent", simToday.stream().mapToDouble(r -> Values.num(r.get("est"))).sum());
            simOut.put("count", simToday.size());
            simOut.put("connected", strategy != null);
            out.put("sim", simOut);
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
