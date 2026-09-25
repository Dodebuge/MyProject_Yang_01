package kb.app;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

/**
 * KB증권 OpenAPI(B2C) 클라이언트. kb_client.py와 같은 동작입니다.
 * - 토큰 캐싱: expires_in까지 재사용하고 만료 60초 전에 재발급
 * - 업무 오류 판별: HTTP 200이어도 dataHeader.processFlag가 "A"가 아니면 KBApiException
 * - 고정길이 전문 값 정리: "   368500" -> 368500, "0000000039024.00" -> 39024.0
 * - 5xx 응답은 0.5초, 1초, 2초 쉬고 세 번까지 재시도
 */
public final class KBClient {

    public static final String DEFAULT_BASE_URL = "https://developer.kbsec.com:32484";
    private static final Gson GSON = new Gson();

    private final String appKey;
    private final String appSecret;
    private final String baseUrl;
    private final String ipAddr;
    private final String macAddr;
    private final int timeoutMs = 10_000;

    private String token = "";
    private long tokenExpiresAt;

    public KBClient(String appKey, String appSecret, String baseUrl, String ipAddr, String macAddr) {
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.ipAddr = ipAddr;
        this.macAddr = macAddr;
    }

    /** 환경변수가 우선이고, 없으면 envFile(.env)에서 읽습니다. */
    public static KBClient fromEnv(Path envFile) throws IOException {
        Map<String, String> env = readEnvFile(envFile);
        env.putAll(System.getenv());
        String key = env.getOrDefault("KB_OPENAPI_APP_KEY", "");
        String secret = env.getOrDefault("KB_OPENAPI_APP_SECRET", "");
        if (key.isEmpty() || secret.isEmpty()) {
            throw new IllegalStateException("KB_OPENAPI_APP_KEY / KB_OPENAPI_APP_SECRET 환경변수 또는 " + envFile + "를 설정하세요.");
        }
        return new KBClient(key, secret,
                env.getOrDefault("KB_OPENAPI_BASE_URL", DEFAULT_BASE_URL),
                env.getOrDefault("KB_OPENAPI_IP_ADDR", "127.0.0.1"),
                env.getOrDefault("KB_OPENAPI_MAC_ADDR", "00:00:00:00:00:00"));
    }

    private static Map<String, String> readEnvFile(Path file) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        if (file == null || !Files.exists(file)) {
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

    private Map<String, String> dataHeader() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("ipAddr", ipAddr);
        h.put("macAddr", macAddr);
        return h;
    }

    // ------------------------------------------------------------------ 인증

    public synchronized String accessToken() throws IOException {
        if (token.isEmpty() || System.currentTimeMillis() >= tokenExpiresAt - 60_000) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("appKey", appKey);
            body.put("appSecret", appSecret);
            body.put("grantType", "client_credentials");
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("dataHeader", dataHeader());
            request.put("dataBody", body);
            JsonObject data = post("/oauth2/token", null, request);
            JsonObject dataBody = data.has("dataBody") && data.get("dataBody").isJsonObject() ? data.getAsJsonObject("dataBody") : new JsonObject();
            if (!dataBody.has("access_token")) {
                throw new IOException("토큰 발급 실패: " + data.get("dataHeader"));
            }
            token = dataBody.get("access_token").getAsString();
            long expiresIn = dataBody.has("expires_in") ? dataBody.get("expires_in").getAsLong() : 86_400;
            tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000;
        }
        return token;
    }

    // ------------------------------------------------------------------ TR 호출

    /** TR을 호출하고 정리된 dataBody를 돌려줍니다. 엔드포인트는 /api/v1/{TR코드 소문자}. */
    public Map<String, Object> call(String trCode, Map<String, ?> dataBody) throws IOException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("dataHeader", dataHeader());
        request.put("dataBody", dataBody == null ? new LinkedHashMap<>() : dataBody);
        JsonObject data = post("/api/v1/" + trCode.toLowerCase(), "bearer " + accessToken(), request);
        return parseResponse(trCode, data);
    }

    /** 연속조회 TR을 nxt_key로 끝까지 넘기며 레코드를 모읍니다. */
    public List<Map<String, Object>> callPages(String trCode, Map<String, Object> dataBody, String record, int maxPages) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String key = "";
        for (int page = 0; page < maxPages; page++) {
            Map<String, Object> body = new LinkedHashMap<>(dataBody);
            body.put("nxt_key", key);
            Map<String, Object> out = call(trCode, body);
            rows.addAll(records(out, record));
            key = Values.str(out.get("nxt_key")).trim();
            if (key.isEmpty() || !seen.add(key)) {
                return rows;
            }
        }
        throw new IOException("[" + trCode + "] " + maxPages + "페이지를 넘었습니다. 조회 기간을 줄이세요.");
    }

    /** dataBody 안의 배열 레코드. 없으면 빈 목록. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> records(Map<String, Object> out, String name) {
        Object value = out.get(name);
        return value instanceof List ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    private JsonObject post(String path, String authorization, Object body) throws IOException {
        byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (authorization != null) {
                conn.setRequestProperty("appKey", appKey);
                conn.setRequestProperty("Authorization", authorization);
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            // 호출이 몰리면 KB 서버가 가끔 500을 연달아 돌려줍니다. 0.5초, 1초, 2초 쉬고 세 번까지 재시도.
            if (status >= 500 && attempt < 3) {
                conn.disconnect();
                sleep(500L << attempt);
                continue;
            }
            if (status >= 400) {
                throw new IOException("HTTP " + status + " " + path);
            }
            try (InputStream in = conn.getInputStream(); Scanner s = new Scanner(in, "UTF-8")) {
                String text = s.useDelimiter("\\A").hasNext() ? s.next() : "{}";
                return GSON.fromJson(text, JsonObject.class);
            } finally {
                conn.disconnect();
            }
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("중단됨", e);
        }
    }

    // ------------------------------------------------------------------ 응답 처리

    /** processFlag를 확인하고 정리된 dataBody를 돌려줍니다. 정상(A)이 아니면 KBApiException. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseResponse(String trCode, JsonObject data) {
        JsonObject header = data.has("dataHeader") && data.get("dataHeader").isJsonObject() ? data.getAsJsonObject("dataHeader") : new JsonObject();
        JsonElement body = data.get("dataBody");
        Map<String, Object> out = body != null && body.isJsonObject() ? (Map<String, Object>) clean(body, "") : new LinkedHashMap<>();
        if (!"A".equals(text(header, "processFlag"))) {
            String message = Values.str(out.get("o_msg"));
            if (message.isEmpty()) message = Values.str(out.get("msg"));
            if (message.isEmpty()) message = text(header, "processMessage");
            throw new KBApiException(trCode, text(header, "processCode"), message.trim());
        }
        return out;
    }

    private static String text(JsonObject obj, String key) {
        JsonElement v = obj.get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    // 코드·날짜·시각·구분값처럼 숫자 모양이어도 문자열로 남겨야 하는 필드
    private static final Set<String> KEEP_STR_KEYS = new HashSet<>(Arrays.asList("dt", "tm", "wd", "lngth", "clsfP", "o_clsf"));
    private static final String[] KEEP_STR_SUFFIXES = {"_cd", "_no", "_dt", "_dy", "_tm", "_ccd", "_clsf", "_f", "_id", "_sq", "RecordSize", "_lngth"};

    /** 고정길이 전문 값을 정리합니다 (kb_client.clean과 같은 규칙). */
    static Object clean(JsonElement value, String key) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), clean(e.getValue(), e.getKey()));
            }
            return map;
        }
        if (value.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : (JsonArray) value) {
                list.add(clean(e, key));
            }
            return list;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (!p.isString()) {
            return p.isNumber() ? p.getAsNumber() : p.getAsBoolean();
        }
        String s = p.getAsString().trim();
        if (KEEP_STR_KEYS.contains(key) || key.startsWith("is_")) {
            return s;
        }
        for (String suffix : KEEP_STR_SUFFIXES) {
            if (key.endsWith(suffix)) {
                return s;
            }
        }
        try {
            return s.contains(".") ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }
}
