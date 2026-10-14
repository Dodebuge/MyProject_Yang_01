package kb.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 마지막 API 응답 (화면 첫 로딩용, daily_store.last_file/save_last/read_last와 같은 파일).
 * API가 성공할 때마다 응답 JSON을 last_api/&lt;경로&gt;[_&lt;파라미터&gt;].txt에 덮어써 둡니다.
 * 화면은 먼저 ?cached=1로 이 값을 받아 바로 그리고, 실제 API 응답이 오면 교체합니다.
 */
final class LastResponse {

    private static final Set<String> APIS = new HashSet<>(Arrays.asList(
            "heatmap", "quarters", "holdings", "dividends", "recurring", "transactions", "chart"));  // 첫 로딩이 느린 API만
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("refresh", "cached"));

    private final Path dir;

    LastResponse(Path dir) {
        this.dir = dir;
    }

    /** 저장할 API면 파일 경로, 아니면 null. 예: ("/api/heatmap", {market=kr}) -> last_api/heatmap_market=kr.txt */
    Path file(String path, Map<String, String[]> params) {
        String api = path.startsWith("/api/") ? path.substring(5) : path;
        if (!APIS.contains(api)) {
            return null;
        }
        StringBuilder kv = new StringBuilder();
        for (Map.Entry<String, String[]> e : new TreeMap<>(params).entrySet()) {
            if (!IGNORED.contains(e.getKey()) && e.getValue().length > 0) {
                kv.append(kv.length() == 0 ? "" : "&").append(e.getKey()).append('=').append(e.getValue()[0]);
            }
        }
        String name = (api + (kv.length() == 0 ? "" : "_" + kv)).replaceAll("[^\\w=&.-]", "_");
        return dir.resolve(name + ".txt");
    }

    static boolean cached(Map<String, String[]> params) {
        String[] v = params.get("cached");
        return v != null && v.length > 0 && ("1".equals(v[0]) || "true".equals(v[0]));
    }

    static String read(Path file) throws IOException {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    static void save(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, body.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);  // 읽는 쪽이 반쯤 쓴 파일을 보지 않게 통째로 교체
    }
}
