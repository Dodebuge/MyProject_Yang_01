# Spring Boot 버전 (내장 Tomcat, 실행 JAR 하나)

Python 서버(`dividends_web.py`)와 같은 주소·같은 JSON을 제공합니다. 서버에 Tomcat을 따로 설치할 필요가 없습니다.

JDK 17 이상만 있으면 됩니다. Maven은 `mvnw`(Maven Wrapper)가 처음 실행할 때 자동으로 받습니다.

```bash
spring-app\run.cmd        # Windows: 빌드 후 실행 → http://localhost:8080 (.env는 저장소 루트에서 읽음)
spring-app/run.sh         # Linux/macOS

# 직접 실행
cd spring-app && ./mvnw -q -DskipTests package
KB_DATA_DIR=/opt/kb_openapi_sample java -jar target/kb-openapi-spring.jar   # http://서버IP:8080/
```

리눅스 서비스 등록은 [deploy/README.md](../deploy/README.md)의 "방법 A"와 `deploy/kb-openapi-spring.service`를 보세요.

| 파일 | 역할 |
|---|---|
| `KbApplication.java` | `@SpringBootApplication`, `KBClient`·`Heatmap`·`Dividends`를 Bean으로 등록 |
| `ApiController.java` | `@RestController` `/api/heatmap`, `/api/quarters`, `/api/holdings`, `/api/dividends`, 오류 → `{"error"}` (400/502) |
| `PageController.java` | `/`, `/heatmap`, `/me` → `static/*.html` |
| `application.properties` | `server.port`(환경변수 `PORT`), `kb.data-dir`(환경변수 `KB_DATA_DIR`) |

- 핵심 로직은 새로 쓰지 않고 `../java-app/src/main/java`(KBClient, Heatmap, Dividends)를 함께 컴파일합니다
  (`build-helper-maven-plugin`). 로직을 고치면 Tomcat WAR 버전과 Spring 버전에 같이 반영됩니다.
- 화면은 저장소 루트의 `home.html`, `heatmap.html`, `me.html`을 빌드할 때 `static/`으로 복사합니다.
- `kb.data-dir` 폴더에 `.env`(appKey/appSecret)를 두고, `etf_snapshot.json`이 여기에 저장됩니다. `KB_OPENAPI_*` 환경변수가 `.env`보다 우선합니다.
- 접근 제한 없음. 필요하면 `spring-boot-starter-security`를 추가하세요.

## 확인한 것

Spring Boot 3.5.16 + JDK 17(Maven 3.9.16)로 빌드해 Python 서버와 같은 요청으로 비교했습니다.

- 일치: 화면 3개, 보유 종목(7종목·평가금액·현금), 배당 76건 전체 행, 미국 히트맵 112종목, 국내 상장 ETF 1,173개, `market=xx`·`year=abc` → 400
- 히트맵 값 일부와 분기별 거래대금은 조회 시점과 KB 응답 불안정(간헐적 500, 깨진 행) 때문에 조금씩 다를 수 있습니다. 계산 방식은 같습니다.
