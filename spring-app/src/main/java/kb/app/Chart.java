package kb.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 종목 검색 + 일봉 차트 (chart.py와 같은 계산·같은 JSON).
 * 검색: 국내는 전체 상장 종목(IVS10920)의 이름·종목번호로, 미국은 티커로 찾습니다.
 *      미국 종목 목록 TR이 없어 티커 모양이면 US_STOCKS에 없어도 후보로 내고 차트 조회 때 거래소를 찾습니다.
 * 차트: 국내 IVS11560, 미국 GSC10060 일봉 최근 약 1년(250거래일).
 * 최근 검색: 차트를 연 종목을 recent_searches.txt에 최신순으로 RECENT_MAX개까지 저장합니다 (chart.py와 같은 파일 형식).
 *      한 줄에 한 종목 "market<탭>code<탭>exchange<탭>name". 직접 고치거나 지워도 됩니다 (형식이 맞지 않는 줄은 무시).
 */
@Service
class Chart {

    private static final long LIST_CACHE_MS = 3_600_000;  // 상장 종목 목록은 하루에 거의 안 바뀜
    static final String CHART_DAYS = "250";
    static final String MINUTE_BARS = "800";  // 분봉 개수 (국내 1분봉은 시간외 포함 약 하루, 5분봉은 약 5일)
    private static final List<String> US_EXCHANGES = Arrays.asList("NAS", "NYS", "AMX");  // 나스닥, 뉴욕, 아멕스(NYSE Arca ETF 포함)
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z.]{0,5}");
    private static final Pattern KR_CODE = Pattern.compile("[0-9A-Z]{6}");
    static final int RECENT_MAX = 10;  // 최근 검색 종목 수
    private static final String[] RECENT_KEYS = {"market", "code", "exchange", "name"};

    private final KBClient client;
    private volatile long krAt;
    private volatile List<Map<String, Object>> kr = Collections.emptyList();
    private final Path recentFile;

    Chart(KBClient client, Path recentFile) {
        this.client = client;
        this.recentFile = recentFile;
    }

    @Autowired
    Chart(KBClient client, KbProperties props) {
        this(client, props.data("recent_searches.txt"));  // 최근 검색 종목 10개
    }

    // ------------------------------------------------------------------ 차트 설정 (chart.py settings/save_settings와 같은 파일·같은 규칙)

    private static final List<String> PERIODS = Arrays.asList("1M", "3M", "6M", "1Y");
    private static final List<String> FLAGS = Arrays.asList("ma5", "ma20", "ma60", "ma120", "bb", "rsi", "macd", "ema5", "ema9", "ema20");
    private static final List<String> INTERVALS = Arrays.asList("D", "1", "3", "5");  // 일봉, 1·3·5분봉
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static Map<String, Object> defaultSettings() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("period", "6M");
        d.put("ma5", true);
        d.put("ma20", true);
        d.put("ma60", true);
        d.put("ma120", false);
        d.put("bb", false);
        d.put("rsi", false);
        d.put("macd", false);
        d.put("interval", "D");
        d.put("ema5", true);
        d.put("ema9", true);
        d.put("ema20", true);
        return d;
    }

    /** base에 d의 올바른 값만 덮어씁니다 (모르는 키·잘못된 값은 버림). */
    static Map<String, Object> validSettings(Map<String, Object> base, JsonObject d) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        JsonElement p = d.get("period");
        if (p != null && p.isJsonPrimitive() && PERIODS.contains(p.getAsString())) {
            out.put("period", p.getAsString());
        }
        JsonElement iv = d.get("interval");
        if (iv != null && iv.isJsonPrimitive() && INTERVALS.contains(iv.getAsString())) {
            out.put("interval", iv.getAsString());
        }
        for (String k : FLAGS) {
            JsonElement v = d.get(k);
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                out.put(k, v.getAsBoolean());
            }
        }
        return out;
    }

    private Path settingsFile() {
        return recentFile.resolveSibling("chart_settings.json");
    }

    /** GET /api/settings : 저장된 차트 설정. 파일이 없거나 깨졌으면 기본값. */
    Map<String, Object> settings() throws IOException {
        String text;
        try {
            text = new String(Files.readAllBytes(settingsFile()), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return defaultSettings();
        }
        try {
            JsonElement saved = JsonParser.parseString(text);
            return validSettings(defaultSettings(), saved.isJsonObject() ? saved.getAsJsonObject() : new JsonObject());
        } catch (JsonParseException e) {
            return defaultSettings();
        }
    }

    /** POST /api/settings : 바꾼 설정을 검사해 저장하고, 저장된 값을 돌려줍니다. */
    synchronized Map<String, Object> saveSettings(String body) throws IOException {
        JsonElement d;
        try {
            d = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("설정이 올바른 JSON이 아닙니다.");
        }
        if (d == null || !d.isJsonObject()) {
            throw new IllegalArgumentException("설정은 JSON 객체여야 합니다.");
        }
        Map<String, Object> out = validSettings(settings(), d.getAsJsonObject());
        Path tmp = settingsFile().resolveSibling("chart_settings.json.tmp");
        Files.write(tmp, (GSON.toJson(out) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, settingsFile(), StandardCopyOption.REPLACE_EXISTING);  // 반쯤 쓴 파일을 읽지 않게 통째로 교체
        return out;
    }

    // ------------------------------------------------------------------ 모니터 종목 (chart.py monitor_items/save_monitor_items와 같은 파일·같은 규칙)
    // 시장 모니터(/monitor)의 + 칸으로 추가한 종목 [{market, code, name, exchange, etf}]. POST는 목록 전체를 바꿉니다.

    static final int MONITOR_MAX = 30;

    /** 올바른 종목만, 중복 없이 MONITOR_MAX개까지. 이름·부제는 공백을 정리해 40자로 자름. */
    static List<Map<String, Object>> validItems(JsonElement items) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (items == null || !items.isJsonArray()) {
            return out;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement e : items.getAsJsonArray()) {
            if (out.size() >= MONITOR_MAX) {
                break;
            }
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject x = e.getAsJsonObject();
            String m = text(x, "market"), code = text(x, "code").toUpperCase(Locale.ROOT), ex = text(x, "exchange");
            boolean ok = "kr".equals(m) && KR_CODE.matcher(code).matches() && ex.isEmpty()
                    || "us".equals(m) && TICKER.matcher(code).matches() && (ex.isEmpty() || US_EXCHANGES.contains(ex));
            if (!ok || !seen.add(m + ":" + code)) {
                continue;
            }
            String name = clip(text(x, "name"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("market", m);
            item.put("code", code);
            item.put("name", name.isEmpty() ? code : name);
            item.put("exchange", ex);
            item.put("etf", clip(text(x, "etf")));
            out.add(item);
        }
        return out;
    }

    private static String text(JsonObject x, String k) {
        JsonElement v = x.get(k);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
    }

    private static String clip(String s) {  // 공백(탭·줄바꿈 포함)을 한 칸으로, 40자까지
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    private Path monitorFile() {
        return recentFile.resolveSibling("monitor_items.json");
    }

    /** GET /api/monitor : 저장된 모니터 종목. 파일이 없거나 깨졌으면 빈 목록. */
    Map<String, Object> monitorItems() throws IOException {
        JsonElement saved = null;
        try {
            saved = JsonParser.parseString(new String(Files.readAllBytes(monitorFile()), StandardCharsets.UTF_8));
        } catch (NoSuchFileException | JsonParseException e) {
            // 빈 목록
        }
        return Collections.singletonMap("items", validItems(saved));
    }

    /** POST /api/monitor {"items": [...]} : 검사해 통째로 저장하고 저장된 목록을 돌려줍니다. */
    synchronized Map<String, Object> saveMonitorItems(String body) throws IOException {
        JsonElement d;
        try {
            d = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("모니터 종목이 올바른 JSON이 아닙니다.");
        }
        if (d == null || !d.isJsonObject() || d.getAsJsonObject().get("items") == null || !d.getAsJsonObject().get("items").isJsonArray()) {
            throw new IllegalArgumentException("모니터 종목은 {\"items\": [...]} 이어야 합니다.");
        }
        List<Map<String, Object>> items = validItems(d.getAsJsonObject().get("items"));
        Path tmp = monitorFile().resolveSibling("monitor_items.json.tmp");
        Files.write(tmp, (GSON.toJson(items) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, monitorFile(), StandardCopyOption.REPLACE_EXISTING);  // 반쯤 쓴 파일을 읽지 않게 통째로 교체
        return Collections.singletonMap("items", items);
    }

    /** GET /api/recent : 최근 검색 종목 (최신순, 최대 RECENT_MAX개). */
    Map<String, Object> recent() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", readRecent());
        return out;
    }

    private List<Map<String, Object>> readRecent() throws IOException {
        List<String> lines;
        try {
            lines = Files.readAllLines(recentFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p.length == 4 && ("kr".equals(p[0]) || "us".equals(p[0])) && !p[1].isEmpty() && items.size() < RECENT_MAX) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 0; i < 4; i++) {
                    r.put(RECENT_KEYS[i], p[i]);
                }
                items.add(r);
            }
        }
        return items;
    }

    /** 차트를 연 종목을 맨 앞에 넣고(같은 종목은 앞으로 옮김) RECENT_MAX개만 남깁니다. */
    synchronized void remember(Map<String, Object> s) throws IOException {
        List<Map<String, Object>> items = readRecent();
        items.removeIf(r -> r.get("market").equals(s.get("market")) && r.get("code").equals(s.get("code")));
        items.add(0, s);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : items.subList(0, Math.min(RECENT_MAX, items.size()))) {
            for (int i = 0; i < 4; i++) {
                sb.append(i == 0 ? "" : "\t").append(Values.str(r.get(RECENT_KEYS[i])).replaceAll("[\\t\\r\\n]", " "));
            }
            sb.append('\n');
        }
        Files.write(recentFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> krStocks() throws IOException {
        if (System.currentTimeMillis() - krAt > LIST_CACHE_MS) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> r : KBClient.records(client.call("IVS10920", Heatmap.body("inq_cnt", "9999")), "out")) {
                list.add(stock("kr", Values.str(r.get("is_cd")), Values.str(r.get("is_nm")), null));
            }
            kr = list;
            krAt = System.currentTimeMillis();
        }
        return kr;
    }

    private static Map<String, Object> stock(String market, String code, String name, String exchange) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("market", market);
        s.put("code", code);
        s.put("name", name);
        if (exchange != null) {
            s.put("exchange", exchange);
        }
        return s;
    }

    /** GET /api/search?q= */
    Map<String, Object> search(String query) throws IOException {
        String q = query == null ? "" : query.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        if (q.isEmpty()) {
            out.put("results", Collections.emptyList());
            return out;
        }
        String lo = q.toLowerCase(Locale.ROOT), up = q.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> krs = new ArrayList<>();
        for (Map<String, Object> s : krStocks()) {
            if (((String) s.get("name")).toLowerCase(Locale.ROOT).contains(lo) || ((String) s.get("code")).startsWith(q)) {
                krs.add(s);
            }
        }
        // 이름·번호가 정확히 같은 종목, 이름이 검색어로 시작하는 종목, 짧은 이름 순
        krs.sort(Comparator.<Map<String, Object>, Boolean>comparing(s -> !(((String) s.get("name")).toLowerCase(Locale.ROOT).equals(lo) || s.get("code").equals(q)))
                .thenComparing(s -> !((String) s.get("name")).toLowerCase(Locale.ROOT).startsWith(lo))
                .thenComparing(s -> ((String) s.get("name")).length()));
        List<Map<String, Object>> us = new ArrayList<>();
        for (String[] t : Heatmap.US_STOCKS) {
            if (t[0].startsWith(up)) {
                us.add(stock("us", t[0], t[0], t[1]));
            }
        }
        us.sort(Comparator.<Map<String, Object>, Boolean>comparing(s -> !s.get("code").equals(up)).thenComparing(s -> ((String) s.get("code")).length()));
        boolean exact = !us.isEmpty() && us.get(0).get("code").equals(up);
        boolean krNameExact = krs.stream().anyMatch(s -> ((String) s.get("name")).toLowerCase(Locale.ROOT).equals(lo));
        if (TICKER.matcher(up).matches() && !exact && !krNameExact) {
            us.add(0, stock("us", up, up, ""));  // 목록 밖 티커: 차트 조회 때 거래소 탐색
            exact = krs.isEmpty();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        if (exact) {  // 티커가 정확히 맞으면 미국 먼저
            results.addAll(us.subList(0, Math.min(5, us.size())));
            results.addAll(krs);
        } else {
            results.addAll(krs);
            results.addAll(us);
        }
        out.put("results", results.subList(0, Math.min(20, results.size())));
        return out;
    }

    Map<String, Object> chart(String market, String rawCode, String exchange, boolean save) throws IOException {
        return chart(market, rawCode, exchange, save, 0);
    }

    /**
     * GET /api/chart?market=kr|us&code=&exchange=[&recent=0][&minutes=1|3|5]. save가 false(recent=0)면 최근 검색에 남기지 않음 (시그널 목록용).
     * minutes가 1·3·5면 분봉 (캔들에 시각 t). 국내는 그 분봉을, 미국은 KB가 1분봉만 줘서 1분봉을 주고 bars=1로 알림 (화면이 묶음). chart.py와 같음.
     */
    Map<String, Object> chart(String market, String rawCode, String exchange, boolean save, int minutes) throws IOException {
        if (minutes != 0 && minutes != 1 && minutes != 3 && minutes != 5) {
            throw new IllegalArgumentException("분봉은 1·3·5분입니다: " + minutes);
        }
        String code = rawCode == null ? "" : rawCode.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if ("kr".equals(market)) {
            if (!KR_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("국내 종목번호는 6자리입니다: " + code);
            }
            Map<String, Object> m = client.call("IVM10050", Heatmap.body("is_cd", code));
            String board = Values.str(m.get("indx_nm")).startsWith("코스닥") ? "KOSDAQ" : "KOSPI";  // ETF는 indx_nm이 비어 KOSPI
            List<Map<String, Object>> rows = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1",
                    "mkt_clsf", "KOSDAQ".equals(board) ? "1" : "0", "chrt_clsf", minutes > 0 ? "B" : "D",
                    "minute_tck_indx", minutes > 0 ? String.valueOf(minutes) : "",
                    "is_cd", code, "inq_clsf", "2", "strt_dy", "", "inq_cnt", minutes > 0 ? MINUTE_BARS : CHART_DAYS)), "out2");
            String name = code;
            for (Map<String, Object> s : krStocks()) {
                if (s.get("code").equals(code)) {
                    name = (String) s.get("name");
                }
            }
            out.put("market", "kr");
            out.put("code", code);
            out.put("name", name);
            out.put("exchange", board);
            out.put("currency", "KRW");
            out.put("bars", minutes);
            out.put("candles", candles(rows, "p2", minutes > 0 ? 1 : 10));  // 국내 일봉 거래대금은 10원 단위, 분봉은 원
            if (save) {
                remember(out);
            }
            return out;
        }
        if (!"us".equals(market)) {
            throw new IllegalArgumentException("market은 kr 또는 us입니다: " + market);
        }
        code = code.toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(code).matches()) {
            throw new IllegalArgumentException("티커가 올바르지 않습니다: " + code);
        }
        List<String> exchanges = US_EXCHANGES;
        if (US_EXCHANGES.contains(exchange)) {
            exchanges = Collections.singletonList(exchange);
        } else {
            for (String[] t : Heatmap.US_STOCKS) {
                if (t[0].equals(code)) {
                    exchanges = Collections.singletonList(t[1]);
                }
            }
        }
        for (String x : exchanges) {
            Map<String, Object> r;
            try {
                r = client.call("GSC10060", Heatmap.body("chrt_clsf", minutes > 0 ? "2" : "3",  // 2 = 1분봉, 3 = 일봉
                        "mdfy_stk_prc_use_f", "", "is_cd", code, "clsf", "1",
                        "srch_strt_dy", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), "rcrd_c", minutes > 0 ? MINUTE_BARS : CHART_DAYS, "bndl", "",
                        "krx_cd", x));
            } catch (KBApiException e) {
                continue;
            }
            List<Map<String, Object>> cs = candles(KBClient.records(r, "out2"), "p4", 1);
            if (!cs.isEmpty()) {
                String name = Values.str(r.get("hngl_is_nm")).trim();
                if (name.isEmpty()) {
                    name = Values.str(r.get("eng_is_nm")).trim();
                }
                out.put("market", "us");
                out.put("code", code);
                out.put("name", name.isEmpty() ? code : name);
                out.put("exchange", x);
                out.put("currency", "USD");
                out.put("bars", minutes > 0 ? 1 : 0);
                out.put("candles", cs);
                if (save) {
                    remember(out);
                }
                return out;
            }
        }
        throw new IllegalArgumentException("미국 거래소(나스닥·뉴욕·아멕스)에서 " + code + " 차트를 찾지 못했습니다.");
    }

    /** 일봉 행 -> {d,o,h,l,c,v,amt}. 깨진 행은 건너뛰고, 같은 날 중복을 없애 오래된 날부터. */
    static List<Map<String, Object>> candles(List<Map<String, Object>> rows, String suffix, double valueScale) {
        TreeMap<String, Map<String, Object>> byDay = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            String d = Values.str(r.get("dt")).trim();
            Double o = Values.numOrNull(r.get("opn_prc_" + suffix)), h = Values.numOrNull(r.get("hgh_prc_" + suffix));
            Double l = Values.numOrNull(r.get("lw_prc_" + suffix)), c = Values.numOrNull(r.get("cls_prc_" + suffix));
            if (o == null || h == null || l == null || c == null || c <= 0 || !Heatmap.validDay(d)) {
                continue;  // 필드가 밀린 행
            }
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("d", d);
            k.put("o", o);
            k.put("h", h);
            k.put("l", l);
            k.put("c", c);
            k.put("v", Values.num(r.get("vlm")));
            k.put("amt", Values.num(r.get("dl_tw_amt")) * valueScale);
            String t = "";
            if (r.containsKey("tm")) {  // 분봉: 시각 HHMM (그 시장 현지 시간)
                Object tm = r.get("tm");
                String raw = tm instanceof Number ? String.valueOf(((Number) tm).longValue()) : Values.str(tm).trim();
                t = ("000000" + raw).substring(raw.length()).substring(0, 4);
                if (!t.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                k.put("t", t);
            }
            byDay.put(d + t, k);  // 같은 날(분봉은 같은 시각) 중복 제거, 오래된 것부터
        }
        return new ArrayList<>(byDay.values());
    }
}
