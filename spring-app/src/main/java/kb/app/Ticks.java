package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 장중 N분마다 시장 전 종목의 누적 거래량·현재가를 SQLite에 저장하고, 고른 시각의 누적 거래량을
 * 전일·전주(7일 전 이전의 마지막 거래일) 같은 시각과 비교합니다 (volume.py의 Ticks와 같은 파일·같은 테이블).
 * 같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록을 씁니다. 시각은 그 시장의 현지 시간입니다 (코스닥 한국, 러셀 2000 뉴욕).
 * 기록 주기는 가진 쪽(Volume·Russell·Alerts)의 @Scheduled가 tick()을 불러 정합니다. 시장·파일마다 하나씩이라 Bean이 아닙니다.
 */
public final class Ticks {

    /** at(현지 시간) 시각의 [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...]. 휴장이면 빈 목록. */
    public interface Take {
        List<List<Object>> rows(LocalDateTime at) throws IOException;
    }

    private static final Logger log = LoggerFactory.getLogger(Ticks.class);
    private static final int KEEP_DAYS = 14;       // 전주 비교에 7일 + 긴 연휴 여유
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type ROWS = new TypeToken<List<List<Object>>>() { }.getType();

    private final String name;
    private final JdbcTemplate db;
    private final String table;
    private final ZoneId zone;
    private final int minutes;
    private final LocalTime open;
    private final LocalTime close;
    private final Take take;

    Ticks(String name, JdbcTemplate db, String table, ZoneId zone, int minutes, LocalTime open, LocalTime close, Take take) {
        this.name = name;
        this.db = db;
        this.table = table;
        this.zone = zone;
        this.minutes = minutes;
        this.open = open;
        this.close = close;
        this.take = take;
        db.execute("CREATE TABLE IF NOT EXISTS " + table + " (at TEXT PRIMARY KEY, payload TEXT NOT NULL)");
    }

    /** now(현지 시간) 다음의 장중 기록 시각 (평일 open~close). */
    LocalDateTime nextTick(LocalDateTime now) {
        return nextSlot(now, minutes, open, close);
    }

    /** now(현지 시간) 다음의 장중 minutes분 단위 시각 (평일 open~close). 장중 기록·시그널 알림(Alerts)이 씀 */
    static LocalDateTime nextSlot(LocalDateTime now, int minutes, LocalTime open, LocalTime close) {
        LocalDateTime t = now.truncatedTo(ChronoUnit.MINUTES).withMinute(now.getMinute() - now.getMinute() % minutes).plusMinutes(minutes);
        if (t.toLocalTime().isBefore(open)) {
            t = t.with(open);
        } else if (t.toLocalTime().isAfter(close)) {
            t = t.plusDays(1).with(open);
        }
        while (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) {  // 토·일이면 다음 날 장 시작
            t = t.plusDays(1).with(open);
        }
        return t;
    }

    /** t(현지 시간)가 평일 open~close 안인지 */
    static boolean inSession(LocalDateTime t, LocalTime open, LocalTime close) {
        return t.getDayOfWeek() != DayOfWeek.SATURDAY && t.getDayOfWeek() != DayOfWeek.SUNDAY
                && !t.toLocalTime().isBefore(open) && !t.toLocalTime().isAfter(close);
    }

    /**
     * 장중 기록 한 번. 가진 쪽의 @Scheduled가 minutes분마다 (정각 바로는 KB 시세·일봉이 아직 안 바뀌었을 수 있어 30초 뒤에) 부릅니다.
     * 지금(현지)을 minutes분 단위로 내린 시각이 평일 open~close일 때만 저장. KB 오류여도 다음 시각에 다시.
     */
    void tick() {
        LocalDateTime now = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime at = now.withMinute(now.getMinute() - now.getMinute() % minutes);
        if (!inSession(at, open, close)) {
            return;
        }
        try {
            int n = take(at);
            log.info("[{}] {} {}", name, at.format(AT), n > 0 ? n + "종목 저장" : "휴장 · 저장 안 함");
        } catch (IOException | RuntimeException e) {
            log.warn("[{}] {} 실패: {}: {}", name, at.format(AT), e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** at 시각의 시세를 저장하고 종목 수를 돌려줍니다. 휴장일이면 저장하지 않고 0. */
    int take(LocalDateTime at) throws IOException {
        List<List<Object>> rows = take.rows(at);
        if (!rows.isEmpty()) {
            save(at.format(AT), rows);
        }
        return rows.size();
    }

    void save(String at, List<List<Object>> rows) {
        db.update("INSERT OR REPLACE INTO " + table + " (at, payload) VALUES (?, ?)", at, GSON.toJson(rows));
        db.update("DELETE FROM " + table + " WHERE at < ?", LocalDateTime.parse(at, AT).minusDays(KEEP_DAYS).format(AT));
    }

    /** GET /api/&lt;시장&gt;-intraday?date=2026-10-16&amp;at=10:30 (비우면 마지막 기록). volume.py Ticks.intraday와 같은 JSON. */
    Map<String, Object> intraday(String day, String at) throws IOException {
        try {
            List<String> ats = db.queryForList("SELECT at FROM " + table + " ORDER BY at", String.class);
            TreeSet<String> dateSet = new TreeSet<>(Comparator.reverseOrder());
            ats.forEach(a -> dateSet.add(a.substring(0, 10)));
            List<String> dates = new ArrayList<>(dateSet);
            LocalDateTime next = nextTick(LocalDateTime.now(zone));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dates", dates);
            out.put("nextTick", next.format(AT));
            out.put("utcOffset", offset(next));
            if (ats.isEmpty()) {
                out.put("date", null);
                out.put("at", null);
                out.put("slots", new ArrayList<>());
                out.put("prev", null);
                out.put("week", null);
                out.put("stocks", new ArrayList<>());
                return out;
            }
            String d = dates.contains(day) ? day : dates.get(0);
            List<String> slots = new ArrayList<>();
            ats.stream().filter(a -> a.startsWith(d)).forEach(a -> slots.add(a.substring(11)));
            String t = slots.get(at.isEmpty() ? slots.size() - 1 : 0);
            for (String s : slots) {
                if (!at.isEmpty() && s.compareTo(at) <= 0) {
                    t = s;
                }
            }
            Map<String, String> prev = find(ats, dates, d, t);
            Map<String, String> week = find(ats, dates, LocalDate.parse(d).minusDays(6).toString(), t);  // 7일 전(같은 요일), 휴장이면 그 이전 마지막 기록일
            List<List<Object>> now = load(d + "T" + t);
            Map<Object, Object> prevV = volumes(load(prev == null ? null : prev.get("date") + "T" + prev.get("at")));
            Map<Object, Object> weekV = volumes(load(week == null ? null : week.get("date") + "T" + week.get("at")));
            List<Map<String, Object>> stocks = new ArrayList<>();
            for (List<Object> r : now) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("code", r.get(0));
                s.put("name", r.get(1));
                s.put("price", r.get(2));
                s.put("change", r.get(3));
                s.put("vlm", r.get(4));
                s.put("value", r.get(5));
                long v = Values.lng(r.get(4));
                s.put("prevVlm", prevV.get(r.get(0)));
                s.put("prevRatio", ratio(v, prevV.get(r.get(0))));
                s.put("weekVlm", weekV.get(r.get(0)));
                s.put("weekRatio", ratio(v, weekV.get(r.get(0))));
                stocks.add(s);
            }
            out.put("date", d);
            out.put("at", t);
            out.put("utcOffset", offset(LocalDateTime.parse(d + "T" + t, AT)));
            out.put("slots", slots);
            out.put("prev", prev);
            out.put("week", week);
            out.put("stocks", stocks);
            return out;
        } catch (DataAccessException e) {
            throw new IOException(table + " 저장소 오류: " + e.getMessage(), e);
        }
    }

    /**
     * GET /api/kosdaq-sim : 저장된 기록으로 매수 조건 모듈(buy)이 hold분 이상 이어진 종목을 amount원씩 사고 매도 모듈(sell)로 판 결과
     * (volume.py Ticks.simulate와 같은 규칙·JSON). 모듈은 SimModules (기본: 거래량 minRatio% 이상 + 시가 위 + 상승, 익절 tp%·손절 sl%).
     * 시가: KB 순위 TR(IVU10210)에 시가가 없어 그날 첫 기록(09:00)의 현재가로 봄. 매수: 기록 시각마다(until까지, 그날 마지막 기록 제외)
     * 조건을 연속으로 만족한 첫 기록부터 hold분이 지난 기록의 현재가로 하루 한 번 (중간에 깨지면 처음부터 다시, hold=0이면 바로).
     * 매도: 이후 기록마다 매도 모듈이 이유를 주면 그 시각 현재가, 아니면 그날 마지막 기록. cost%는 매도 금액에서 뺌.
     * 상한가 근처(+29.5% 이상)는 살 수 없다고 보고 뺌. spac이면 스팩 제외.
     * start: 이 시각 전에는 사지 않음 (""면 처음부터). maxHold: 산 뒤 이 분이 지나면 그 시각 현재가로 팜 (0이면 안 함).
     * maxBuys: 하루 최대 매수 종목 수 (0이면 제한 없음, 같은 시각에 여럿이면 거래량 증가율 큰 순).
     * allowed: {날짜: {종목: 시각}}이면 그 종목만 그 시각부터 삼 (알림 종목만, Alerts.firstTimes). null이면 전 종목.
     */
    Map<String, Object> simulate(double minRatio, double amount, double tp, double sl, String until, double cost, boolean spac, int hold,
                                 String buy, String sell, String start, int maxHold, int maxBuys,
                                 Map<String, Map<String, String>> allowed) throws IOException {
        Params p = new Params();
        p.min = minRatio;
        p.amount = amount;
        p.tp = tp;
        p.sl = sl;
        p.until = until;
        p.cost = cost;
        p.spac = spac;
        p.hold = hold;
        p.buy = buy;
        p.sell = sell;
        p.start = start;
        p.maxHold = maxHold;
        p.maxBuys = maxBuys;
        return run(p, allowed, false);
    }

    /**
     * 자동매매용 (Trader 시뮬레이션 전략): 마지막 두 기록일로 같은 규칙을 돌려, 오늘(마지막 기록일)의 마지막 기록 시각에
     * 새로 살 종목과 팔 종목. 장중이라 마지막 기록에서도 사고, '마지막 기록' 매도는 하지 않음 (장마감 매도는 Trader가 함).
     * m: 시뮬레이션 화면 값 {min, amount, tp, sl, until, spac, hold, buy, sell, start, maxHold, maxBuys}
     * -> {date, at, every(기록 간격 분), buy: [{code, name, price}], sell: {종목: 이유}}
     */
    Map<String, Object> live(Map<String, Object> m, Map<String, Map<String, String>> allowed) throws IOException {
        Params p = new Params();
        p.min = Values.num(m.getOrDefault("min", 100));
        p.amount = Values.num(m.get("amount"));
        p.tp = Values.num(m.getOrDefault("tp", 0));
        p.sl = Values.num(m.getOrDefault("sl", 0));
        p.until = Values.str(m.getOrDefault("until", "15:00"));
        p.cost = 0;
        p.spac = !Boolean.FALSE.equals(m.get("spac"));
        p.hold = (int) Values.num(m.getOrDefault("hold", 30));
        p.buy = Values.str(m.getOrDefault("buy", "volume-open-up"));
        p.sell = Values.str(m.getOrDefault("sell", "tp-sl"));
        p.start = Values.str(m.getOrDefault("start", ""));
        p.maxHold = (int) Values.num(m.getOrDefault("maxHold", 0));
        p.maxBuys = (int) Values.num(m.getOrDefault("maxBuys", 0));
        return run(p, allowed, true);
    }

    // ------------------------------------------------------------------ 시뮬레이션 엔진 (C처럼: 구조체 + 함수)

    /** 시뮬레이션 화면 값 (struct sim_params) */
    static final class Params {
        double min;        // 거래량 기준 % (매수 조건의 "min")
        double amount;     // 종목당 매수 금액
        double tp;         // 익절 % (0이면 안 함)
        double sl;         // 손절 %
        String until;      // 이 시각 뒤에는 사지 않음 "15:00"
        String start;      // 이 시각 전에는 사지 않음 ("" = 처음부터)
        double cost;       // 매도 금액에서 뺄 비용 %
        boolean spac;      // 스팩 제외
        int hold;          // 조건이 이만큼(분) 이어져야 삼
        int maxHold;       // 산 뒤 이 분이 지나면 팜 (0이면 안 함)
        int maxBuys;       // 하루 최대 매수 종목 수 (0이면 제한 없음)
        String buy;        // 매수 조건 이름 (SimModules.Rule id)
        String sell;       // 매도 모듈 이름 (SimModules.SELL_ID)
    }

    /** 기록 한 행 = 한 시각의 한 종목 (payload [code, name, price, change, vol, value]) */
    static final class Row {
        Object code;       // 저장된 그대로 (화면 JSON에 그대로)
        Object name;
        double price;      // 현재가
        double change;     // 전일 대비 %
        double vol;        // 그 시각까지 누적 거래량
        double value;      // 그 시각까지 누적 거래대금 (없으면 0)
    }

    /** 거래 하나 (struct trade). 매도 모듈이 peak를 남길 수 있음 */
    static final class Trade {
        String date;
        Object code;
        Object name;
        String since;      // 조건을 연속으로 만족하기 시작한 시각
        double open;       // 시가 (그날 첫 기록 현재가)
        String buyAt;
        double buyPrice;
        long qty;
        double ratio;      // 산 시각의 거래량 증가 %
        double change;
        String prevAt;     // 비교한 전날 기록 "2026-10-21 10:00"
        double peak;       // trailing: 산 뒤 최고가 (0 = 아직 없음)
        String sellAt;     // null = 아직 보유
        double sellPrice;
        String reason;
        long invested;
        long pnl;
        double ret;
    }

    /** 이 시각에 살 차례가 된 종목 */
    private static final class Ready {
        double ratio;
        double prevVol;    // 전날 같은 시각의 누적 거래량
        Row row;
        String began;
    }

    private static double num(Object v) {
        return v == null || "".equals(v) ? 0 : Values.num(v);
    }

    private static int mins(String hm) {
        return Integer.parseInt(hm.substring(0, 2)) * 60 + Integer.parseInt(hm.substring(3));
    }

    /** at 기록을 종목 -> Row로 (cache: 하루 앞까지만 들고 있음) */
    private Map<Object, Row> byCode(Map<String, Map<Object, Row>> cache, String at) {
        Map<Object, Row> m = cache.get(at);
        if (m != null) {
            return m;
        }
        m = new LinkedHashMap<>();
        for (List<Object> r : load(at)) {
            Row x = new Row();
            x.code = r.get(0);
            x.name = r.get(1);
            x.price = num(r.get(2));
            x.change = num(r.get(3));
            x.vol = num(r.get(4));
            x.value = r.size() > 5 ? num(r.get(5)) : 0;
            m.put(x.code, x);
        }
        cache.put(at, m);
        return m;
    }

    private static double base(Row r) {
        return r.price / (1 + r.change / 100);
    }

    /**
     * 현재가·등락률로 역산한 전일 종가가 그날 중앙값과 2% 넘게 다른 행은 잘못 받은 값으로 보고 뺌
     * (KB가 가끔 자릿수가 빠진 현재가를 줌: 6220 → 709 → 6510, 등락률은 그대로). volume.py Ticks.simulate clean과 같음.
     */
    private void clean(Map<String, Map<Object, Row>> cache, String day, List<String> times) {
        Map<Object, List<Double>> bases = new HashMap<>();
        for (String t : times) {
            for (Row r : byCode(cache, day + "T" + t).values()) {
                if (r.price > 0) {
                    bases.computeIfAbsent(r.code, k -> new ArrayList<>()).add(base(r));
                }
            }
        }
        Map<Object, Double> mid = new HashMap<>();
        for (Map.Entry<Object, List<Double>> e : bases.entrySet()) {
            List<Double> v = e.getValue();
            java.util.Collections.sort(v);
            int n = v.size();
            mid.put(e.getKey(), n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2);
        }
        for (String t : times) {
            java.util.Iterator<Row> it = byCode(cache, day + "T" + t).values().iterator();
            while (it.hasNext()) {
                Row r = it.next();
                if (r.price > 0 && Math.abs(base(r) / mid.get(r.code) - 1) > 0.02) {
                    it.remove();
                }
            }
        }
    }

    /**
     * 엔진: 하루씩 그날 기록을 시각순으로 훑으며 ① 들고 있는 것 매도 ② 매수 가능 시각이면 종목마다 조건 검사 ③ hold분 이어진 종목 매수.
     * 거래량 비교 기준은 전날의 같은 시각 이전 마지막 기록. live면 오늘 하루만 돌리고 마지막 기록 시각의 새 매수·매도만 돌려줌.
     */
    private Map<String, Object> run(Params p, Map<String, Map<String, String>> allowed, boolean live) throws IOException {
        if (p.amount <= 0 || p.hold < 0 || p.maxHold < 0 || p.maxBuys < 0 || p.until.length() != 5 || p.until.charAt(2) != ':'
                || !p.start.isEmpty() && (p.start.length() != 5 || p.start.charAt(2) != ':')) {
            throw new IllegalArgumentException("매수 금액·시각·유지 시간이 올바르지 않습니다");
        }
        SimModules.Rule rule = SimModules.buy(p.buy);
        SimModules.Sell sell = SimModules.sell(p.sell);
        List<Trade> trades = new ArrayList<>();
        List<String> days;
        String liveAt = null;  // live: 오늘 마지막 기록 시각
        try {
            Map<String, List<String>> slots = new LinkedHashMap<>();  // 날짜 -> 그날 기록 시각들
            for (String a : db.queryForList("SELECT at FROM " + table + " ORDER BY at", String.class)) {
                slots.computeIfAbsent(a.substring(0, 10), k -> new ArrayList<>()).add(a.substring(11));
            }
            days = new ArrayList<>(slots.keySet());
            Map<String, Map<Object, Row>> cache = new HashMap<>();  // 하루 앞까지만 들고 있음 (14일 전부 올리면 수백 MB)

            for (int di = live ? Math.max(1, days.size() - 1) : 1; di < days.size(); di++) {
                String prevDay = days.get(di - 1);
                String d = days.get(di);
                cache.keySet().removeIf(k -> k.substring(0, 10).compareTo(prevDay) < 0);
                List<String> today = slots.get(d);
                clean(cache, d, today);

                List<Trade> held = new ArrayList<>();            // 지금 들고 있는 거래
                java.util.Set<Object> bought = new java.util.HashSet<>();  // 오늘 이미 산 종목 (하루 한 번)
                Map<Object, String> since = new HashMap<>();     // 종목 -> 조건을 연속으로 만족하기 시작한 시각
                Map<Object, Double> opens = new HashMap<>();     // 종목 -> 시가 (첫 기록 현재가)
                for (Row r : byCode(cache, d + "T" + today.get(0)).values()) {
                    opens.put(r.code, r.price);
                }
                Map<String, String> allowedToday = allowed == null ? null : allowed.getOrDefault(d, Map.of());

                for (int i = 0; i < today.size(); i++) {
                    String t = today.get(i);
                    Map<Object, Row> now = byCode(cache, d + "T" + t);

                    // ① 매도: 매도 모듈이 이유를 주거나, 보유 시간(maxHold)이 지나면 그 시각 현재가로
                    for (int h = held.size() - 1; h >= 0; h--) {
                        Trade tr = held.get(h);
                        Row r = now.get(tr.code);
                        double px = r == null ? 0 : r.price;
                        if (px <= 0) {
                            continue;
                        }
                        String why = sell.check(tr, px, p.tp, p.sl);
                        if (why == null && p.maxHold > 0 && mins(t) - mins(tr.buyAt) >= p.maxHold) {
                            why = "보유 시간";
                        }
                        if (why != null) {
                            tr.sellAt = t;
                            tr.sellPrice = px;
                            tr.reason = why;
                            held.remove(h);
                        }
                    }

                    // ② 매수할 수 있는 시각인가: until까지, start부터, 전날 비교 기록이 있고, (simulate는) 그날 마지막 기록이 아님
                    String before = null;  // 전날의 t 이전 마지막 기록 시각
                    for (String s : slots.get(prevDay)) {
                        if (s.compareTo(t) <= 0) {
                            before = s;
                        }
                    }
                    if (t.compareTo(p.until) > 0 || t.compareTo(p.start) < 0 || !live && i == today.size() - 1 || before == null) {
                        continue;
                    }
                    Map<Object, Row> prev = byCode(cache, prevDay + "T" + before);

                    // ③ 종목마다 조건 검사 -> hold분 이어지면 살 차례
                    List<Ready> ready = new ArrayList<>();
                    double[] s = new double[SimModules.NFIELD];
                    for (Row r : now.values()) {
                        Row pr = prev.get(r.code);
                        double pv = pr == null ? 0 : pr.vol;
                        if (bought.contains(r.code) || pv <= 0 || r.price <= 0 || r.change >= 29.5     // 상한가 근처는 못 산다고 봄
                                || p.spac && Values.str(r.name).contains("스팩") || p.amount < r.price     // 1주도 못 삼
                                || allowedToday != null && t.compareTo(allowedToday.getOrDefault(Values.str(r.code), "99:99")) < 0) {  // 알림 시각 전
                            since.remove(r.code);
                            continue;
                        }
                        s[SimModules.PRICE] = r.price;
                        s[SimModules.CHANGE] = r.change;
                        s[SimModules.RATIO] = (r.vol / pv - 1) * 100;
                        s[SimModules.OPEN] = opens.getOrDefault(r.code, r.price);
                        s[SimModules.VALUE] = r.value;
                        s[SimModules.VWAP] = r.vol > 0 && r.value > 0 ? r.value / r.vol : r.price;  // 거래대금 없으면 현재가 (VWAP 위로 안 봄)
                        if (!SimModules.test(rule, s, p.min)) {
                            since.remove(r.code);  // 조건이 깨지면 처음부터 다시
                            continue;
                        }
                        String began = since.computeIfAbsent(r.code, k -> t);
                        if (mins(t) - mins(began) < p.hold) {
                            continue;
                        }
                        Ready x = new Ready();
                        x.ratio = s[SimModules.RATIO];
                        x.prevVol = pv;
                        x.row = r;
                        x.began = began;
                        int at = ready.size();  // 삽입 정렬: 증가율 큰 순, 같으면 먼저 온 순
                        while (at > 0 && ready.get(at - 1).ratio < x.ratio) {
                            at--;
                        }
                        ready.add(at, x);
                    }

                    // ④ 매수 (하루 최대 종목 수까지)
                    for (Ready x : ready) {
                        if (p.maxBuys > 0 && bought.size() >= p.maxBuys) {
                            break;
                        }
                        Row r = x.row;
                        bought.add(r.code);
                        Trade tr = new Trade();
                        tr.date = d;
                        tr.code = r.code;
                        tr.name = r.name;
                        tr.since = x.began;
                        tr.open = opens.getOrDefault(r.code, r.price);  // 첫 기록에 없던 종목은 지금 값
                        tr.buyAt = t;
                        tr.buyPrice = r.price;
                        tr.qty = (long) Math.floor(p.amount / r.price);
                        tr.ratio = Math.round((r.vol / x.prevVol - 1) * 1000) / 10.0;
                        tr.change = r.change;
                        tr.prevAt = prevDay + " " + before;
                        held.add(tr);
                        trades.add(tr);
                    }
                }

                String lastAt = today.get(today.size() - 1);
                if (live) {
                    liveAt = lastAt;
                    continue;
                }
                // ⑤ 그날 끝까지 못 판 것은 마지막 기록에 팜 (값이 없으면 산 값)
                Map<Object, Row> last = byCode(cache, d + "T" + lastAt);
                for (Trade tr : held) {
                    Row r = last.get(tr.code);
                    tr.sellAt = lastAt;
                    tr.sellPrice = r != null ? r.price : tr.buyPrice;
                    tr.reason = "마지막 기록";
                }
            }
        } catch (DataAccessException e) {
            throw new IOException(table + " 저장소 오류: " + e.getMessage(), e);
        }
        if (live) {
            return liveSignal(trades, liveAt, liveAt == null ? null : days.get(days.size() - 1));
        }
        for (Trade tr : trades) {
            tr.invested = Math.round(tr.qty * tr.buyPrice);
            tr.pnl = Math.round(tr.qty * tr.sellPrice * (1 - p.cost / 100) - tr.invested);
            tr.ret = Math.round((double) tr.pnl / tr.invested * 10000) / 100.0;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", days.size() > 1 ? days.get(1) : null);
        out.put("to", days.size() > 1 ? days.get(days.size() - 1) : null);
        out.put("total", summary(trades, null));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (int i = Math.min(1, days.size()); i < days.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", days.get(i));
            m.putAll(summary(trades, days.get(i)));
            perDay.add(m);
        }
        out.put("days", perDay);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Trade tr : trades) {
            ts.add(toMap(tr));
        }
        out.put("trades", ts);
        return out;
    }

    /** live 결과: 오늘 마지막 기록 시각(at)에 새로 산 종목과 판 종목 */
    private Map<String, Object> liveSignal(List<Trade> trades, String at, String date) {
        List<Map<String, Object>> buys = new ArrayList<>();
        Map<String, String> sells = new LinkedHashMap<>();
        for (Trade tr : trades) {
            if (at.equals(tr.buyAt)) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("code", Values.str(tr.code));
                b.put("name", Values.str(tr.name));
                b.put("price", tr.buyPrice);
                buys.add(b);
            }
            if (at.equals(tr.sellAt)) {
                sells.put(Values.str(tr.code), tr.reason);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("at", at);
        out.put("every", minutes);
        out.put("buy", buys);
        out.put("sell", sells);
        return out;
    }

    /** 화면 JSON의 거래 한 건 (예전과 같은 키 순서) */
    private static Map<String, Object> toMap(Trade tr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", tr.date);
        m.put("code", tr.code);
        m.put("name", tr.name);
        m.put("since", tr.since);
        m.put("open", tr.open);
        m.put("buyAt", tr.buyAt);
        m.put("buyPrice", tr.buyPrice);
        m.put("qty", tr.qty);
        m.put("ratio", tr.ratio);
        m.put("change", tr.change);
        m.put("prevAt", tr.prevAt);
        if (tr.peak > 0) {
            m.put("peak", tr.peak);
        }
        m.put("sellAt", tr.sellAt);
        m.put("sellPrice", tr.sellPrice);
        m.put("reason", tr.reason);
        m.put("invested", tr.invested);
        m.put("pnl", tr.pnl);
        m.put("ret", tr.ret);
        return m;
    }

    /** day의 거래 합계 (day가 null이면 전체) */
    private static Map<String, Object> summary(List<Trade> trades, String day) {
        long n = 0;
        long inv = 0;
        long pnl = 0;
        long wins = 0;
        for (Trade t : trades) {
            if (day != null && !day.equals(t.date)) {
                continue;
            }
            n++;
            inv += t.invested;
            pnl += t.pnl;
            wins += t.pnl > 0 ? 1 : 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", (int) n);
        m.put("wins", wins);
        m.put("invested", inv);
        m.put("pnl", pnl);
        m.put("ret", inv > 0 ? (Object) (Math.round((double) pnl / inv * 10000) / 100.0) : null);
        return m;
    }

    /** 그 현지 시각의 UTC 오프셋(분). 화면이 한국 시간을 함께 보여 줄 때 씁니다 (뉴욕은 서머타임에 따라 -240/-300). */
    private int offset(LocalDateTime local) {
        return local.atZone(zone).getOffset().getTotalSeconds() / 60;
    }

    /** before 이전 마지막 기록일의, at 이전 마지막 기록. 없으면 null. */
    private static Map<String, String> find(List<String> ats, List<String> dates, String before, String at) {
        String day = dates.stream().filter(x -> x.compareTo(before) < 0).findFirst().orElse(null);  // dates는 최신순
        String hit = null;
        for (String a : ats) {
            if (day != null && a.startsWith(day) && a.substring(11).compareTo(at) <= 0) {
                hit = a;
            }
        }
        if (hit == null) {
            return null;
        }
        Map<String, String> m = new LinkedHashMap<>();
        m.put("date", day);
        m.put("at", hit.substring(11));
        return m;
    }

    /** 마지막 기록 {at: 'yyyy-MM-ddTHH:mm', rows} (기록이 없으면 at null, rows 빈 목록). */
    Map<String, Object> last() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> r = db.queryForList("SELECT at, payload FROM " + table + " ORDER BY at DESC LIMIT 1");
            out.put("at", r.isEmpty() ? null : r.get(0).get("at"));
            out.put("rows", r.isEmpty() ? List.of() : GSON.fromJson((String) r.get(0).get("payload"), ROWS));
        } catch (DataAccessException e) {
            throw new IOException(table + " 저장소 오류: " + e.getMessage(), e);
        }
        return out;
    }

    private List<List<Object>> load(String at) {
        if (at == null) {
            return new ArrayList<>();
        }
        List<String> r = db.queryForList("SELECT payload FROM " + table + " WHERE at = ?", String.class, at);
        return r.isEmpty() ? new ArrayList<>() : GSON.fromJson(r.get(0), ROWS);
    }

    private static Map<Object, Object> volumes(List<List<Object>> rows) {
        Map<Object, Object> m = new HashMap<>();
        rows.forEach(r -> m.put(r.get(0), r.get(4)));
        return m;
    }

    private static Double ratio(long v, Object before) {
        long b = before == null ? 0 : Values.lng(before);
        return b > 0 ? Math.round(((double) v / b - 1) * 10000) / 100.0 : null;
    }
}
