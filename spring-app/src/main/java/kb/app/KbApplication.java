package kb.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 진입점. dividends_web.py와 같은 주소·같은 JSON을 제공합니다.
 *
 *   java -jar kb-openapi-spring.jar                       # http://localhost:8080
 *   KB_DATA_DIR=/opt/kb_openapi_sample java -jar ...      # .env와 etf_snapshot.json 위치
 *   .env에 SSL_CERT·SSL_KEY가 있으면 같은 포트를 https로 엽니다 (deploy/make-cert.sh)
 *
 * 핵심 로직(KBClient, Heatmap, Dividends)은 java-app과 같은 소스를 씁니다. 여기서는 Bean으로만 묶습니다.
 */
@SpringBootApplication
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }

    /**
     * HTTPS: .env(또는 환경변수)에 SSL_CERT·SSL_KEY(PEM 인증서·개인 키 경로, 상대 경로는 데이터 폴더 기준)가 있으면 PORT를 https로 엽니다.
     * 브라우저 알림(거래량 페이지 🔔)은 https 주소나 localhost에서만 됩니다. 인증서는 deploy/make-cert.sh 또는 deploy/README.md 'HTTPS'.
     * 파일이 없거나 깨졌으면 시작할 때 오류로 멈춥니다 (http로 몰래 열리지 않게).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> https(@Value("${kb.data-dir}") Path dataDir) throws IOException {
        Map<String, String> env = KBClient.readEnvFile(dataDir.resolve(".env"));
        env.putAll(System.getenv());  // 환경변수가 .env보다 우선
        String cert = env.getOrDefault("SSL_CERT", "").trim();
        String key = env.getOrDefault("SSL_KEY", "").trim();
        return factory -> {
            if (cert.isEmpty() || key.isEmpty()) {
                return;
            }
            Ssl ssl = new Ssl();
            ssl.setCertificate("file:" + dataDir.resolve(cert).toAbsolutePath());
            ssl.setCertificatePrivateKey("file:" + dataDir.resolve(key).toAbsolutePath());
            factory.setSsl(ssl);
            System.out.println("[HTTPS] 켜짐: " + dataDir.resolve(cert).toAbsolutePath());
        };
    }

    /** .env(appKey/appSecret) 위치. KB_OPENAPI_* 환경변수가 .env보다 우선합니다. */
    @Bean
    KBClient kbClient(@Value("${kb.data-dir}") Path dataDir) throws IOException {
        return KBClient.fromEnv(dataDir.resolve(".env"));
    }

    /** 내정보(보유 종목·배당)를 하루 한 번만 KB에서 조회해 저장하는 SQLite. Python 버전과 같은 kb.db를 씁니다. */
    @Bean
    DailyStore dailyStore(@Value("${kb.data-dir}") Path dataDir) {
        return new DailyStore(dataDir.resolve("kb.db"));
    }

    @Bean
    Heatmap heatmap(KBClient client, @Value("${kb.data-dir}") Path dataDir, DailyStore store) throws IOException {
        // 토스 키(TOSS_OPENAPI_CLIENT_ID/SECRET)가 있으면 내정보에 토스 계좌 합산, 없으면 null
        return new Heatmap(client, dataDir.resolve("etf_snapshot.json"), store, TossClient.fromDataDir(dataDir));
    }

    @Bean
    Dividends dividends(KBClient client, DailyStore store) {
        return new Dividends(client, store);
    }

    /** MYSQL_HOST가 있으면 4시간마다(00·04·…·20시) 보유 금액을 MySQL에 기록. 없으면 아무것도 안 함. */
    @Bean(destroyMethod = "stop")
    Snapshots snapshots(Heatmap heatmap, @Value("${kb.data-dir}") Path dataDir) throws IOException {
        Snapshots s = new Snapshots(heatmap, dataDir.resolve(".env"));
        s.start();
        return s;
    }

    @Bean
    Chart chart(KBClient client, @Value("${kb.data-dir}") Path dataDir) {
        return new Chart(client, dataDir.resolve("recent_searches.txt"));  // 최근 검색 종목 10개
    }

    /** 시장 모니터 지수·추가 종목 시세를 5분마다 kb.db에 저장 (화면 첫 로딩용). */
    @Bean(destroyMethod = "stop")
    Monitor monitor(Chart chart, KBClient client, @Value("${kb.data-dir}") Path dataDir) {
        Monitor m = new Monitor(chart, dataDir.resolve("kb.db"), client);
        m.start();
        return m;
    }

    @Bean
    Volume volume(KBClient client, @Value("${kb.data-dir}") Path dataDir) {
        SimModules.rulesFile = dataDir.resolve("sim_buy_rules.json");  // 시뮬레이션·자동매매 매수 조건 (모니터 화면에서 편집)
        return new Volume(client, dataDir.resolve("kosdaq.db"));
    }

    @Bean
    Russell russell(KBClient client, @Value("${kb.data-dir}") Path dataDir) {
        return new Russell(client, dataDir.resolve("russell2000.csv"), dataDir.resolve("russell.db"));
    }

    /** 장중 5분마다 코스닥 전 종목 누적 거래량을 kosdaq.db에 기록. */
    @Bean(destroyMethod = "stop")
    Ticks kosdaqTicks(Volume volume) {
        volume.ticks.start();
        return volume.ticks;
    }

    /** 코스닥·러셀 2000 증가율 top 30의 새 상승 시그널을 장중 5분마다 확인해 ntfy(.env의 NTFY_TOPIC)·브라우저로 알림. */
    @Bean(destroyMethod = "stop")
    Alerts alerts(Volume volume, Russell russell, @Value("${kb.data-dir}") Path dataDir) throws IOException {
        Map<String, String> env = KBClient.readEnvFile(dataDir.resolve(".env"));
        env.putAll(System.getenv());  // 환경변수가 .env보다 우선
        Alerts a = new Alerts(volume, russell, dataDir.resolve("alerts.db"), env);
        a.start();
        return a;
    }

    /**
     * 토스 자동매매 (실제 주문, trader.html). 토스 키가 없으면 꺼짐. 시그널 알림의 새 상승 시그널을 자동 매수 대상으로 넘겨받습니다.
     * 토스 토큰이 클라이언트당 1개라 내정보(Heatmap)와 같은 TossClient를 씁니다.
     */
    @Bean(destroyMethod = "stop")
    Trader trader(Heatmap heatmap, Alerts alerts, Volume volume, Russell russell, @Value("${kb.data-dir}") Path dataDir) {
        Trader t = new Trader(heatmap.toss(), dataDir.resolve("trader.db"));
        alerts.listener = (market, a) -> t.onSignal("kosdaq".equals(market) ? "KR" : "US", Values.str(a.get("code")), Values.str(a.get("name")));
        t.strategy = (m, p) -> ("KR".equals(m) ? volume.ticks : russell.ticks).live(p,  // 시뮬레이션 전략 신호 (같은 엔진, 오늘 기록)
                Boolean.TRUE.equals(p.get("alerts")) ? alerts.firstTimes("KR".equals(m) ? "kosdaq" : "russell") : null);
        t.start();
        return t;
    }

    /** 뉴욕 장중 30분마다 러셀 2000(russell2000.csv) 거래량을 russell.db에 기록. */
    @Bean(destroyMethod = "stop")
    Ticks russellTicks(Russell russell) {
        russell.ticks.start();
        return russell.ticks;
    }
}
