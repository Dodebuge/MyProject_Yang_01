# Spring Boot 버전 (내장 Tomcat, 실행 JAR 하나)

Python 서버(`dividends_web.py`)와 같은 주소·같은 JSON을 제공합니다. 서버에 Tomcat을 따로 설치할 필요가 없습니다.

JDK 17 이상만 있으면 됩니다. 빌드 도구는 Gradle이고, `gradlew`(Gradle Wrapper)가 처음 실행할 때 Gradle 8.14.3을 자동으로 받습니다.

```bash
spring-app\run.cmd        # Windows: 빌드 후 실행 → http://localhost:8080 (.env는 저장소 루트에서 읽음)
spring-app/run.sh         # Linux/macOS: 코드가 바뀌었을 때만 빌드, CDS로 빠르게 시작
spring-app/run.sh prepare # 빌드·CDS 준비만 (systemd 서비스용)

# 직접 실행
cd spring-app && ./gradlew bootJar
KB_DATA_DIR=/opt/kb_openapi_sample java -jar build/libs/kb-openapi-spring.jar   # http://서버IP:8080/
```

**IDE에서 실행할 때**: IntelliJ는 `spring-app` 폴더(또는 `build.gradle`)를 열면 Gradle 프로젝트로 가져오고, Kotlin(`.kt`)과 Java를 함께 빌드해
`KbApplication`을 바로 실행·디버깅할 수 있습니다. 실행 구성의 환경변수에 `KB_DATA_DIR=저장소 루트`를 넣으세요.
VS Code는 Gradle for Java 확장으로 가져오거나, `run.sh`를 `JAVA_OPTS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=localhost:5005"`로 띄우고
`"request": "attach"`(port 5005)로 붙으면 됩니다.

`run.sh`는 시작 시간을 줄이려고 세 가지를 합니다 (이 PC 측정: 빌드 포함 약 5.2초 → 1.3초).

1. `build.gradle`, `src/`, 화면 HTML, `../docs/`가 jar보다 새로울 때만 Gradle 빌드 (`--no-daemon`: 서버에 빌드 JVM을 남기지 않음)
2. jar를 `build/app/`에 풀고, 한 번 띄웠다 끝내며 읽은 클래스를 `build/app/app.jsa`(CDS)로 저장해 다음 시작부터 재사용.
   jar가 바뀌면 자동으로 다시 만듭니다. JDK를 바꾸면 `build/app`을 지우세요.
3. `-XX:TieredStopAtLevel=1`: JIT를 가볍게 해서 시작을 줄임. 끄려면 `JAVA_OPTS="" ./run.sh`

리눅스 서비스 등록은 [deploy/README.md](../deploy/README.md)의 "방법 A"와 `deploy/kb-openapi-spring.service`를 보세요.

## 구조 (Spring 방식)

| 파일 | 역할 |
|---|---|
| `KbApplication.java` | `@SpringBootApplication`, `@EnableScheduling`, `@EnableAsync`, `@ConfigurationPropertiesScan`. SQLite 파일이 여럿이라 `DataSourceAutoConfiguration`은 끔 |
| `KbProperties.java` | `@ConfigurationProperties("kb")` record: 데이터 폴더, KB·토스 키, MySQL, ntfy, HTTPS |
| `application.properties` | `server.port`(`PORT`), `kb.data-dir`(`KB_DATA_DIR`), `spring.config.import`로 데이터 폴더의 `.env`를 읽고 `kb.*`에 연결. 환경변수가 `.env`보다 우선 |
| `AppConfig.java` | 설정값으로 만드는 `@Bean`: `KBClient`, `TossClient`(토스 키가 있을 때만, 쓰는 쪽은 `Optional`), SQLite 파일마다 `JdbcTemplate`(`kbDb`·`kosdaqDb`·`russellDb`·`alertsDb`·`traderDb`·`minutesDb`)과 트랜잭션 관리자(`kbTx`·`minutesTx`), 자동매매 시뮬레이션 전략, HTTPS |
| `ApiController.java` / `PageController.java` / `LastResponseFilter.java` | `/api/*`(오류 → `{"error"}` 400/502), 화면 주소, 마지막 응답 저장 |
| `@Service` | `Heatmap`, `Dividends`, `Chart`, `Monitor`, `Alerts`, `Trader`, `Minutes`, `Snapshots`, `Volume`·`Russell`(Kotlin `@Component`) |
| `@Repository` | `DailyStore`(kb.db 하루 기록), `MonitorQuotes`(시장 모니터 시세, `@Transactional("kbTx")`), `MinuteBars`(1분봉, `@Transactional("minutesTx")`) |
| `Ticks.java` | 장중 기록·시뮬레이션 엔진. 시장·파일마다 하나라 Bean이 아니고 `Volume`·`Russell`·`Alerts`가 가짐 |

주기 작업은 모두 `@Scheduled` (스레드 8개, `spring.task.scheduling.pool.size`):

| 작업 | 주기 |
|---|---|
| 코스닥 장중 기록 `Volume.recordTicks`, 알림 종목 체결강도·호가 `Alerts.recordFlow` | `30 */5 9-15 * * MON-FRI` (서울, 09:00~15:40만 저장) |
| 러셀 2000 장중 기록 `Russell.recordTicks` | `30 0,30 10-16 * * MON-FRI` (뉴욕, 10:00~16:30만) |
| 시그널 알림 `Alerts.checkKosdaq` / `checkRussell` | 5분마다 (코스닥 09:05~15:35 서울, 러셀 09:50~16:20 뉴욕) |
| 시장 모니터 시세 `Monitor.refreshQuietly` | 시작 때 + 5분 간격. 종목 목록이 바뀌면 `@Async changed()`로 곧바로 |
| 자동매매 `Trader.scheduledTick` | 5초 간격 |
| 코스닥 1분봉 `Minutes.daily` | `0 10 20 * * MON-FRI` (서울). 시작 때 `@Async @EventListener(ApplicationReadyEvent)`로 마지막 거래일 이어 받기 |
| 보유 금액 MySQL 기록 `Snapshots.record` | `0 0 */4 * * *` (서울, `MYSQL_HOST`가 있을 때만) |

- 시그널 알림 → 자동매매는 Spring 이벤트(`Alerts.Signal` → `Trader`의 `@EventListener`)로 넘깁니다.
- DB는 `JdbcTemplate`(Python 서버와 같은 SQLite 파일·같은 표). 여러 문장을 한꺼번에 바꾸는 곳만 `@Transactional`(저장소 Bean)이나 `TransactionTemplate`(`run.sh dbcheck`의 시험 쓰기 되돌림).
- 토스 계좌 합산·자동매매·1분봉 토스 절반은 `.env` 또는 환경변수에 `TOSS_OPENAPI_CLIENT_ID/SECRET`이 있을 때만 켜집니다.
- 단위 테스트: `./gradlew test` (JUnit 5, `src/test/java`). Jetson 배포(`deploy/jetson/deploy.sh`)도 이 테스트를 먼저 돌립니다.
- 화면은 저장소 루트의 `home.html`, `heatmap.html`, `me.html`, `chart.html`을 빌드할 때 `static/`으로 복사합니다.
- `kb.data-dir` 폴더에 `.env`(appKey/appSecret)를 두고, `etf_snapshot.json`·`*.db`가 여기에 저장됩니다. 환경변수가 `.env`보다 우선합니다.
- 접근 제한 없음. 필요하면 `spring-boot-starter-security`를 추가하세요.

## 확인한 것

Spring Boot 3.5.16 + JDK 17로 빌드해 Python 서버와 같은 요청으로 비교했습니다.

- 일치: 화면 3개, 보유 종목(7종목·평가금액·현금), 배당 76건 전체 행, 미국 히트맵 112종목, 국내 상장 ETF 1,173개, `market=xx`·`year=abc` → 400
- 히트맵 값 일부와 분기별 거래대금은 조회 시점과 KB 응답 불안정(간헐적 500, 깨진 행) 때문에 조금씩 다를 수 있습니다. 계산 방식은 같습니다.
