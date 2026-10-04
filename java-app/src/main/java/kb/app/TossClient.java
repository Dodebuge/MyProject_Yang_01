package kb.app;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 토스증권 OpenAPI 클라이언트 + 내 보유 종목 (toss_openapi_sample의 toss_client.py, heatmap.fetch_my).
 * 내정보에서 KB 계좌와 합산할 때만 씁니다.
 * - 토큰: POST /oauth2/token (form, client_id/client_secret). 클라이언트당 1개만 유효해 token-revoked면 한 번 재발급 후 재시도
 * - 계좌 API는 X-Tossinvest-Account: {accountSeq} 헤더 (TOSS_OPENAPI_ACCOUNT_SEQ, 없으면 첫 계좌)
 * - 429는 Retry-After만큼, 5xx는 0.5초, 1초, 2초 쉬고 세 번까지 재시도
 */
final class TossClient {

    static final String DEFAULT_BASE_URL = "https://openapi.tossinvest.com";
    private static final Set<String> TOKEN_ERRORS = new HashSet<>(Arrays.asList("invalid-token", "expired-token", "token-revoked"));
    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() { }.getType();

    private final String clientId;
    private final String clientSecret;
    private final String baseUrl;
    private String accountSeq;
    private String token = "";
    private long tokenExpiresAt;

    TossClient(String clientId, String clientSecret, String baseUrl, String accountSeq) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.accountSeq = accountSeq;
    }

    /**
     * 환경변수 > dataDir/.env > dataDir/toss_openapi_sample/.env > dataDir/../toss_openapi_sample/.env 순으로
     * TOSS_OPENAPI_CLIENT_ID/SECRET을 찾습니다. 없으면 null (토스 합산 안 함).
     */
    static TossClient fromDataDir(Path dataDir) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        for (Path p : Arrays.asList(dataDir.resolve("../toss_openapi_sample/.env"), dataDir.resolve("toss_openapi_sample/.env"),
                dataDir.resolve(".env"))) {
            env.putAll(readEnvFile(p));  // 뒤에 읽은 것이 우선
        }
        env.putAll(System.getenv());
        String id = env.getOrDefault("TOSS_OPENAPI_CLIENT_ID", "");
        String secret = env.getOrDefault("TOSS_OPENAPI_CLIENT_SECRET", "");
        if (id.isEmpty() || secret.isEmpty()) {
            return null;
        }
        return new TossClient(id, secret, env.getOrDefault("TOSS_OPENAPI_BASE_URL", DEFAULT_BASE_URL),
                env.getOrDefault("TOSS_OPENAPI_ACCOUNT_SEQ", ""));
    }

    private static Map<String, String> readEnvFile(Path file) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return env;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.isEmpty() || line.startsWith("#") || eq < 1) {
                continue;
            }
            env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim().replaceAll("^[\"']|[\"']$", ""));
        }
        return env;
    }

    // ------------------------------------------------------------------ 호출

    private synchronized String accessToken() throws IOException {
        if (token.isEmpty() || System.currentTimeMillis() >= tokenExpiresAt - 60_000) {
            String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
            Map<String, Object> data = request("POST", "/oauth2/token", null, form);
            token = Values.str(data.get("access_token"));
            tokenExpiresAt = System.currentTimeMillis() + (data.containsKey("expires_in") ? Values.lng(data.get("expires_in")) : 86_400) * 1000;
        }
        return token;
    }

    /** GET 후 result를 돌려줍니다. account면 X-Tossinvest-Account 헤더를 붙입니다. */
    Object get(String path, Map<String, String> params, boolean account) throws IOException {
        StringJoiner q = new StringJoiner("&", "?", "").setEmptyValue("");
        if (params != null) {
            params.forEach((k, v) -> q.add(enc(k) + "=" + enc(v)));
        }
        for (boolean retry : new boolean[]{true, false}) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + accessToken());
            if (account) {
                headers.put("X-Tossinvest-Account", accountSeq());
            }
            try {
                return request("GET", path + q, headers, null).get("result");
            } catch (TossApiException e) {
                if (!(retry && TOKEN_ERRORS.contains(e.code))) {
                    throw e;
                }
                synchronized (this) {
                    token = "";  // 만료·무효화된 토큰: 새로 받아 한 번만 다시 시도
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @SuppressWarnings("unchecked")
    private synchronized String accountSeq() throws IOException {
        if (accountSeq.isEmpty()) {
            List<Map<String, Object>> accounts = (List<Map<String, Object>>) get("/api/v1/accounts", null, false);
            if (accounts == null || accounts.isEmpty()) {
                throw new IOException("조회된 토스 계좌가 없습니다.");
            }
            accountSeq = Values.str(accounts.get(0).get("accountSeq")).replaceAll("\\.0$", "");
        }
        return accountSeq;
    }

    private Map<String, Object> request(String method, String path, Map<String, String> headers, String form) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod(method);
            if (headers != null) {
                headers.forEach(conn::setRequestProperty);
            }
            if (form != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            if (attempt < 3 && (status == 429 || status >= 500)) {
                String wait = conn.getHeaderField("Retry-After");
                conn.disconnect();
                sleep(wait != null ? Math.round(Values.num(wait) * 1000) : 500L << attempt);
                continue;
            }
            String text;
            try (InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                text = in == null ? "" : new Scanner(in, "UTF-8").useDelimiter("\\A").next();
            } catch (java.util.NoSuchElementException e) {
                text = "";
            } finally {
                conn.disconnect();
            }
            Map<String, Object> body;
            try {
                body = text.isEmpty() ? new LinkedHashMap<>() : GSON.fromJson(text, MAP);
            } catch (RuntimeException e) {
                throw new IOException("HTTP " + status + " " + path + " (JSON 아님)");
            }
            if (status < 400) {
                return body;
            }
            Object err = body.get("error");
            if (err instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) err;
                throw new TossApiException(status, Values.str(m.get("code")), Values.str(m.get("message")), Values.str(m.get("requestId")));
            }
            // /oauth2/token은 OAuth 표준 형식: {"error": "invalid_client", "error_description": ...}
            throw new TossApiException(status, Values.str(err), Values.str(body.get("error_description")), conn.getHeaderField("X-Request-Id"));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
    }

    /** 업무 오류 (HTTP 4xx + {"error": {code, message, requestId}}). */
    static final class TossApiException extends IOException {
        final String code;

        TossApiException(int status, String code, String message, String requestId) {
            super("[" + status + "] " + code + " " + message + " (requestId=" + requestId + ")");
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ 내 보유 종목

    private static Map<String, String> param(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    /**
     * 토스 계좌의 보유 종목과 현금 (/holdings, /buying-power). heatmap.fetch_my와 같은 모양.
     * cap = 평가금액(원; 미국 종목은 현재 USD/KRW 환율로 환산), pl = 수익률(%), change = 오늘 손익률(%).
     * 현금 = 매수 가능 금액(원화 + 달러 원화환산). 토스에는 예수금 API가 없습니다.
     * 섹터: ETF는 이름 분류, 미국은 Heatmap.US_SECTOR, 국내는 null (Heatmap이 KB 투자지표로 채움).
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> fetchMy() throws IOException {
        Map<String, Object> h = (Map<String, Object>) get("/api/v1/holdings", null, true);
        Map<String, String> fx = param("baseCurrency", "USD");
        fx.put("quoteCurrency", "KRW");
        double rate = Values.num(((Map<String, Object>) get("/api/v1/exchange-rate", fx, false)).get("rate"));
        Map<String, Double> buying = new LinkedHashMap<>();
        for (String c : new String[]{"KRW", "USD"}) {
            buying.put(c, Values.num(((Map<String, Object>) get("/api/v1/buying-power", param("currency", c), true)).get("cashBuyingPower")));
        }
        List<Map<String, Object>> items = (List<Map<String, Object>>) h.get("items");
        Map<String, String> securityType = new LinkedHashMap<>();
        if (!items.isEmpty()) {
            StringJoiner symbols = new StringJoiner(",");
            items.forEach(i -> symbols.add(Values.str(i.get("symbol"))));
            for (Map<String, Object> s : (List<Map<String, Object>>) get("/api/v1/stocks", param("symbols", symbols.toString()), false)) {
                securityType.put(Values.str(s.get("symbol")), Values.str(s.get("securityType")));
            }
        }

        List<Map<String, Object>> stocks = new ArrayList<>();
        for (Map<String, Object> i : items) {
            String symbol = Values.str(i.get("symbol"));
            String name = Values.str(i.get("name"));
            boolean us = "US".equals(i.get("marketCountry"));
            boolean etf = Arrays.asList("ETF", "FOREIGN_ETF").contains(securityType.get(symbol));
            String[] cls = Heatmap.classifyEtf(name);
            double amount = Values.num(((Map<String, Object>) i.get("marketValue")).get("amount"));
            long cap = Math.round(us ? amount * rate : amount);
            Object plRate = ((Map<String, Object>) i.get("profitLoss")).get("rate");
            Object dailyRate = ((Map<String, Object>) i.get("dailyProfitLoss")).get("rate");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", symbol);
            s.put("name", name);
            s.put("market", us ? "미국" : "국내");
            s.put("etf", etf);
            s.put("sector", etf ? (cls[1] != null ? cls[1] : cls[0]) : us ? Heatmap.US_SECTOR.getOrDefault(symbol, "미분류") : null);
            s.put("qty", Values.num(i.get("quantity")));
            s.put("price", Values.num(i.get("lastPrice")));
            s.put("cap", cap);
            s.put("value", cap);
            s.put("pl", plRate == null ? null : (Object) (Values.num(plRate) * 100));
            s.put("change", Values.num(dailyRate) * 100);
            if (cap > 0) {
                stocks.add(s);
            }
        }
        Map<String, Object> cash = new LinkedHashMap<>();
        cash.put("krw", Math.round(buying.get("KRW")));
        cash.put("fx_krw", Math.round(buying.get("USD") * rate));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stocks", stocks);
        result.put("cash", cash);
        return result;
    }
}
