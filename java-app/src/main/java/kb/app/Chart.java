package kb.app;

import java.io.IOException;
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

/**
 * 종목 검색 + 일봉 차트 (chart.py와 같은 계산·같은 JSON).
 * 검색: 국내는 전체 상장 종목(IVS10920)의 이름·종목번호로, 미국은 티커로 찾습니다.
 *      미국 종목 목록 TR이 없어 티커 모양이면 US_STOCKS에 없어도 후보로 내고 차트 조회 때 거래소를 찾습니다.
 * 차트: 국내 IVS11560, 미국 GSC10060 일봉 최근 약 1년(250거래일).
 */
final class Chart {

    private static final long LIST_CACHE_MS = 3_600_000;  // 상장 종목 목록은 하루에 거의 안 바뀜
    private static final String CHART_DAYS = "250";
    private static final List<String> US_EXCHANGES = Arrays.asList("NAS", "NYS", "AMX");  // 나스닥, 뉴욕, 아멕스(NYSE Arca ETF 포함)
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z.]{0,5}");
    private static final Pattern KR_CODE = Pattern.compile("[0-9A-Z]{6}");

    private final KBClient client;
    private volatile long krAt;
    private volatile List<Map<String, Object>> kr = Collections.emptyList();

    Chart(KBClient client) {
        this.client = client;
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

    /** GET /api/chart?market=kr|us&code=&exchange= */
    Map<String, Object> chart(String market, String rawCode, String exchange) throws IOException {
        String code = rawCode == null ? "" : rawCode.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if ("kr".equals(market)) {
            if (!KR_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("국내 종목번호는 6자리입니다: " + code);
            }
            Map<String, Object> m = client.call("IVM10050", Heatmap.body("is_cd", code));
            String board = Values.str(m.get("indx_nm")).startsWith("코스닥") ? "KOSDAQ" : "KOSPI";  // ETF는 indx_nm이 비어 KOSPI
            List<Map<String, Object>> rows = KBClient.records(client.call("IVS11560", Heatmap.body("info_ccd", "1",
                    "mkt_clsf", "KOSDAQ".equals(board) ? "1" : "0", "chrt_clsf", "D", "minute_tck_indx", "",
                    "is_cd", code, "inq_clsf", "2", "strt_dy", "", "inq_cnt", CHART_DAYS)), "out2");
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
            out.put("candles", candles(rows, "p2", 10));  // 국내 차트 거래대금은 10원 단위
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
                r = client.call("GSC10060", Heatmap.body("chrt_clsf", "3",  // 3 = 일봉
                        "mdfy_stk_prc_use_f", "", "is_cd", code, "clsf", "1",
                        "srch_strt_dy", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), "rcrd_c", CHART_DAYS, "bndl", "",
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
                out.put("candles", cs);
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
            byDay.put(d, k);
        }
        return new ArrayList<>(byDay.values());
    }
}
