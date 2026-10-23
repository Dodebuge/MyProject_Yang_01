package kb.app;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * kb.* 설정 (application.properties). 값은 환경변수 > 데이터 폴더의 .env 순으로 옵니다 (KB_OPENAPI_APP_KEY 같은 .env 이름 그대로).
 * .env에 KEY= 처럼 빈 값만 있으면 기본값을 씁니다.
 */
@ConfigurationProperties("kb")
record KbProperties(Path dataDir, OpenApi openapi, Toss toss, Mysql mysql, Ntfy ntfy, Ssl ssl) {

    /** 데이터 폴더 안의 파일 (kb.db, trader.db, .env …) */
    Path data(String file) {
        return dataDir.resolve(file);
    }

    record OpenApi(String appKey, String appSecret, String baseUrl, String ipAddr, String macAddr) {
        OpenApi {
            baseUrl = or(baseUrl, KBClient.DEFAULT_BASE_URL);
            ipAddr = or(ipAddr, "127.0.0.1");
            macAddr = or(macAddr, "00:00:00:00:00:00");
        }
    }

    record Toss(String clientId, String clientSecret, String baseUrl, String accountSeq) {
        Toss {
            baseUrl = or(baseUrl, TossClient.DEFAULT_BASE_URL);
            accountSeq = or(accountSeq, "");
        }

        boolean configured() {
            return !or(clientId, "").isEmpty() && !or(clientSecret, "").isEmpty();
        }
    }

    record Mysql(String host, String port, String database, String user, String password) {
        Mysql {
            host = or(host, "");
            port = or(port, "3306");
            database = or(database, "kb_openapi");
            user = or(user, "");
            password = or(password, "");
        }

        /** MYSQL_HOST가 없으면 null (4시간 기록 안 함) */
        String url() {
            return host.isEmpty() ? null : "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=10000&socketTimeout=60000&characterEncoding=utf8";
        }
    }

    record Ntfy(String topic, String server, String appUrl) {
        Ntfy {
            topic = or(topic, "");
            server = or(server, "https://ntfy.sh").replaceAll("/+$", "");
            appUrl = or(appUrl, "").replaceAll("/+$", "");
        }

        static final Ntfy OFF = new Ntfy("", "", "");
    }

    record Ssl(String cert, String key) {
        boolean on() {
            return !or(cert, "").isEmpty() && !or(key, "").isEmpty();
        }
    }

    private static String or(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }
}
