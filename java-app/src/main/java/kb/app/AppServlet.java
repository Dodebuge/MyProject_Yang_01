package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * dividends_web.py와 같은 주소를 처리합니다. 화면(HTML)은 Python 버전과 같은 파일을 씁니다.
 *
 *   GET /, /heatmap, /me, /chart, /volume, /monitor -> home.html, heatmap.html, me.html, chart.html, volume.html, monitor.html
 *   GET /signals.js                     -> 차트·거래량 화면이 함께 쓰는 보조지표·시그널 계산
 *   GET /docs/*.html                    -> Archify 구조도·순서도 (첫 화면의 '구조 문서')
 *   GET /api/heatmap?market=kr|us       -> 섹터 히트맵
 *   GET /api/quarters?market=kr|us      -> 분기별 일평균 거래대금
 *   GET /api/holdings                   -> 보유 종목과 현금 (KB + 토스)
 *   GET /api/holdings-history           -> 날짜별 총자산 (kb.db 하루 기록 + MYSQL_HOST가 있으면 4시간 기록)
 *   GET /api/dividends?year=2026        -> 배당 내역
 *   GET /api/recurring                  -> 소수점 정기 구매 내역 (거래내역 소수단위매수, 최근 반년)
 *   GET /api/transactions               -> 최근 1달 거래내역
 *   GET /api/search?q=                  -> 종목 검색 (국내 이름·종목번호, 미국 티커)
 *   GET /api/chart?market=kr|us&code=   -> 일봉 차트 (최근 약 1년)
 *   GET /api/kosdaq-volume              -> 코스닥 전 종목 이번 주·전주 하루 평균 거래량 (10분 캐시)
 *   GET /api/kosdaq-intraday[?date=&at=] -> 장중 5분 기록(kosdaq.db)으로 같은 시각 거래량의 전일·전주 대비
 *   GET /api/kosdaq-sim?min=&amount=&tp=&sl=&until=&cost=&spac= -> 같은 기록으로 거래량 증가 + 상승 종목 매수·매도 시뮬레이션
 *   GET /api/russell-volume, /api/russell-intraday -> 러셀 2000 (russell2000.csv 종목) 같은 모양. 같은 시각은 뉴욕 시간 30분마다
 *   GET /api/trader, POST /api/trader  -> 토스 자동매매 상태, 설정·매수 대상·전량 매도 (Trader, trader.db)
 *   GET /api/alerts[?since=id]         -> 코스닥·러셀 2000 증가율 top 30의 새 상승 시그널 알림 (Alerts, alerts.db)
 *   GET /api/recent                     -> 최근 검색 종목 (recent_searches.txt, 최대 10개)
 *   GET /api/settings, POST /api/settings -> 차트 설정 (chart_settings.json: 기간, 이평선·볼린저밴드 체크)
 *   GET /api/monitor, POST /api/monitor  -> 시장 모니터 지수 칸 + 추가한 종목 (monitor_items.json, POST는 목록 통째로)
 *   GET /api/monitor-quotes[?refresh=1]  -> 5분마다 kb.db에 저장한 모니터 시세 (refresh=1이면 지금 KB에서 다시 받아 저장)
 *   (내정보 API는 ?refresh=1 이면 저장된 오늘 값을 건너뛰고 KB에서 다시 조회)
 *   (heatmap·quarters·holdings·dividends·recurring·transactions·chart·kosdaq-volume·russell-volume은 ?cached=1 이면 last_api/*.txt의 마지막 응답을 바로 돌려줌)
 *
 * 설정: web.xml의 context-param dataDir(기본 /opt/kb_openapi_sample)에 .env와 etf_snapshot.json을 둡니다.
 * 토스 계좌 합산: 같은 .env(또는 환경변수)에 TOSS_OPENAPI_CLIENT_ID/SECRET이 있으면 켜집니다.
 * 환경변수 KB_DATA_DIR가 있으면 그 값을 씁니다. KB_OPENAPI_* 환경변수는 .env보다 우선합니다.
 */
public class AppServlet extends HttpServlet {

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final java.lang.reflect.Type MAP_TYPE = new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType();
    private static final Map<String, String> PAGES = new HashMap<>();
    // 구조도 (docs/*.html). '/'가 더 들어갈 수 없어 docs 폴더 밖은 못 엶
    private static final Pattern DOC = Pattern.compile("/docs/[\\w.-]+\\.html");

    static {
        PAGES.put("/", "/home.html");
        PAGES.put("/heatmap", "/heatmap.html");
        PAGES.put("/me", "/me.html");
        PAGES.put("/chart", "/chart.html");
        PAGES.put("/volume", "/volume.html");
        PAGES.put("/trader", "/trader.html");
        PAGES.put("/monitor", "/monitor.html");
        PAGES.put("/signals.js", "/signals.js");  // 차트·거래량 화면이 함께 쓰는 보조지표·시그널 계산
        PAGES.put("/sw.js", "/sw.js");  // 브라우저 알림 서비스 워커 (안드로이드 크롬 등)
    }

    private Heatmap heatmap;
    private Dividends dividends;
    private LastResponse last;  // 마지막 응답 (?cached=1)
    private Chart chart;
    private Monitor monitor;  // 시장 모니터 시세를 5분마다 kb.db에 저장
    private Volume volume;
    private Russell russell;
    private Alerts alerts;
    private Trader trader;
    private Snapshots snapshots;  // 4시간마다 보유 금액 기록 (MySQL)
    private static final int SETTINGS_MAX_BYTES = 4096;  // POST /api/settings 본문 크기 제한
    private static final int MONITOR_MAX_BYTES = 16384;  // POST /api/monitor 본문 크기 제한 (30종목이 수 KB)

    private interface Api {
        Map<String, Object> handle(HttpServletRequest req) throws IOException;
    }

    private final Map<String, Api> apis = new HashMap<>();

    @Override
    public void destroy() {
        if (snapshots != null) {
            snapshots.stop();
        }
        if (volume != null) {
            volume.ticks.stop();
        }
        if (russell != null) {
            russell.ticks.stop();
        }
        if (alerts != null) {
            alerts.stop();
        }
        if (monitor != null) {
            monitor.stop();
        }
        if (trader != null) {
            trader.stop();
        }
    }

    @Override
    public void init() throws ServletException {
        String dir = System.getenv("KB_DATA_DIR");
        if (dir == null || dir.isEmpty()) {
            dir = getServletContext().getInitParameter("dataDir");
        }
        Path dataDir = Paths.get(dir == null || dir.isEmpty() ? "/opt/kb_openapi_sample" : dir);
        KBClient client;
        try {
            client = KBClient.fromEnv(dataDir.resolve(".env"));
        } catch (IOException | IllegalStateException e) {
            throw new ServletException("KB OpenAPI 설정을 읽지 못했습니다 (" + dataDir + "): " + e.getMessage(), e);
        }
        DailyStore store = new DailyStore(dataDir.resolve("kb.db"));  // 내정보: 하루 한 번만 KB 조회
        TossClient toss;
        try {
            toss = TossClient.fromDataDir(dataDir);  // 토스 키가 있으면 내정보에 토스 계좌 합산
        } catch (IOException e) {
            throw new ServletException("토스 OpenAPI 설정을 읽지 못했습니다 (" + dataDir + "): " + e.getMessage(), e);
        }
        heatmap = new Heatmap(client, dataDir.resolve("etf_snapshot.json"), store, toss);
        dividends = new Dividends(client, store);
        last = new LastResponse(dataDir.resolve("last_api"));
        chart = new Chart(client, dataDir.resolve("recent_searches.txt"));  // 최근 검색 종목 10개
        monitor = new Monitor(chart, dataDir.resolve("kb.db"), client);
        monitor.start();  // 시장 모니터 지수·추가 종목 시세를 5분마다 kb.db에 저장 (화면 첫 로딩용)
        volume = new Volume(client, dataDir.resolve("kosdaq.db"));
        volume.ticks.start();  // 장중 10분마다 코스닥 거래량 기록
        russell = new Russell(client, dataDir.resolve("russell2000.csv"), dataDir.resolve("russell.db"));
        russell.ticks.start();  // 뉴욕 장중 30분마다 러셀 2000 거래량 기록 (russell2000.csv가 있을 때)
        try {
            Map<String, String> env = KBClient.readEnvFile(dataDir.resolve(".env"));
            env.putAll(System.getenv());  // 환경변수가 .env보다 우선
            alerts = new Alerts(volume, russell, dataDir.resolve("alerts.db"), env);
        } catch (IOException e) {
            throw new ServletException("알림 설정을 읽지 못했습니다 (" + dataDir + "): " + e.getMessage(), e);
        }
        alerts.start();  // 코스닥·러셀 2000 증가율 top 30의 새 상승 시그널을 장중 5분마다 확인 (ntfy·브라우저 알림)
        trader = new Trader(toss, dataDir.resolve("trader.db"));  // 토스 자동매매 (토스 키가 없으면 꺼짐)
        alerts.listener = (market, a) -> trader.onSignal("kosdaq".equals(market) ? "KR" : "US", Values.str(a.get("code")), Values.str(a.get("name")));
        trader.start();
        try {
            snapshots = new Snapshots(heatmap, dataDir.resolve(".env"));  // MYSQL_HOST가 있으면 4시간마다 보유 금액 기록
        } catch (IOException e) {
            throw new ServletException("MySQL 설정을 읽지 못했습니다 (" + dataDir + "): " + e.getMessage(), e);
        }
        snapshots.start();

        apis.put("/api/heatmap", req -> heatmap.queryHeatmap(param(req, "market", "kr")));
        apis.put("/api/quarters", req -> heatmap.queryQuarters(param(req, "market", "kr")));
        apis.put("/api/holdings", req -> heatmap.queryHoldings(refresh(req)));
        apis.put("/api/holdings-history", req -> snapshots.history());
        apis.put("/api/recurring", req -> dividends.queryRecurring(refresh(req)));
        apis.put("/api/search", req -> chart.search(param(req, "q", "")));
        apis.put("/api/recent", req -> chart.recent());
        apis.put("/api/settings", req -> chart.settings());
        apis.put("/api/monitor", req -> monitor.listing());
        apis.put("/api/monitor-quotes", req -> refresh(req) ? monitor.refresh() : monitor.quotes());
        apis.put("/api/chart", req -> chart.chart(param(req, "market", "kr"), param(req, "code", ""), param(req, "exchange", ""), !"0".equals(param(req, "recent", "1"))));
        apis.put("/api/kosdaq-volume", req -> volume.query(refresh(req)));
        apis.put("/api/kosdaq-intraday", req -> volume.ticks.intraday(param(req, "date", ""), param(req, "at", "")));
        apis.put("/api/kosdaq-sim", req -> {
            String market = param(req, "market", "kosdaq");
            Ticks ticks = "kosdaq".equals(market) ? volume.ticks : "russell".equals(market) ? russell.ticks : null;
            if (ticks == null) {
                throw new IllegalArgumentException("없는 시장입니다: " + market);
            }
            return ticks.simulate(Double.parseDouble(param(req, "min", "100")), Double.parseDouble(param(req, "amount", "300000")),
                    Double.parseDouble(param(req, "tp", "0")), Double.parseDouble(param(req, "sl", "0")), param(req, "until", "15:00"),
                    Double.parseDouble(param(req, "cost", "0.23")), !"0".equals(param(req, "spac", "1")), Integer.parseInt(param(req, "hold", "30")),
                    param(req, "buy", "volume-open-up"), param(req, "sell", "tp-sl"), param(req, "start", ""),
                    Integer.parseInt(param(req, "maxHold", "0")), Integer.parseInt(param(req, "maxBuys", "0")),
                    "1".equals(param(req, "alerts", "0")) ? alerts.firstTimes(market) : null);
        });
        apis.put("/api/kosdaq-sim-modules", req -> SimModules.catalog());
        apis.put("/api/trader", req -> trader.status());
        apis.put("/api/alerts", req -> alerts.recent(Long.parseLong(param(req, "since", "0"))));
        apis.put("/api/russell-volume", req -> russell.query(refresh(req)));
        apis.put("/api/russell-intraday", req -> russell.ticks.intraday(param(req, "date", ""), param(req, "at", "")));
        apis.put("/api/transactions", req -> dividends.queryTransactions(refresh(req)));
        apis.put("/api/dividends", req -> {
            String year = param(req, "year", String.valueOf(LocalDate.now().getYear()));
            try {
                return dividends.query(Integer.parseInt(year), refresh(req));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("연도가 올바르지 않습니다: " + year);
            }
        });
    }

    /** ?refresh=1 : 저장된 오늘 값을 건너뛰고 KB에서 다시 조회 (화면의 'KB에서 다시 조회' 버튼). */
    /** POST /api/monitor : 목록을 저장하고 시세 저장을 곧바로 다시 돌려 새 종목을 받게 함. */
    private Map<String, Object> saveMonitor(String text) throws IOException {
        Map<String, Object> out = chart.saveMonitorItems(text);
        monitor.changed();
        return out;
    }

    private static boolean refresh(HttpServletRequest req) {
        String v = req.getParameter("refresh");
        return "1".equals(v) || "true".equals(v);
    }

    private static String param(HttpServletRequest req, String name, String fallback) {
        String v = req.getParameter(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setHeader("Cache-Control", "no-store");
        String path = req.getPathInfo();
        if (!"/api/settings".equals(path) && !"/api/trader".equals(path) && !"/api/monitor".equals(path)) {
            resp.sendError(404);
            return;
        }
        int status = 200;
        Object payload;
        if (req.getContentLength() <= 0 || req.getContentLength() > ("/api/monitor".equals(path) ? MONITOR_MAX_BYTES : SETTINGS_MAX_BYTES)) {
            status = 400;
            payload = error("요청 크기가 올바르지 않습니다");
        } else {
            byte[] buf = new byte[req.getContentLength()];
            int n = 0;
            try (InputStream in = req.getInputStream()) {
                for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0; n += r) {
                }
            }
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            try {
                payload = "/api/trader".equals(path) ? trader.command(GSON.fromJson(text, MAP_TYPE))
                        : "/api/monitor".equals(path) ? saveMonitor(text) : chart.saveSettings(text);
            } catch (IllegalArgumentException | com.google.gson.JsonParseException e) {
                status = 400;
                payload = error(e.getMessage());
            } catch (IOException e) {  // trader.db·토스 오류
                status = 502;
                payload = error(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=utf-8");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        resp.setHeader("Cache-Control", "no-store");

        String page = PAGES.get(path);
        if (page == null && DOC.matcher(path).matches()) {
            page = path;  // WAR 안의 docs/*.html
        }
        if (page != null) {
            try (InputStream in = getServletContext().getResourceAsStream(page)) {
                if (in == null) {
                    resp.sendError(404);
                    return;
                }
                resp.setContentType(page.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8");
                copy(in, resp.getOutputStream());
            }
            return;
        }

        Api api = apis.get(path);
        if (api == null) {
            resp.sendError(404);
            return;
        }
        Path lastFile = last.file(path, req.getParameterMap());
        if (lastFile != null && LastResponse.cached(req.getParameterMap())) {  // ?cached=1 : 마지막으로 성공한 응답 (없으면 404)
            String saved = LastResponse.read(lastFile);
            byte[] body = (saved != null ? saved : GSON.toJson(error("저장된 응답이 없습니다"))).getBytes(StandardCharsets.UTF_8);
            resp.setStatus(saved != null ? 200 : 404);
            resp.setContentType("application/json; charset=utf-8");
            resp.setContentLength(body.length);
            resp.getOutputStream().write(body);
            return;
        }
        int status;
        Object payload;
        try {
            payload = api.handle(req);
            status = 200;
        } catch (IllegalArgumentException | KBApiException e) {
            status = 400;
            payload = error(e.getMessage());
        } catch (Exception e) {  // 네트워크 오류 등
            status = 502;
            payload = error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String json = GSON.toJson(payload);
        if (lastFile != null && status == 200) {
            try {
                LastResponse.save(lastFile, json);
            } catch (IOException e) {  // 첫 로딩용 저장이 안 돼도 응답은 그대로 보냄
                log("마지막 응답 저장 실패 (" + lastFile + "): " + e);
            }
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("application/json; charset=utf-8");
        resp.setContentLength(body.length);
        resp.getOutputStream().write(body);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) {
            out.write(buffer, 0, n);
        }
    }
}
