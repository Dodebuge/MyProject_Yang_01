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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 장중 N분마다 시장 전 종목의 누적 거래량·현재가를 SQLite에 저장하고, 고른 시각의 누적 거래량을
 * 전일·전주(7일 전 이전의 마지막 거래일) 같은 시각과 비교합니다 (volume.py의 Ticks와 같은 파일·같은 테이블).
 * 같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록을 씁니다. 시각은 그 시장의 현지 시간입니다 (코스닥 한국, 러셀 2000 뉴욕).
 */
public final class Ticks {

    /** at(현지 시간) 시각의 [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...]. 휴장이면 빈 목록. */
    public interface Take {
        List<List<Object>> rows(LocalDateTime at) throws IOException;
    }

    private static final int KEEP_DAYS = 14;       // 전주 비교에 7일 + 긴 연휴 여유
    private static final int DELAY_SECONDS = 30;   // 정각 바로는 KB 시세·일봉이 아직 안 바뀌었을 수 있어 조금 뒤에 조회
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type ROWS = new TypeToken<List<List<Object>>>() { }.getType();

    private final String name;
    private final Path db;
    private final String table;
    private final ZoneId zone;
    private final int minutes;
    private final LocalTime open;
    private final LocalTime close;
    private final Take take;
    private ScheduledExecutorService timer;

    Ticks(String name, Path db, String table, ZoneId zone, int minutes, LocalTime open, LocalTime close, Take take) {
        this.name = name;
        this.db = db;
        this.table = table;
        this.zone = zone;
        this.minutes = minutes;
        this.open = open;
        this.close = close;
        this.take = take;
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

    /** 장중 기록 시작. */
    synchronized void start() {
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, table);
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
        System.out.println("[" + name + "] 장중 " + minutes + "분마다 거래량 기록 -> " + db.getFileName()
                + " (다음 현지 " + nextTick(LocalDateTime.now(zone)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + ")");
    }

    synchronized void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    // 실행할 때마다 다음 시각을 다시 계산해 예약 (고정 주기로 두면 PC 절전·시계 보정 뒤 시각이 밀림)
    private synchronized void scheduleNext() {
        if (timer.isShutdown()) {
            return;
        }
        LocalDateTime at = nextTick(LocalDateTime.now(zone));
        // ZonedDateTime끼리 비교: 서머타임이 바뀌는 주말을 지나도 실제 시간 차이로 기다림
        long delay = Math.max(0, Duration.between(ZonedDateTime.now(zone), at.atZone(zone)).toMillis()) + DELAY_SECONDS * 1000L;
        timer.schedule(() -> {
            try {
                int n = take(at);
                System.out.println("[" + name + "] " + at.format(AT) + " " + (n > 0 ? n + "종목 저장" : "휴장 · 저장 안 함"));
            } catch (IOException | SQLException | RuntimeException e) {  // KB 오류여도 다음 시각에 다시
                System.out.println("[" + name + "] " + at.format(AT) + " 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                scheduleNext();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** at 시각의 시세를 저장하고 종목 수를 돌려줍니다. 휴장일이면 저장하지 않고 0. */
    int take(LocalDateTime at) throws IOException, SQLException {
        List<List<Object>> rows = take.rows(at);
        if (!rows.isEmpty()) {
            save(at.format(AT), rows);
        }
        return rows.size();
    }

    void save(String at, List<List<Object>> rows) throws SQLException {
        String oldest = LocalDateTime.parse(at, AT).minusDays(KEEP_DAYS).format(AT);
        try (Connection c = connect();
             PreparedStatement put = c.prepareStatement("INSERT OR REPLACE INTO " + table + " (at, payload) VALUES (?, ?)");
             PreparedStatement prune = c.prepareStatement("DELETE FROM " + table + " WHERE at < ?")) {
            put.setString(1, at);
            put.setString(2, GSON.toJson(rows));
            put.executeUpdate();
            prune.setString(1, oldest);
            prune.executeUpdate();
        }
    }

    /** GET /api/&lt;시장&gt;-intraday?date=2026-10-16&amp;at=10:30 (비우면 마지막 기록). volume.py Ticks.intraday와 같은 JSON. */
    Map<String, Object> intraday(String day, String at) throws IOException {
        try (Connection c = connect()) {
            List<String> ats = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT at FROM " + table + " ORDER BY at")) {
                while (rs.next()) {
                    ats.add(rs.getString(1));
                }
            }
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
            List<List<Object>> now = load(c, d + "T" + t);
            Map<Object, Object> prevV = volumes(load(c, prev == null ? null : prev.get("date") + "T" + prev.get("at")));
            Map<Object, Object> weekV = volumes(load(c, week == null ? null : week.get("date") + "T" + week.get("at")));
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
        } catch (SQLException e) {
            throw new IOException(db.getFileName() + " 저장소 오류: " + e.getMessage(), e);
        }
    }

    /**
     * GET /api/kosdaq-sim : 저장된 기록으로 '같은 시각 전일 대비 거래량 minRatio% 이상 + 주가 상승' 종목을 amount원씩 사고 판 결과
     * (volume.py Ticks.simulate와 같은 규칙·JSON). 매수: 기록 시각마다(until까지, 그날 마지막 기록 제외) 처음 만족한 종목을 그 시각 현재가로 하루 한 번.
     * 매도: 이후 기록에서 익절 tp%·손절 sl%(0이면 안 함)에 닿으면 그 시각 현재가, 아니면 그날 마지막 기록. cost%는 매도 금액에서 뺌.
     * 상한가 근처(+29.5% 이상)는 살 수 없다고 보고 뺌. spac이면 스팩 제외.
     */
    Map<String, Object> simulate(double minRatio, double amount, double tp, double sl, String until, double cost, boolean spac) throws IOException {
        if (amount <= 0 || until.length() != 5 || until.charAt(2) != ':') {
            throw new IllegalArgumentException("매수 금액·시각이 올바르지 않습니다");
        }
        List<Map<String, Object>> trades = new ArrayList<>();
        List<String> days;
        try (Connection c = connect()) {
            Map<String, List<String>> slots = new LinkedHashMap<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT at FROM " + table + " ORDER BY at")) {
                while (rs.next()) {
                    String a = rs.getString(1);
                    slots.computeIfAbsent(a.substring(0, 10), k -> new ArrayList<>()).add(a.substring(11));
                }
            }
            days = new ArrayList<>(slots.keySet());
            Map<String, Map<Object, List<Object>>> cache = new HashMap<>();  // 하루 앞까지만 들고 있음 (14일 전부 올리면 수백 MB)
            for (int di = 1; di < days.size(); di++) {
                String p = days.get(di - 1);
                String d = days.get(di);
                cache.keySet().removeIf(k -> k.substring(0, 10).compareTo(p) < 0);
                List<String> today = slots.get(d);
                Map<Object, Map<String, Object>> held = new LinkedHashMap<>();
                java.util.Set<Object> bought = new java.util.HashSet<>();
                for (int i = 0; i < today.size(); i++) {
                    String t = today.get(i);
                    Map<Object, List<Object>> now = byCode(c, cache, d + "T" + t);
                    for (java.util.Iterator<Map.Entry<Object, Map<String, Object>>> it = held.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<Object, Map<String, Object>> e = it.next();
                        double px = now.containsKey(e.getKey()) ? num(now.get(e.getKey()).get(2)) : 0;
                        double buy = (double) e.getValue().get("buyPrice");
                        String hit = tp > 0 && px >= buy * (1 + tp / 100) ? "익절" : sl > 0 && px > 0 && px <= buy * (1 - sl / 100) ? "손절" : null;
                        if (hit != null) {
                            e.getValue().put("sellAt", t);
                            e.getValue().put("sellPrice", px);
                            e.getValue().put("reason", hit);
                            it.remove();
                        }
                    }
                    String before = null;
                    for (String s : slots.get(p)) {
                        if (s.compareTo(t) <= 0) {
                            before = s;
                        }
                    }
                    if (t.compareTo(until) > 0 || i == today.size() - 1 || before == null) {
                        continue;
                    }
                    Map<Object, List<Object>> prev = byCode(c, cache, p + "T" + before);
                    for (List<Object> r : now.values()) {
                        double px = num(r.get(2));
                        double pv = prev.containsKey(r.get(0)) ? num(prev.get(r.get(0)).get(4)) : 0;
                        double ch = num(r.get(3));
                        if (bought.contains(r.get(0)) || pv <= 0 || px <= 0 || !(ch > 0 && ch < 29.5) || spac && Values.str(r.get(1)).contains("스팩")
                                || (num(r.get(4)) / pv - 1) * 100 < minRatio || amount < px) {
                            continue;
                        }
                        bought.add(r.get(0));
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("date", d);
                        tr.put("code", r.get(0));
                        tr.put("name", r.get(1));
                        tr.put("buyAt", t);
                        tr.put("buyPrice", px);
                        tr.put("qty", (long) Math.floor(amount / px));
                        tr.put("ratio", Math.round((num(r.get(4)) / pv - 1) * 1000) / 10.0);
                        tr.put("change", ch);
                        tr.put("prevAt", p + " " + before);
                        held.put(r.get(0), tr);
                        trades.add(tr);
                    }
                }
                String lastAt = today.get(today.size() - 1);
                Map<Object, List<Object>> last = byCode(c, cache, d + "T" + lastAt);
                for (Map.Entry<Object, Map<String, Object>> e : held.entrySet()) {  // 익절·손절 없으면 그날 마지막 기록에 팜 (값이 없으면 산 값)
                    e.getValue().put("sellAt", lastAt);
                    e.getValue().put("sellPrice", last.containsKey(e.getKey()) ? num(last.get(e.getKey()).get(2)) : e.getValue().get("buyPrice"));
                    e.getValue().put("reason", "마지막 기록");
                }
            }
        } catch (SQLException e) {
            throw new IOException(db.getFileName() + " 저장소 오류: " + e.getMessage(), e);
        }
        for (Map<String, Object> tr : trades) {
            long qty = (long) tr.get("qty");
            long invested = Math.round(qty * (double) tr.get("buyPrice"));
            long pnl = Math.round(qty * (double) tr.get("sellPrice") * (1 - cost / 100) - invested);
            tr.put("invested", invested);
            tr.put("pnl", pnl);
            tr.put("ret", Math.round((double) pnl / invested * 10000) / 100.0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", days.size() > 1 ? days.get(1) : null);
        out.put("to", days.size() > 1 ? days.get(days.size() - 1) : null);
        out.put("total", summary(trades));
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (String d : days.subList(Math.min(1, days.size()), days.size())) {
            List<Map<String, Object>> ts = new ArrayList<>();
            trades.stream().filter(x -> d.equals(x.get("date"))).forEach(ts::add);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d);
            m.putAll(summary(ts));
            perDay.add(m);
        }
        out.put("days", perDay);
        out.put("trades", trades);
        return out;
    }

    private static Map<String, Object> summary(List<Map<String, Object>> ts) {
        long inv = 0;
        long pnl = 0;
        long wins = 0;
        for (Map<String, Object> t : ts) {
            inv += (long) t.get("invested");
            pnl += (long) t.get("pnl");
            wins += (long) t.get("pnl") > 0 ? 1 : 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", ts.size());
        m.put("wins", wins);
        m.put("invested", inv);
        m.put("pnl", pnl);
        m.put("ret", inv > 0 ? (Object) (Math.round((double) pnl / inv * 10000) / 100.0) : null);
        return m;
    }

    private Map<Object, List<Object>> byCode(Connection c, Map<String, Map<Object, List<Object>>> cache, String at) throws SQLException {
        Map<Object, List<Object>> m = cache.get(at);
        if (m == null) {
            m = new LinkedHashMap<>();
            for (List<Object> r : load(c, at)) {
                m.put(r.get(0), r);
            }
            cache.put(at, m);
        }
        return m;
    }

    private static double num(Object v) {
        return v == null || "".equals(v) ? 0 : Values.num(v);
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

    private List<List<Object>> load(Connection c, String at) throws SQLException {
        if (at == null) {
            return new ArrayList<>();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT payload FROM " + table + " WHERE at = ?")) {
            ps.setString(1, at);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? GSON.fromJson(rs.getString(1), ROWS) : new ArrayList<>();
            }
        }
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

    private Connection connect() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");  // Tomcat은 WEB-INF/lib의 JDBC 드라이버를 자동 등록하지 않을 수 있음
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc가 classpath에 없습니다", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout = 30000");
            st.execute("CREATE TABLE IF NOT EXISTS " + table + " (at TEXT PRIMARY KEY, payload TEXT NOT NULL)");
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }
}
