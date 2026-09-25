package kb.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tomcat 앞단 프록시. 받은 요청을 같은 서버의 Python 서버(dividends_web.py, 기본 http://127.0.0.1:8000)로
 * 그대로 넘기고 응답을 돌려줍니다. 페이지와 /api/* 모두 이 서블릿 하나로 처리합니다.
 *
 * 컨텍스트 경로는 떼고 넘깁니다: /kb/heatmap?x=1 -> http://127.0.0.1:8000/heatmap?x=1
 */
public class ProxyServlet extends HttpServlet {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 120_000;  // 분기별 거래대금 첫 조회가 10~20초 걸림

    private String backend;

    @Override
    public void init() {
        String value = getInitParameter("backend");
        backend = (value == null || value.isEmpty() ? "http://127.0.0.1:8000" : value).replaceAll("/+$", "");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo() == null ? "/" : req.getPathInfo();
        String query = req.getQueryString() == null ? "" : "?" + req.getQueryString();

        HttpURLConnection conn;
        int status;
        try {
            conn = (HttpURLConnection) new URL(backend + path + query).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", req.getHeader("Accept") == null ? "*/*" : req.getHeader("Accept"));
            status = conn.getResponseCode();
        } catch (IOException e) {
            // 페이지의 fetch()가 {"error": ...}를 읽어 화면에 보여 줍니다.
            resp.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            resp.setContentType("application/json; charset=utf-8");
            String message = "Python 서버(" + backend + ")에 연결할 수 없습니다: " + e.getMessage();
            resp.getOutputStream().write(("{\"error\": \"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }

        resp.setStatus(status);
        copyHeader(conn, resp, "Content-Type");
        copyHeader(conn, resp, "Cache-Control");
        InputStream body = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (body == null) {
            return;
        }
        try (InputStream in = body; OutputStream out = resp.getOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            for (int n; (n = in.read(buffer)) != -1; ) {
                out.write(buffer, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copyHeader(HttpURLConnection conn, HttpServletResponse resp, String name) {
        String value = conn.getHeaderField(name);
        if (value != null) {
            resp.setHeader(name, value);
        }
    }
}
