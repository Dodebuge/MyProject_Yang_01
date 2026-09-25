# Java 버전 (Tomcat 10, Python 없이)

`dividends_web.py` + `heatmap.py` + `dividends.py` + `kb_client.py`를 Java 서블릿으로 옮긴 코드입니다.
같은 주소, 같은 JSON을 돌려주므로 화면(`../home.html`, `../heatmap.html`, `../me.html`)은 그대로 씁니다.

| Java | Python |
|---|---|
| `KBClient.java` | `kb_client.py` (토큰 캐싱, processFlag 검사, 고정길이 값 정리, 5xx 재시도) |
| `Dividends.java` | `dividends.py`, `dividends_web.query_dividends` |
| `Heatmap.java` | `heatmap.py` (섹터 히트맵, ETF 분류·스냅숏, 분기별 거래대금, 보유 종목) |
| `AppServlet.java` | `dividends_web.py` (페이지와 `/api/*` 경로) |

- Tomcat 10.1 (jakarta.servlet 6.0), Java 11 이상. 외부 라이브러리는 Gson 하나.
- 접근 제한 없음(요청대로). 로그인이 필요하면 `web.xml`에 `security-constraint`를 추가하세요.

## 빌드와 배포

```bash
cd java-app
mvn -q package                                   # target/kb-openapi.war
cp target/kb-openapi.war /opt/tomcat/webapps/    # -> http://서버IP:8080/kb-openapi/
```

설정 폴더(기본 `/opt/kb_openapi_sample`, `web.xml`의 `dataDir` 또는 환경변수 `KB_DATA_DIR`)에

- `.env`: `KB_OPENAPI_BASE_URL`, `KB_OPENAPI_APP_KEY`, `KB_OPENAPI_APP_SECRET` (환경변수로 줘도 됨, 환경변수가 우선)
- `etf_snapshot.json`: 자동 생성. Tomcat 실행 사용자에게 쓰기 권한이 있어야 합니다.

## 확인한 것

Windows의 Tomcat 9.0.122 + JDK 17에서 Python 서버와 같은 요청으로 비교했고, jakarta로 바꾼 뒤 Tomcat 10.1.60(Java 11 대상 빌드)에서 다시 확인했습니다.

- 일치: 보유 종목(7종목, 평가금액·현금·수익률), 배당 76건 전체 행, 국내 히트맵 182종목 값, 미국 히트맵 112종목, 국내 상장 ETF 1,173개 분류
- 분기별 거래대금은 섹터 값이 0.2~2% 다를 수 있습니다. KB 차트 API가 같은 종목도 호출할 때마다 깨진 행을
  다르게 섞어 보내기 때문이며(같은 종목 3회 조회: 유효 395행, 400행, 400행), 두 구현의 계산 방식은 같습니다.

Maven이 없는 곳에서는 `javac`와 `jar`로도 만들 수 있습니다:
`WEB-INF/classes`(컴파일 결과), `WEB-INF/lib/gson-2.11.0.jar`, `WEB-INF/web.xml`, 루트에 HTML 3개.
