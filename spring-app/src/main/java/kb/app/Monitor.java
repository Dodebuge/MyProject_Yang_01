package kb.app;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 시장 모니터(/monitor) 시세 저장소 (monitor.py와 같은 파일·같은 테이블).
 * 화면이 열릴 때마다 지수·추가 종목의 일봉을 KB에서 하나씩 받으면 수 초 걸려, 5분마다 미리 받아
 * SQLite(kb.db)의 monitor_quotes 테이블에 저장해 두고 화면은 저장된 값을 바로 받습니다 (GET /api/monitor-quotes).
 * 종목 목록이 바뀌면(POST /api/monitor) 다음 5분을 기다리지 않고 곧바로 다시 받습니다 (@Async).
 */
@Service
class Monitor {

    private static final Logger log = LoggerFactory.getLogger(Monitor.class);
    static final int EVERY_SECONDS = 300;
    static final int KEEP_CANDLES = 40;  // 월간 대비(1달 전 ≈ 22거래일)에 넉넉하게
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final java.lang.reflect.Type MAP = new TypeToken<Map<String, Object>>() { }.getType();

    // 지수 히트맵 칸. KB OpenAPI에 지수 시세가 없어 추종 ETF로 봄 (Trader INDEX와 같은 방식)
    // ponytail: 지수 대신 추종 ETF. 정확한 지수 값이 필요하면 KB 지수 시세 TR로 바꿀 것
    static final List<Map<String, Object>> INDICES = List.of(
            index("코스피", "kr", "069500", "", "KODEX 200"),
            index("코스닥", "kr", "229200", "", "KODEX 코스닥150"),
            index("S&P 500", "us", "SPY", "AMX", "SPY"),
            index("나스닥", "us", "QQQ", "NAS", "QQQ (나스닥 100)"),
            index("다우", "us", "DIA", "AMX", "DIA"),
            index("러셀 2000", "us", "IWM", "AMX", "IWM"));

    // 채권 ETF 칸 (monitor.py BONDS와 같음): KB·토스에 금리 TR이 없는 만기는 ETF 가격으로 대신 (금리와 반대로 움직임).
    // 미국채는 그 만기 지표물 하나만 담는 F/m 단일 만기 ETF (UFIV·UTEN·UTHY)
    static final List<Map<String, Object>> BONDS = List.of(
            index("국고채 30년", "kr", "439870", "", "KODEX 국고채30년액티브"),
            index("미국채 5년", "us", "UFIV", "NAS", "UFIV"),
            index("미국채 10년", "us", "UTEN", "NAS", "UTEN"),
            index("미국채 30년", "us", "UTHY", "NAS", "UTHY"));

    // 금리·원유 칸 (KB TR 값 그대로, monitor.py MACRO와 같음). 토스 API는 주식·ETF 시세만 있어 원유 선물·국채 금리는 KB에서 받음.
    // 국고채 30년·미국채 금리는 KB·토스 모두 TR이 없어 위 BONDS(ETF 가격)로 대신
    // {key, 이름, TR, 값 필드, 전일 대비 필드, 단위}
    static final String[][] MACRO = {
            {"macro:wti", "WTI 원유 (근월물)", "IVSA0070", "now_prc", "up_dwn_r_p2", "$"},
            {"macro:kr5y", "국고채 5년", "IVA10370", "ntnbnd_yr5_thng_yld_p5", "ntnbnd_yr5_thng_yld_cmpr_p5", "%"},
            {"macro:kr10y", "국고채 10년", "IVA10370", "ntnbnd_yr10_thng_yld_p5", "ntnbnd_yr10_thng_yld_cmpr_p5", "%"},
    };

    private static Map<String, Object> index(String name, String market, String code, String exchange, String etf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("market", market);
        m.put("code", code);
        m.put("exchange", exchange);
        m.put("etf", etf);
        return Collections.unmodifiableMap(m);
    }

    /** 일봉 조회 (운영은 chart.chart(..., save=false), 테스트는 가짜). */
    interface Quote {
        Map<String, Object> chart(String market, String code, String exchange) throws IOException;
    }

    /** KB TR 호출 (운영은 KBClient.call, 테스트는 가짜). 금리·원유용 */
    interface Tr {
        Map<String, Object> call(String tr, Map<String, ?> body) throws IOException;
    }

    private final Chart chart;
    private final Quote quote;
    private final Tr tr;
    private final MonitorQuotes store;

    Monitor(Chart chart, MonitorQuotes store, Quote quote, Tr tr) {
        this.chart = chart;
        this.quote = quote;
        this.tr = tr;
        this.store = store;
    }

    @Autowired
    Monitor(Chart chart, MonitorQuotes store, KBClient client) {
        this(chart, store, (m, c, x) -> chart.chart(m, c, x, false), client::call);
    }

    /** GET /api/monitor : 지수 칸, 채권 ETF 칸, 사용자가 추가한 종목. */
    Map<String, Object> listing() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indices", INDICES);
        out.put("bonds", BONDS);
        out.putAll(chart.monitorItems());
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> one(Map<String, Object> ix) {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> cs = (List<Map<String, Object>>) quote.chart(
                    Values.str(ix.get("market")), Values.str(ix.get("code")), Values.str(ix.get("exchange"))).get("candles");
            List<Map<String, Object>> slim = new ArrayList<>();
            for (Map<String, Object> c : cs.subList(Math.max(0, cs.size() - KEEP_CANDLES), cs.size())) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("d", c.get("d"));
                k.put("c", c.get("c"));
                slim.add(k);
            }
            p.put("candles", slim);
        } catch (Exception e) {  // 한 종목이 실패해도 나머지는 저장
            p.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return p;
    }

    private static Double num(Object v) {
        try {
            return Double.parseDouble(Values.str(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 금리·원유: TR마다 한 번씩 불러 MACRO 칸으로 {name, value, change, unit, date} (실패는 {name, error}).
     * 원유 change는 전일 대비 %, 금리 change는 전일 대비 %p. 한 TR이 실패해도 다른 칸은 저장.
     */
    Map<String, Map<String, Object>> macro() {
        Map<String, Object> bodies = new LinkedHashMap<>();  // TR -> 응답 Map 또는 오류 문자열
        for (String[] m : MACRO) {
            if (!bodies.containsKey(m[2])) {
                try {
                    bodies.put(m[2], tr.call(m[2], new LinkedHashMap<>()));
                } catch (IOException | RuntimeException e) {
                    bodies.put(m[2], e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
        }
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String[] m : MACRO) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", m[1]);
            Object body = bodies.get(m[2]);
            if (!(body instanceof Map)) {
                v.put("error", body);
                out.put(m[0], v);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) body;
            Map<String, Object> row = b;
            String date = Values.str(b.get("dt2")).trim().isEmpty() ? Values.str(b.get("dt")).trim() : Values.str(b.get("dt2")).trim();
            if ("IVSA0070".equals(m[2])) {  // 해외선물·지수 목록에서 WTI 근월물 (NYM@CL)
                row = null;
                for (Map<String, Object> r : KBClient.records(b, "out4")) {
                    if ("NYM@CL".equals(Values.str(r.get("symbl_cd")).trim())) {
                        row = r;
                    }
                }
                String dt = row == null ? "" : Values.str(row.get("dt_tm")).trim();
                date = dt.length() > 8 ? dt.substring(0, 8) : dt;
            }
            Double value = row == null ? null : num(row.get(m[3]));
            if (value == null) {
                v.put("error", m[2] + " 응답에 " + m[1] + " 값이 없습니다");
            } else {
                v.put("value", value);
                v.put("change", num(row.get(m[4])));
                v.put("unit", m[5]);
                v.put("date", date);
            }
            out.put(m[0], v);
        }
        return out;
    }

    /** 지수·추가 종목 일봉을 KB에서 받아 저장하고, 저장된 전체를 돌려줍니다. 목록에서 빠진 종목은 지웁니다. */
    @SuppressWarnings("unchecked")
    synchronized Map<String, Object> refresh() throws IOException {
        List<Map<String, Object>> items = new ArrayList<>(INDICES);
        items.addAll(BONDS);
        items.addAll((List<Map<String, Object>>) chart.monitorItems().get("items"));
        List<Map<String, Object>> got = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, Object>>> jobs = new ArrayList<>();
            for (Map<String, Object> ix : items) {
                jobs.add(pool.submit(() -> one(ix)));
            }
            for (Future<Map<String, Object>> f : jobs) {
                got.add(f.get());
            }
        } catch (Exception e) {
            throw new IOException("모니터 시세 조회 중단: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
        Map<String, String> payloads = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            payloads.put(items.get(i).get("market") + ":" + items.get(i).get("code"), GSON.toJson(got.get(i)));
        }
        macro().forEach((k, v) -> payloads.put(k, GSON.toJson(v)));
        try {
            store.replaceAll(LocalDateTime.now(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), payloads);
        } catch (DataAccessException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
        return quotes();
    }

    /** GET /api/monitor-quotes : 저장된 시세 (KB 조회 없음). {"quotes": {key: {"fetchedAt", "candles" 또는 "error"}}} */
    Map<String, Object> quotes() throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        try {
            store.findAll().forEach((key, row) -> {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("fetchedAt", row[0]);
                v.putAll(GSON.fromJson(row[1], MAP));
                q.put(key, v);
            });
        } catch (DataAccessException e) {
            throw new IOException("kb.db 저장소 오류: " + e.getMessage(), e);
        }
        return Collections.singletonMap("quotes", q);
    }

    /** 서버 시작 때 한 번, 이후 5분마다 refresh. */
    // ponytail: 장 시간과 상관없이 5분마다 (지수·종목 수십 개라 부담이 작음). 줄이려면 국내·미국 장중에만
    @Scheduled(fixedDelay = EVERY_SECONDS, timeUnit = TimeUnit.SECONDS)
    public void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {  // DB·네트워크 오류여도 다음에 다시
            log.warn("[모니터] 시세 저장 실패: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** 종목 목록이 바뀜 (POST /api/monitor): 다음 5분을 기다리지 않고 곧바로 다시 받음. 응답은 기다리지 않음 */
    @Async
    public void changed() {
        refreshQuietly();
    }
}
