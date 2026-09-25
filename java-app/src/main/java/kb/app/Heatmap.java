package kb.app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 국내·해외 섹터 히트맵, 국내 상장 ETF, 분기별 거래대금, 보유 종목 (heatmap.py와 같은 동작).
 *
 * 국내: 시가총액 상위(IVS10920) + 거래대금 상위(IVU10210) 종목의 업종·시총·거래대금(IVM10050). ETF·ETN 제외.
 * 해외: 섹터·순위 TR이 없어 US_STOCKS(미국 대형주, GICS 섹터)를 GSS10030으로 조회.
 * ETF: 전체 상장 종목(IVS10920)에서 브랜드로 ETF를 골라 이름 키워드로 분류, 날짜별 코드 스냅숏과 비교.
 */
final class Heatmap {

    // (티커, 거래소, 섹터). 거래소: NAS=나스닥, NYS=뉴욕
    // ponytail: 수동 목록, 편입·상장 거래소 변경 시 여기만 고치면 됩니다.
    static final List<String[]> US_STOCKS = new ArrayList<>();

    private static void us(String sector, String... symbolExchange) {
        for (String item : symbolExchange) {
            String[] parts = item.split(":");
            US_STOCKS.add(new String[]{parts[0], parts[1], sector});
        }
    }

    static {
        us("정보기술", "NVDA:NAS", "AAPL:NAS", "MSFT:NAS", "AVGO:NAS", "ORCL:NYS", "AMD:NAS", "PLTR:NAS", "CRM:NYS",
                "CSCO:NAS", "IBM:NYS", "MU:NAS", "ACN:NYS", "INTU:NAS", "NOW:NYS", "QCOM:NAS", "TXN:NAS", "AMAT:NAS",
                "LRCX:NAS", "KLAC:NAS", "ADBE:NAS", "ANET:NYS", "INTC:NAS", "ADI:NAS", "PANW:NAS", "CRWD:NAS", "APH:NYS", "DELL:NYS");
        us("커뮤니케이션", "GOOGL:NAS", "META:NAS", "NFLX:NAS", "TMUS:NAS", "DIS:NYS", "T:NYS", "VZ:NYS", "CMCSA:NAS");
        us("경기소비재", "AMZN:NAS", "TSLA:NAS", "HD:NYS", "MCD:NYS", "BKNG:NAS", "LOW:NYS", "TJX:NYS", "SBUX:NAS", "NKE:NYS");
        us("필수소비재", "WMT:NAS", "COST:NAS", "PG:NYS", "KO:NYS", "PEP:NAS", "PM:NYS", "MO:NYS");
        us("헬스케어", "LLY:NYS", "JNJ:NYS", "UNH:NYS", "ABBV:NYS", "MRK:NYS", "TMO:NYS", "ABT:NYS", "ISRG:NAS", "AMGN:NAS",
                "PFE:NYS", "GILD:NAS", "DHR:NYS", "BSX:NYS", "VRTX:NAS");
        us("금융", "BRK.B:NYS", "JPM:NYS", "V:NYS", "MA:NYS", "BAC:NYS", "WFC:NYS", "GS:NYS", "MS:NYS", "AXP:NYS", "C:NYS",
                "SCHW:NYS", "BLK:NYS", "SPGI:NYS", "PGR:NYS", "COIN:NAS");
        us("산업재", "GE:NYS", "CAT:NYS", "RTX:NYS", "BA:NYS", "HON:NAS", "UNP:NYS", "UBER:NYS", "DE:NYS", "LMT:NYS", "ETN:NYS",
                "GEV:NYS", "UPS:NYS");
        us("에너지", "XOM:NYS", "CVX:NYS", "COP:NYS", "EOG:NYS", "SLB:NYS");
        us("소재", "LIN:NAS", "SHW:NYS", "FCX:NYS", "NEM:NYS", "ECL:NYS");
        us("유틸리티", "NEE:NYS", "SO:NYS", "DUK:NYS", "CEG:NAS", "VST:NYS");
        us("부동산", "PLD:NYS", "AMT:NYS", "EQIX:NAS", "WELL:NYS", "SPG:NYS");
    }

    static final Map<String, String> US_SECTOR = new HashMap<>();

    static {
        for (String[] s : US_STOCKS) {
            US_SECTOR.put(s[0], s[2]);
        }
        US_SECTOR.put("GOOG", "커뮤니케이션");
    }

    // ------------------------------------------------------------------ 국내 상장 ETF

    static final String ETF_OTHER = "배당·전략·기타";

    // 운용사 브랜드. 종목명이 이 단어로 시작하면 ETF로 봅니다 (ETN은 이름 끝이 "ETN").
    static final Set<String> ETF_BRANDS = new HashSet<>(Arrays.asList("KODEX", "TIGER", "RISE", "ACE", "PLUS", "SOL", "KIWOOM",
            "HANARO", "1Q", "KoAct", "TIME", "WON", "에셋플러스", "BNK", "마이티", "HK", "MIDAS", "파워", "FOCUS", "UNICORN",
            "DAISHIN", "IBK", "DS", "TREX", "TRUSTON", "더제이"));

    /** (이름 패턴, 국내 업종, 미국 GICS 섹터). 위에서부터 처음 맞는 규칙. 섹터가 null이면 섹터 ETF가 아닌 묶음. */
    private static final class Rule {
        final Pattern pattern;
        final String kr;
        final String us;

        Rule(String regex, String kr, String us) {
            this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            this.kr = kr;
            this.us = us;
        }
    }

    // ponytail: 이름 키워드 분류라 애매한 ETF(예: "AI코리아")는 가까운 섹터로 갑니다. 새 테마가 늘면 규칙을 추가하세요.
    private static final List<Rule> ETF_RULES = Arrays.asList(
            new Rule("채권|국고채|국채|회사채|특수채|금융채|은행채|여전채|금리|CD|KOFR|머니마켓|MMF|단기채|국공채|통안채|전단채|단기자금|물가채|하이일드|TDF|TRF", "채권·금리·자산배분", null),
            new Rule("금현물|금선물|골드|은선물|원유|WTI|구리선물|천연가스|농산물|원자재|달러|엔화|통화|비트코인|국제금|금액티브|은액티브|구리실물|콩선물|팔라듐|탄소배출권", "원자재·통화", null),
            new Rule("리츠|부동산", "부동산", "부동산"),
            new Rule("반도체|HBM|하이닉스|삼성전자|디스플레이|전기전자|전자|엔비디아|TSMC|파운드리|브로드컴", "전기 전자", "정보기술"),
            new Rule("2차전지|이차전지|배터리|리튬|양극재", "전기 전자", "경기소비재"),
            new Rule("소프트웨어|인터넷|플랫폼|게임|클라우드|사이버보안|IT서비스|SW|마이크로소프트|팔란티어|메타버스", "IT 서비스", "정보기술"),
            new Rule("증권", "증권", "금융"),
            new Rule("보험", "보험", "금융"),
            new Rule("은행|금융|지주", "금융", "금융"),
            new Rule("바이오|헬스케어|제약|의료|메디컬|비만|일라이릴리", "제약", "헬스케어"),
            new Rule("자동차|모빌리티|전기차|수소차|테슬라|자율주행|스마트카|BYD", "운송장비 부품", "경기소비재"),
            new Rule("조선|방산|방위|우주|항공우주|K-?디펜스", "운송장비 부품", "산업재"),
            new Rule("원자력|원전|전력|기계|인프라|로봇|휴머노이드|SMR|산업재", "기계 장비", "산업재"),
            new Rule("화장품|뷰티", "화학", "필수소비재"),
            new Rule("화학|소재|정유|에너지", "화학", "에너지"),
            new Rule("철강|금속|비철|고려아연", "금속", "소재"),
            new Rule("건설", "건설", "산업재"),
            new Rule("통신|5G", "통신", "커뮤니케이션"),
            new Rule("미디어|엔터|콘텐츠|컨텐츠|K-?POP|케이팝|방송|웹툰|드라마|K컬처|구글|골프", "오락 문화", "커뮤니케이션"),
            new Rule("음식료|필수소비|푸드|K-?푸드|라면", "음식료 담배", "필수소비재"),
            new Rule("유통|소비|여행|레저|럭셔리|명품|커머스|내수", "유통", "경기소비재"),
            new Rule("운송|해운|물류|항공", "운송 창고", "산업재"),
            new Rule("유틸리티|가스", "전기 가스", "유틸리티"),
            new Rule("테크|빅테크|IT(?![A-Za-z])|AI|FANG|팡플", "전기 전자", "정보기술"),
            new Rule("배당|커버드콜|인컴", ETF_OTHER, null),
            new Rule("200|코스피|코스닥|KRX|KOSPI|KOSDAQ|S&P|나스닥|NASDAQ|다우|MSCI|TOPIX|니케이|항셍|CSI|유로|인도|베트남|중국|차이나|과창판|HSCEI|A50|ChiNext|차이넥스트|DAX|라틴|아시아|일본|미국|글로벌|선진국|신흥국|대만|KTOP|TOP10|Top10|우량주|블루칩|레버리지|인버스", "시장지수", null));

    /** {국내 업종 또는 묶음 이름, 미국 섹터(null 가능)}. */
    static String[] classifyEtf(String name) {
        for (Rule r : ETF_RULES) {
            if (r.pattern.matcher(name).find()) {
                return new String[]{r.kr, r.us};
            }
        }
        return new String[]{ETF_OTHER, null};
    }

    private static boolean isEtfName(String name) {
        return ETF_BRANDS.contains(name.split(" ")[0]);
    }

    // ------------------------------------------------------------------ 상태

    private static final int CACHE_SECONDS = 60;
    private static final int QUARTER_CACHE_SECONDS = 600;  // 과거 분기 값은 자주 바뀌지 않음
    private static final int QUARTERS = 5;                 // 최근 5개 분기 (진행 중인 분기 포함)
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Gson GSON = new Gson();

    private final KBClient client;
    private final Path snapshotFile;
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();  // key -> {저장 시각(ms), payload}

    Heatmap(KBClient client, Path snapshotFile) {
        this.client = client;
        this.snapshotFile = snapshotFile;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cached(String key, int seconds) {
        Object[] hit = cache.get(key);
        return hit != null && System.currentTimeMillis() - (Long) hit[0] < seconds * 1000L ? (Map<String, Object>) hit[1] : null;
    }

    private Map<String, Object> store(String key, Map<String, Object> payload) {
        cache.put(key, new Object[]{System.currentTimeMillis(), payload});
        return payload;
    }

    private static String now() {
        return LocalDateTime.now().format(STAMP);
    }

    private interface Call<T> {
        T get() throws IOException;
    }

    /** KB 업무 오류나 네트워크 오류면 null (한 종목 실패로 전체가 멈추지 않게). */
    private static <T> T orNull(Call<T> call) {
        try {
            return call.get();
        } catch (KBApiException | IOException e) {
            return null;
        }
    }

    private static Map<String, String> body(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ 히트맵

    /** GET /api/heatmap?market=kr|us */
    Map<String, Object> queryHeatmap(String market) throws IOException {
        if (!"kr".equals(market) && !"us".equals(market)) {
            throw new IllegalArgumentException("market은 kr 또는 us: " + market);
        }
        Map<String, Object> hit = cached(market, CACHE_SECONDS);
        if (hit != null) {
            return hit;
        }
        client.accessToken();  // 병렬 호출 전에 토큰을 한 번만 발급
        List<Map<String, Object>> stocks = "kr".equals(market) ? fetchKr() : fetchUs();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("market", market);
        payload.put("currency", "kr".equals(market) ? "KRW" : "USD");
        payload.put("fetchedAt", now());
        payload.put("stocks", stocks);
        payload.put("etf", etfSummary());
        return store(market, payload);
    }

    /** 국내 종목. cap·value는 원 단위. */
    List<Map<String, Object>> fetchKr() throws IOException {
        Map<String, Map<String, Object>> ranked = new LinkedHashMap<>();
        for (Map<String, Object> r : KBClient.records(client.call("IVS10920", body("inq_cnt", "200")), "out")) {
            ranked.put(Values.str(r.get("is_cd")), r);
        }
        Map<String, Object> byValue = client.call("IVU10210", body("srt_clsf", "", "inq_cnt", "100", "excg_clsf", "0",
                "mkt_clsf", "1", "thdy_bdy_clsf", ""));
        for (Map<String, Object> r : KBClient.records(byValue, "out2")) {
            ranked.putIfAbsent(Values.str(r.get("is_cd")), r);
        }
        List<Map<String, Object>> rows = Values.parallel(new ArrayList<>(ranked.values()), 8, row -> {
            Map<String, Object> m = orNull(() -> client.call("IVM10050", body("is_cd", Values.str(row.get("is_cd")))));
            if (m == null) {
                return null;
            }
            String indexName = Values.str(m.get("indx_nm"));
            String sector = indexName.replace("코스피 ", "").replace("코스닥 ", "").trim();
            if (sector.isEmpty()) {  // ETF·ETN
                return null;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", Values.str(row.get("is_cd")));
            s.put("name", Values.str(row.get("is_nm")));
            s.put("sector", sector);
            s.put("board", indexName.startsWith("코스닥") ? "KOSDAQ" : "KOSPI");  // 차트 TR의 mkt_clsf에 필요
            s.put("cap", Values.lng(m.get("opn_prc_tl_amt")) * 100_000_000L);   // 억원 -> 원
            s.put("value", Values.lng(m.get("dl_tw_amt")));
            s.put("change", Values.num(row.get("up_dwn_r_p2")));
            s.put("price", row.get("now_prc"));
            return s;
        });
        rows.removeIf(r -> r == null);
        return rows;
    }

    /** 미국 종목. cap·value는 달러 단위. */
    List<Map<String, Object>> fetchUs() {
        List<Map<String, Object>> rows = Values.parallel(US_STOCKS, 8, item -> {
            Map<String, Object> q = orNull(() -> client.call("GSS10030", body("krx_cd", item[1], "is_cd", item[0])));
            if (q == null || Values.num(q.get("opn_prc_tl_amt")) == 0) {
                return null;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", item[0]);
            s.put("name", item[0]);
            s.put("sector", item[2]);
            s.put("exchange", item[1]);
            s.put("cap", Values.num(q.get("opn_prc_tl_amt")));
            s.put("value", Values.num(q.get("dl_tw_amt")));
            s.put("change", Values.num(q.get("up_dwn_r_p2")));
            s.put("price", q.get("now_prc_p4"));
            s.put("date", q.get("dt"));
            return s;
        });
        rows.removeIf(r -> r == null);
        return rows;
    }

    // ------------------------------------------------------------------ ETF

    /** 시가총액 순위 전체(IVS10920)에서 ETF만 골라 분류합니다. cap은 원 단위. */
    List<Map<String, Object>> fetchEtfs() throws IOException {
        List<Map<String, Object>> etfs = new ArrayList<>();
        for (Map<String, Object> r : KBClient.records(client.call("IVS10920", body("inq_cnt", "9999")), "out")) {
            String name = Values.str(r.get("is_nm"));
            if (!isEtfName(name) || name.endsWith("ETN")) {
                continue;
            }
            String[] cls = classifyEtf(name);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", Values.str(r.get("is_cd")));
            e.put("name", name);
            e.put("kr", cls[0]);
            e.put("us", cls[1]);
            e.put("sector", cls[1] != null);
            e.put("cap", Values.lng(r.get("opn_prc_tl_amt")) * 1_000_000L);  // 백만원 -> 원
            etfs.add(e);
        }
        return etfs;
    }

    /** 오늘 ETF 코드 목록을 저장하고, 직전 날짜와 비교해 새로 상장·상장폐지된 ETF를 돌려줍니다. */
    synchronized Map<String, Object> updateSnapshot(List<Map<String, Object>> etfs) throws IOException {
        String today = LocalDate.now().toString();
        TreeMap<String, List<String>> snaps = new TreeMap<>();
        if (Files.exists(snapshotFile)) {
            try {
                Map<String, List<String>> read = GSON.fromJson(new String(Files.readAllBytes(snapshotFile), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, List<String>>>() { }.getType());
                if (read != null) {
                    snaps.putAll(read);
                }
            } catch (RuntimeException ignored) {
                // 깨진 파일이면 새로 시작
            }
        }
        List<String> codes = new ArrayList<>();
        for (Map<String, Object> e : etfs) {
            codes.add((String) e.get("code"));
        }
        Collections.sort(codes);
        snaps.put(today, codes);
        while (snaps.size() > 60) {  // 최근 60일치만 보관
            snaps.pollFirstEntry();
        }
        Files.write(snapshotFile, GSON.toJson(snaps).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> changes = new LinkedHashMap<>();
        String since = snaps.lowerKey(today);
        changes.put("since", since);
        List<String> listed = new ArrayList<>();
        List<String> delisted = new ArrayList<>();
        if (since != null) {
            Set<String> before = new HashSet<>(snaps.get(since));
            Set<String> nowCodes = new HashSet<>(codes);
            for (Map<String, Object> e : etfs) {
                if (!before.contains(e.get("code"))) {
                    listed.add((String) e.get("code"));
                }
            }
            for (String c : new java.util.TreeSet<>(before)) {
                if (!nowCodes.contains(c)) {
                    delisted.add(c);
                }
            }
        }
        changes.put("listed", listed);
        changes.put("delisted", delisted);
        return changes;
    }

    Map<String, Object> etfSummary() throws IOException {
        List<Map<String, Object>> etfs = fetchEtfs();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", etfs.size());
        summary.put("items", etfs);
        summary.put("changes", updateSnapshot(etfs));
        return summary;
    }

    // ------------------------------------------------------------------ 분기별 거래대금

    /** 실제 달력 날짜이고 오늘 이전인지. 필드가 밀린 행은 날짜 자리에 엉뚱한 숫자가 옵니다. */
    private static boolean validDay(String d) {
        try {
            LocalDate day = LocalDate.parse(d, YMD);
            return !day.isBefore(LocalDate.of(2000, 1, 1)) && !day.isAfter(LocalDate.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 종목의 최근 약 400거래일 {날짜, 거래대금}. 국내 원, 미국 달러. 깨진 행은 건너뜁니다. */
    private List<Object[]> dailyValues(Map<String, Object> stock, String market) throws IOException {
        List<Map<String, Object>> rows;
        double scale;
        if ("kr".equals(market)) {
            rows = KBClient.records(client.call("IVS11560", body("info_ccd", "1",
                    "mkt_clsf", "KOSDAQ".equals(stock.get("board")) ? "1" : "0", "chrt_clsf", "D", "minute_tck_indx", "",
                    "is_cd", (String) stock.get("code"), "inq_clsf", "2", "strt_dy", "", "inq_cnt", "400")), "out2");
            scale = 10;  // 국내 차트 거래대금은 10원 단위
        } else {
            rows = KBClient.records(client.call("GSC10060", body("chrt_clsf", "3",  // 3 = 일봉
                    "mdfy_stk_prc_use_f", "", "is_cd", (String) stock.get("code"), "clsf", "1",
                    "srch_strt_dy", LocalDate.now().format(YMD), "rcrd_c", "400", "bndl", "",
                    "krx_cd", (String) stock.get("exchange"))), "out2");
            scale = 1;
        }
        List<Object[]> days = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String d = Values.str(r.get("dt"));
            Double v = Values.numOrNull(r.get("dl_tw_amt"));
            if (v != null && validDay(d)) {
                days.add(new Object[]{d, v * scale});
            }
        }
        return days;
    }

    private static String quarterOf(String yyyymmdd) {
        return yyyymmdd.substring(0, 4) + "Q" + ((Integer.parseInt(yyyymmdd.substring(4, 6)) - 1) / 3 + 1);
    }

    /** GET /api/quarters?market= : 히트맵 종목의 분기별 일평균 거래대금. 섹터 값 = 소속 종목 일평균의 합. */
    @SuppressWarnings("unchecked")
    Map<String, Object> queryQuarters(String market) throws IOException {
        Map<String, Object> hit = cached("quarters:" + market, QUARTER_CACHE_SECONDS);
        if (hit != null) {
            return hit;
        }
        List<Map<String, Object>> stocks = (List<Map<String, Object>>) queryHeatmap(market).get("stocks");

        List<Map<String, Object>> rows = Values.parallel(stocks, 4, stock -> {
            List<Object[]> days = orNull(() -> dailyValues(stock, market));  // 재시도 후에도 실패하면 빈 목록
            Map<String, double[]> byQ = new HashMap<>();  // 분기 -> {합계, 거래일수}
            String last = "";
            for (Object[] d : days == null ? Collections.<Object[]>emptyList() : days) {
                String day = (String) d[0];
                double[] acc = byQ.computeIfAbsent(quarterOf(day), k -> new double[2]);
                acc[0] += (Double) d[1];
                acc[1] += 1;
                if (day.compareTo(last) > 0) {
                    last = day;
                }
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", stock.get("code"));
            r.put("name", stock.get("name"));
            r.put("sector", stock.get("sector"));
            r.put("_last", last);
            r.put("_byQ", byQ);
            return r;
        });

        int failed = 0;
        String last = "";
        for (Map<String, Object> r : rows) {
            String l = (String) r.get("_last");
            if (l.isEmpty()) {
                failed++;
            } else if (l.compareTo(last) > 0) {
                last = l;
            }
        }
        if (last.isEmpty()) {
            throw new IOException("차트 데이터를 받지 못했습니다.");
        }
        int y = Integer.parseInt(last.substring(0, 4));
        int q = (Integer.parseInt(last.substring(4, 6)) - 1) / 3 + 1;
        List<String> quarters = new ArrayList<>();
        for (int i = 0; i < QUARTERS; i++) {
            quarters.add(0, y + "Q" + q);
            if (q > 1) {
                q--;
            } else {
                y--;
                q = 4;
            }
        }

        Map<String, double[]> sectors = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Map<String, double[]> byQ = (Map<String, double[]>) r.remove("_byQ");
            r.remove("_last");
            List<Double> values = new ArrayList<>();
            double[] acc = sectors.computeIfAbsent((String) r.get("sector"), k -> new double[QUARTERS]);
            for (int i = 0; i < QUARTERS; i++) {
                double[] sumCount = byQ.get(quarters.get(i));
                Double avg = sumCount == null ? null : sumCount[0] / sumCount[1];
                values.add(avg);
                acc[i] += avg == null ? 0 : avg;
            }
            r.put("values", values);
        }
        List<Map<String, Object>> sectorList = new ArrayList<>();
        for (Map.Entry<String, double[]> e : sectors.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", e.getKey());
            List<Double> values = new ArrayList<>();
            for (double v : e.getValue()) {
                values.add(v);
            }
            s.put("values", values);
            sectorList.add(s);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("market", market);
        payload.put("currency", "kr".equals(market) ? "KRW" : "USD");
        payload.put("quarters", quarters);
        payload.put("asOf", last);
        payload.put("fetchedAt", now());
        payload.put("sectors", sectorList);
        payload.put("failed", failed);
        payload.put("stocks", rows);
        return store("quarters:" + market, payload);
    }

    // ------------------------------------------------------------------ 보유 종목

    /** GET /api/holdings : 내 보유 종목과 현금. 서버 메모리에 60초만 캐시하고 파일로 저장하지 않습니다. */
    Map<String, Object> queryHoldings() throws IOException {
        Map<String, Object> hit = cached("holdings", CACHE_SECONDS);
        if (hit != null) {
            return hit;
        }
        client.accessToken();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fetchedAt", now());
        payload.putAll(fetchMy());
        return store("holdings", payload);
    }

    /**
     * 국내: SSQM2952 중 원화 종목. 현금이 D+2 기준이라 종목도 결제 후 잔량(ec_q) 기준으로 셉니다.
     * 해외: SPQM2226 (소수점 포함, 같은 종목 행은 합침). 원화 평가금액은 국내·해외 모두 SSQM2952 val_amt(KB 앱과 같은 환율).
     * cap = 평가금액(원), pl = 수익률(%; 해외는 달러 기준), change = 오늘 등락률(%).
     */
    Map<String, Object> fetchMy() throws IOException {
        Map<String, Map<String, Object>> holdings = new LinkedHashMap<>();
        Map<String, Object> balance = client.call("SSQM2952", body("excg_mktpr_ccd", ""));
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("krw", Values.lng(balance.get("nxt2_dy_tfnd")));
        cash.put("fx_krw", Values.lng(balance.get("fcrncy_tfnd_krw_exch_amt")));
        cash.put("today", Values.lng(balance.get("dy_tfnd")));

        Map<String, Long> fxValue = new HashMap<>();  // 해외 종목 원화 평가금액 (온주·소수점 행 합계)
        for (Map<String, Object> r : KBClient.records(balance, "Record1")) {
            String code = Values.str(r.get("is_cd"));
            if (!Values.str(r.get("crncy_cd")).isEmpty()) {
                fxValue.merge(code, Values.lng(r.get("val_amt")), Long::sum);
                continue;
            }
            long qty = Values.lng(r.get("ec_q"));
            if (qty == 0) {
                continue;
            }
            code = code.startsWith("A") ? code.substring(1) : code;
            String name = Values.str(r.get("is_nm"));
            long cost = Values.lng(r.get("byng_amt"));
            boolean etf = isEtfName(name);
            String[] cls = classifyEtf(name);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("code", code);
            h.put("name", name);
            h.put("market", "국내");
            h.put("qty", qty);
            h.put("etf", etf);
            // 해외 보유와 같은 기준으로 묶도록 섹터 ETF는 GICS 섹터 이름을 씁니다.
            h.put("sector", etf ? (cls[1] != null ? cls[1] : cls[0]) : null);
            h.put("cap", Values.lng(r.get("val_amt")));
            h.put("cost", cost);
            h.put("pl", cost != 0 ? (Object) Values.num(r.get("val_yld")) : null);
            holdings.put(code, h);
        }

        Map<String, Object> overseas = client.call("SPQM2226", body("fee_clsf", "", "nxt_key", "", "std_crncy_f", "1",
                "cn_f", "", "exch_r_aplc_f", "1"));
        for (Map<String, Object> r : KBClient.records(overseas, "Record2")) {
            String code = Values.str(r.get("is_cd"));
            Map<String, Object> h = holdings.computeIfAbsent(code, c -> {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("code", c);
                n.put("name", Values.str(r.get("is_nm")));
                n.put("market", "미국");
                n.put("exchange", Values.str(r.get("mkt_clsf")));
                n.put("qty", 0.0);
                n.put("etf", false);
                n.put("sector", US_SECTOR.getOrDefault(c, "미분류"));
                n.put("cap", 0L);
                n.put("cost_usd", 0.0);
                return n;
            });
            double qty = Values.num(r.get("frgn_hld_q_p6"));
            h.put("qty", (Double) h.get("qty") + qty);
            Long fx = fxValue.get(code);
            h.put("cap", fx != null && fx != 0 ? fx : (Long) h.get("cap") + Values.lng(r.get("krw_val_amt")));
            h.put("cost_usd", (Double) h.get("cost_usd") + qty * Values.num(r.get("byng_avr_prc_p4")));
            h.put("price", Values.num(r.get("now_prc_p4")));
        }

        List<Map<String, Object>> stocks = Values.parallel(new ArrayList<>(holdings.values()), 8, h -> {
            Map<String, Object> q;
            if ("국내".equals(h.get("market"))) {
                q = orNull(() -> client.call("IVU10140", body("excg_clsf", "0", "shrt_cd", (String) h.get("code"))));
                if (q != null) {
                    h.put("price", q.get("now_prc"));
                }
                if (h.get("sector") == null) {
                    Map<String, Object> m = orNull(() -> client.call("IVM10050", body("is_cd", (String) h.get("code"))));
                    String sector = m == null ? "" : Values.str(m.get("indx_nm")).replace("코스피 ", "").replace("코스닥 ", "").trim();
                    h.put("sector", sector.isEmpty() ? "미분류" : sector);
                }
            } else {
                String exchange = (String) h.remove("exchange");
                q = orNull(() -> client.call("GSS10030", body("krx_cd", exchange, "is_cd", (String) h.get("code"))));
                double cost = (Double) h.remove("cost_usd");
                double qty = (Double) h.get("qty");
                h.put("pl", cost != 0 ? (Object) ((qty * (Double) h.get("price") / cost - 1) * 100) : null);
                h.put("qty", Math.round(qty * 1e6) / 1e6);
            }
            h.put("change", q == null ? 0.0 : Values.num(q.get("up_dwn_r_p2")));
            h.put("value", h.get("cap"));  // 크기 기준을 평가금액 하나로 통일
            return h;
        });
        stocks.removeIf(h -> ((Number) h.get("cap")).longValue() <= 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stocks", stocks);
        result.put("cash", cash);
        return result;
    }
}
