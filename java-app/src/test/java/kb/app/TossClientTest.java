package kb.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 토스증권 OpenAPI 연결 검사. 실제로 토스를 호출하므로 평소 빌드(mvn package)에서는 건너뜁니다.
 *
 *   ../spring-app/mvnw test -Dtoss.live=true                  (java-app 폴더에서)
 *   ../spring-app/mvnw test -Dtoss.live=true -Dkb.data.dir=/opt/kb_openapi_sample
 *
 * 키는 서버와 같은 곳에서 찾습니다: 환경변수 > {dataDir}/.env (저장소 루트 .env)
 * dataDir 기본값은 KB_DATA_DIR 환경변수, 없으면 저장소 루트(java-app의 상위 폴더).
 * 실패하면 대부분 허용 IP 미등록(403)이나 키 오류(401 invalid_client)입니다.
 * 토스 토큰은 클라이언트당 1개만 유효해, 같은 키로 돌고 있는 서버의 토큰은 무효화됩니다(서버가 자동 재발급).
 */
class TossClientTest {

    private static Path dataDir() {
        String dir = System.getProperty("kb.data.dir", System.getenv("KB_DATA_DIR"));
        return dir == null || dir.isEmpty() ? Paths.get("..").toAbsolutePath().normalize() : Paths.get(dir);
    }

    @Test
    @SuppressWarnings("unchecked")
    void connectsToTossAndReadsHoldings() throws Exception {
        assumeTrue(Boolean.getBoolean("toss.live"), "실제 토스 호출은 -Dtoss.live=true 일 때만 실행합니다");

        TossClient toss = TossClient.fromDataDir(dataDir());
        assertNotNull(toss, "TOSS_OPENAPI_CLIENT_ID/SECRET을 찾지 못했습니다 (dataDir=" + dataDir() + ")");

        // 토큰 발급 + 계좌 조회 + 보유 종목·환율·매수 가능 금액 호출이 모두 성공해야 연결된 것으로 봅니다.
        Map<String, Object> my = assertDoesNotThrow(toss::fetchMy, "토스 연결 실패");
        assertInstanceOf(List.class, my.get("stocks"));
        Map<String, Object> cash = (Map<String, Object>) my.get("cash");
        assertTrue((Long) cash.get("krw") >= 0 && (Long) cash.get("fx_krw") >= 0, "현금 값이 이상합니다: " + cash);

        System.out.println("[토스] 연결 성공: " + ((List<?>) my.get("stocks")).size() + "종목, 현금 " + cash);
    }
}
