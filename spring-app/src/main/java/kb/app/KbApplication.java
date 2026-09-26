package kb.app;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 진입점. dividends_web.py와 같은 주소·같은 JSON을 제공합니다.
 *
 *   java -jar kb-openapi-spring.jar                       # http://localhost:8080
 *   KB_DATA_DIR=/opt/kb_openapi_sample java -jar ...      # .env와 etf_snapshot.json 위치
 *
 * 핵심 로직(KBClient, Heatmap, Dividends)은 java-app과 같은 소스를 씁니다. 여기서는 Bean으로만 묶습니다.
 */
@SpringBootApplication
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }

    /** .env(appKey/appSecret) 위치. KB_OPENAPI_* 환경변수가 .env보다 우선합니다. */
    @Bean
    KBClient kbClient(@Value("${kb.data-dir}") Path dataDir) throws IOException {
        return KBClient.fromEnv(dataDir.resolve(".env"));
    }

    @Bean
    Heatmap heatmap(KBClient client, @Value("${kb.data-dir}") Path dataDir) {
        return new Heatmap(client, dataDir.resolve("etf_snapshot.json"));
    }

    @Bean
    Dividends dividends(KBClient client) {
        return new Dividends(client);
    }
}
