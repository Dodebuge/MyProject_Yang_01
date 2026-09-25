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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * dividends_web.py와 같은 주소를 처리합니다. 화면(HTML)은 Python 버전과 같은 파일을 씁니다.
 *
 *   GET /, /heatmap, /me                -> home.html, heatmap.html, me.html
 *   GET /api/heatmap?market=kr|us       -> 섹터 히트맵
 *   GET /api/quarters?market=kr|us      -> 분기별 일평균 거래대금
 *   GET /api/holdings                   -> 보유 종목과 현금
 *   GET /api/dividends?year=2026        -> 배당 내역
 *
 * 설정: web.xml의 context-param dataDir(기본 /opt/kb_openapi_sample)에 .env와 etf_snapshot.json을 둡니다.
 * 환경변수 KB_DATA_DIR가 있으면 그 값을 씁니다. KB_OPENAPI_* 환경변수는 .env보다 우선합니다.
 */
public class AppServlet extends HttpServlet {

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final Map<String, String> PAGES = new HashMap<>();

    static {
        PAGES.put("/", "/home.html");
        PAGES.put("/heatmap", "/heatmap.html");
        PAGES.put("/me", "/me.html");
    }

    private Heatmap heatmap;
    private Dividends dividends;

    private interface Api {
        Map<String, Object> handle(HttpServletRequest req) throws IOException;
    }

    private final Map<String, Api> apis = new HashMap<>();

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
        heatmap = new Heatmap(client, dataDir.resolve("etf_snapshot.json"));
        dividends = new Dividends(client);

        apis.put("/api/heatmap", req -> heatmap.queryHeatmap(param(req, "market", "kr")));
        apis.put("/api/quarters", req -> heatmap.queryQuarters(param(req, "market", "kr")));
        apis.put("/api/holdings", req -> heatmap.queryHoldings());
        apis.put("/api/dividends", req -> {
            String year = param(req, "year", String.valueOf(LocalDate.now().getYear()));
            try {
                return dividends.query(Integer.parseInt(year));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("연도가 올바르지 않습니다: " + year);
            }
        });
    }

    private static String param(HttpServletRequest req, String name, String fallback) {
        String v = req.getParameter(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        resp.setHeader("Cache-Control", "no-store");

        if (PAGES.containsKey(path)) {
            try (InputStream in = getServletContext().getResourceAsStream(PAGES.get(path))) {
                if (in == null) {
                    resp.sendError(404);
                    return;
                }
                resp.setContentType("text/html; charset=utf-8");
                copy(in, resp.getOutputStream());
            }
            return;
        }

        Api api = apis.get(path);
        if (api == null) {
            resp.sendError(404);
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
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
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
