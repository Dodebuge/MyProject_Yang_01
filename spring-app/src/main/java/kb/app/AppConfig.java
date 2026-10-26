package kb.app;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 설정값으로 만드는 Bean: KB·토스 클라이언트, 데이터 폴더의 SQLite 저장소(JdbcTemplate)와 트랜잭션 관리자, HTTPS.
 * 나머지(Heatmap, Ticks를 가진 Volume·Russell, Alerts, Trader …)는 각 클래스의 @Component·@Service로 등록됩니다.
 */
@Configuration
class AppConfig {

    AppConfig(KbProperties p) {
        SimModules.rulesFile = p.data("sim_buy_rules.json");  // 시뮬레이션·자동매매 매수 조건 (모니터 화면에서 편집, SimModules는 정적 도우미)
    }

    @Bean
    KBClient kbClient(KbProperties p) {
        KbProperties.OpenApi o = p.openapi();
        if (o.appKey() == null || o.appKey().isBlank() || o.appSecret() == null || o.appSecret().isBlank()) {
            throw new IllegalStateException("KB_OPENAPI_APP_KEY / KB_OPENAPI_APP_SECRET 환경변수 또는 " + p.data(".env") + "를 설정하세요.");
        }
        return new KBClient(o.appKey(), o.appSecret(), o.baseUrl(), o.ipAddr(), o.macAddr());
    }

    /**
     * 토스 키(TOSS_OPENAPI_CLIENT_ID/SECRET)가 있을 때만. 토큰이 클라이언트당 1개라 내정보·자동매매·1분봉이 이 Bean 하나를 같이 씁니다.
     * 없으면 Bean이 없고, 쓰는 쪽은 Optional로 받아 토스 없이 동작합니다.
     */
    @Bean
    @ConditionalOnExpression("!'${kb.toss.client-id:}'.isBlank() && !'${kb.toss.client-secret:}'.isBlank()")
    TossClient tossClient(KbProperties p) {
        KbProperties.Toss t = p.toss();
        return new TossClient(t.clientId(), t.clientSecret(), t.baseUrl(), t.accountSeq());
    }

    /** 자동매매 시계 (테스트는 가짜 시계로 Trader를 직접 만듦) */
    @Bean
    Supplier<ZonedDateTime> clock() {
        return () -> ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    /** 자동매매 시뮬레이션 전략 신호: 시뮬레이션 화면과 같은 엔진(Ticks.live)을 오늘 기록으로. 알림 종목만이면 알림 시각부터 */
    @Bean
    Trader.Strategy simStrategy(Volume volume, Russell russell, Alerts alerts) {
        return (m, p) -> ("KR".equals(m) ? volume.ticks : russell.ticks).live(p,
                Boolean.TRUE.equals(p.get("alerts")) ? alerts.firstTimes("KR".equals(m) ? "kosdaq" : "russell") : null);
    }

    /**
     * 자동매매 단타 전략 신호: 코스닥 5분 기록(마지막 기록의 전날 같은 시각 대비 거래량)으로 후보를 고르고,
     * 후보·보유 종목의 오늘 KB 1분봉(4개씩 동시에)으로 Scalp.live -> {date, at, buy, sell, checked}
     */
    @Bean
    @SuppressWarnings("unchecked")
    Trader.Scalper scalper(Volume volume, Minutes minutes, Supplier<ZonedDateTime> clock) {
        return (params, held) -> {
            Map<String, Object> rec = volume.ticks.intraday("", "");
            ZonedDateTime now = clock.get().withZoneSameInstant(ZoneId.of("Asia/Seoul"));
            List<Map<String, Object>> stocks = rec.get("stocks") instanceof List ? (List<Map<String, Object>>) rec.get("stocks") : List.of();
            Map<String, Object> out = Scalp.live(stocks, codes -> {
                List<List<List<Object>>> got = Values.parallel(codes, 4, code -> minutes.bars(code, now.toLocalDate()));
                Map<String, List<List<Object>>> m = new HashMap<>();
                for (int i = 0; i < codes.size(); i++) {
                    m.put(codes.get(i), got.get(i));
                }
                return m;
            }, Scalp.params(params), now.getHour() * 60 + now.getMinute(), held, Trader.SCALP_MAX_CANDS);
            out.put("date", rec.get("date"));
            out.put("at", rec.get("at"));
            return out;
        };
    }

    // ------------------------------------------------------------------ SQLite (Python 서버와 같은 파일)

    /** 내정보 하루 기록(daily), 시장 모니터 시세(monitor_quotes) */
    @Bean
    JdbcTemplate kbDb(KbProperties p) {
        return Sqlite.jdbc(p.data("kb.db"));
    }

    @Bean
    PlatformTransactionManager kbTx(@Qualifier("kbDb") JdbcTemplate db) {
        return new DataSourceTransactionManager(db.getDataSource());
    }

    /** 코스닥 장중 5분 기록 */
    @Bean
    JdbcTemplate kosdaqDb(KbProperties p) {
        return Sqlite.jdbc(p.data("kosdaq.db"));
    }

    /** 러셀 2000 장중 30분 기록 */
    @Bean
    JdbcTemplate russellDb(KbProperties p) {
        return Sqlite.jdbc(p.data("russell.db"));
    }

    /** 시그널 알림, 알림 종목 체결강도·호가 기록 */
    @Bean
    JdbcTemplate alertsDb(KbProperties p) {
        return Sqlite.jdbc(p.data("alerts.db"));
    }

    /** 토스 자동매매 설정·주문·기록 */
    @Bean
    JdbcTemplate traderDb(KbProperties p) {
        return Sqlite.jdbc(p.data("trader.db"));
    }

    /** 코스닥 전 종목 1분봉 */
    @Bean
    JdbcTemplate minutesDb(KbProperties p) {
        return Sqlite.jdbc(p.data("minutes.db"));
    }

    @Bean
    PlatformTransactionManager minutesTx(@Qualifier("minutesDb") JdbcTemplate db) {
        return new DataSourceTransactionManager(db.getDataSource());
    }

    // ------------------------------------------------------------------ HTTPS

    /**
     * HTTPS: .env(또는 환경변수)에 SSL_CERT·SSL_KEY(PEM 인증서·개인 키 경로, 상대 경로는 데이터 폴더 기준)가 있으면 PORT를 https로 엽니다.
     * 브라우저 알림(거래량 페이지 🔔)은 https 주소나 localhost에서만 됩니다. 인증서는 deploy/make-cert.sh 또는 deploy/README.md 'HTTPS'.
     * 파일이 없거나 깨졌으면 시작할 때 오류로 멈춥니다 (http로 몰래 열리지 않게).
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> https(KbProperties p) {
        return factory -> {
            if (!p.ssl().on()) {
                return;
            }
            Ssl ssl = new Ssl();
            ssl.setCertificate("file:" + p.data(p.ssl().cert().trim()).toAbsolutePath());
            ssl.setCertificatePrivateKey("file:" + p.data(p.ssl().key().trim()).toAbsolutePath());
            factory.setSsl(ssl);
        };
    }
}
