package kb.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 진입점. dividends_web.py와 같은 주소·같은 JSON을 제공합니다.
 *
 *   java -jar kb-openapi-spring.jar                       # http://localhost:8080
 *   KB_DATA_DIR=/opt/kb_openapi_sample java -jar ...      # .env와 etf_snapshot.json·*.db 위치
 *   .env에 SSL_CERT·SSL_KEY가 있으면 같은 포트를 https로 엽니다 (deploy/make-cert.sh)
 *
 * 설정은 KbProperties(kb.*), 설정으로 만드는 Bean은 AppConfig, 주기 작업은 각 클래스의 @Scheduled.
 * SQLite 파일이 여럿이라 DataSource 자동 설정은 끄고 AppConfig가 파일마다 JdbcTemplate을 만듭니다.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
