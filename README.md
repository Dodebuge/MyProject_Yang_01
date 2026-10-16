# KB증권 OpenAPI 샘플 (Python)

[kbsecurities/kb-openapi](https://github.com/kbsecurities/kb-openapi)의 `example/python` 호출 방식을 따라 만든 시세 조회 예제입니다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `kb_client.py` | `KBClient`: 토큰 발급·캐싱, TR 호출, 업무 오류 판별, 고정길이 값 정리 |
| `examples.py` | 현재가, 호가, 일봉, 거래량 상위, 해외주식 현재가, 환율 조회 예제 |
| `dividends.py` | 배당 내역 조회·월별 집계 (콘솔 출력, CSV 저장) |
| `dividends_web.py` | 로컬 웹서버: `/` 첫 화면, `/heatmap`, `/me` 페이지와 `/api/*` |
| `home.html` | 첫 화면: 히트맵 / 내정보 선택 |
| `chart.py` / `chart.html` | 종목 검색(이름·종목번호·미국 티커) + 일봉 차트 (같은 서버의 `/chart`) |
| `volume.py` / `russell.py` / `volume.html` | 코스닥·러셀 2000 거래량 증가 종목 (같은 서버의 `/volume`) |
| `me.html` | 내정보: 보유 종목 현황 + 정기 구매 + 최근 1달 거래내역 + 배당 내역 |
| `deploy/` | 리눅스 서버 배포: Tomcat 10 프록시 WAR + Python 서버 systemd 서비스 ([deploy/README.md](deploy/README.md)) |
| `java-app/` | Python 없이 Tomcat 10에서 도는 Java 버전 ([java-app/README.md](java-app/README.md)) |
| `spring-app/` | Spring Boot 버전, 내장 Tomcat 실행 JAR ([spring-app/README.md](spring-app/README.md)) |
| `../toss_openapi_sample/` | 같은 화면을 토스증권 OpenAPI로 만든 버전 ([toss_openapi_sample/README.md](../toss_openapi_sample/README.md), 리눅스 테스트 방법 포함) |
| `heatmap.py` / `heatmap.html` | 국내·미국 섹터 히트맵 (같은 서버의 `/heatmap`) |

## 실행

```bash
pip install -r requirements.txt
python examples.py --offline   # ../all_kbstock_sample_V2 샘플 응답으로 실행, 키 불필요
cp .env.example .env           # appKey/appSecret 입력
python examples.py             # 운영 서버 호출
```

## 배당금 월별 조회

```bash
python dividends.py                      # 올해 월별 합계 + 종목별 합계
python dividends.py --month 7            # 7월 상세 내역
python dividends.py --year 2025          # 다른 연도
python dividends.py --csv dividends.csv  # 상세 내역 CSV 저장 (엑셀용)
```

계좌 거래내역(`SWQA2301`)에서 `배당금 입금`, `해외원천세 출금`, `배당세금추징 출금`, `해외원천세 환급 입금`을 골라 월별로 합산합니다.
국내 배당과 ETF 분배금, 해외 배당, 세금이 모두 들어가고, 해외 금액은 거래일 환율로 원화 환산합니다.
거래내역을 한 페이지에 6건씩 넘겨 받기 때문에 거래가 많으면 10~20초 걸립니다.

## 웹 화면

Spring Boot로 실행합니다 (JDK 17 이상만 있으면 됨, Maven은 `mvnw`가 자동으로 받음).

```bash
spring-app\run.cmd              # Windows → http://localhost:8080
spring-app/run.sh               # Linux/macOS
```

Python으로 실행해도 같은 화면입니다.

```bash
python dividends_web.py          # http://localhost:8000
python dividends_web.py --port 8080 --no-open
```

- `/` 첫 화면: 히트맵과 내정보 중 선택
- `/heatmap` 섹터 히트맵 (아래 "섹터 히트맵" 참고)
- `/chart` 종목 차트: 종목이름·종목번호·티커로 검색해 최근 약 1년 일봉(캔들·거래량)을 그립니다. `/chart?market=kr&code=005930`처럼 주소로 바로 열 수 있습니다.
- `/volume` 코스닥·러셀 2000 거래량 증가: 아래 "코스닥 거래량 증가", "러셀 2000 거래량 증가" 참고
- `/me` 내정보: 보유 종목 현황(총자산·수익률·현금 비율, 총자산 대비 금액 비중 히트맵, 보유 종목 표)과 배당 내역

### 배당 내역 (내정보 아래쪽)

페이지를 열거나 연도를 바꾸거나 "조회"를 누를 때마다 KB OpenAPI로 거래내역을 새로 조회합니다(10~20초).
실수령·세전·세금 요약, 월별 국내/해외 누적 막대, 종목별 배당, 월별 합계표, 상세 내역(월 선택, CSV 저장)을 보여 줍니다.
appKey/appSecret은 서버(`dividends_web.py`)에만 있고, 서버는 127.0.0.1에만 열려 다른 기기에서 접속할 수 없습니다.
화면을 바꾸려면 `me.html`만 수정하면 됩니다.

## 종목 차트

`python dividends_web.py` 실행 후 http://localhost:8000/chart

- 검색(`/api/search?q=`): 국내는 전체 상장 종목(`IVS10920`, 1시간 캐시)의 이름·종목번호, 미국은 티커. KB OpenAPI에 미국 종목 목록 TR이 없어 영문 티커 모양이면 후보로 내고, 차트를 열 때 나스닥(`NAS`)·뉴욕(`NYS`)·아멕스(`AMX`, NYSE Arca ETF 포함) 순으로 찾습니다.
- 차트(`/api/chart?market=kr|us&code=`): 국내 `IVS11560`(코스피·코스닥은 `IVM10050` 지수명으로 구분), 미국 `GSC10060` 일봉 250거래일. 1M·3M·6M·1Y 기간, 마우스를 올리면 시고저종·등락률·거래량. 점검: `python chart.py`
- 보조지표: 이평선 5·20·60·120일, 볼린저밴드(20일, ±2σ)를 체크박스로 켜고 끕니다. 지표는 1년치로 계산해 보이는 기간만 그립니다.
- 차트 설정(`GET/POST /api/settings`): 기간과 지표 체크 상태를 바꿀 때마다 `chart_settings.json`(`.env`와 같은 폴더)에 저장하고, 다음에 열 때 그대로 씁니다. 모르는 키·잘못된 값은 버리고, 파일이 없거나 깨졌으면 기본값(6M, 5·20·60일선)으로 엽니다.
- 최근 검색(`/api/recent`): 차트를 연 종목을 `recent_searches.txt`(`.env`와 같은 폴더)에 최신순으로 10개까지 저장해 검색창 아래에 칩으로 보여 줍니다. 같은 종목은 앞으로 옮기고, 10개를 넘으면 오래된 것부터 지웁니다. 한 줄에 `market<탭>code<탭>exchange<탭>name` 형식이라 직접 고치거나 지워도 됩니다. 개수는 `chart.py`의 `RECENT_MAX`와 `Chart.java`의 `RECENT_MAX`.

## 코스닥 거래량 증가

`python dividends_web.py` 실행 후 http://localhost:8000/volume . 위쪽에서 **같은 시각**과 **주간 평균** 보기를 고릅니다.
두 보기 모두 기준(%)·주가 상승만·스팩 제외·종목 이름으로 거르고 열 제목으로 정렬하며, 행을 누르면 차트가 열립니다.

### 같은 시각 (10분 기록)

- 서버가 켜져 있으면 장중(평일 09:00~15:40) 10분마다(정각 30초 뒤) 코스닥 전 종목의 누적 거래량·현재가·등락률을 `IVU10210` 한 번으로 받아
  `kosdaq.db`(`.env`와 같은 폴더, git에 올리지 않음)에 저장합니다. 휴장일(오늘 일봉이 없음)에는 저장하지 않고, 14일이 지난 기록은 지웁니다(하루 약 4MB).
- `/api/kosdaq-intraday?date=&at=`: 고른 시각까지의 누적 거래량을 **전일**(직전 기록일)과 **전주**(7일 전, 휴장이면 그 전 기록일)의 같은 시각과 비교합니다.
  같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록과 비교합니다. KB를 부르지 않고 저장된 값만 읽습니다.
- 기록이 쌓이려면 서버가 장중에 켜져 있어야 합니다. 전일 비교는 이틀째, 전주 비교는 8일째부터 보입니다.
- Python·Java·Spring 서버가 같은 `kosdaq.db`를 쓰고, 같은 시각을 두 서버가 기록하면 나중 값으로 덮어씁니다.

### 주간 평균

- `/api/kosdaq-volume`: 코스닥 전 종목(거래대금 순위 `IVU10210` `mkt_clsf=3`, 약 1,700종목)의 최근 15거래일 일봉(`IVS11560`)을 받아
  주(월~일)별로 묶고, 이번 주와 전주의 **하루 평균 거래량**을 비교합니다. 휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 평균을 씁니다.
- 30초 남짓 걸려 10분 동안은 같은 값을 돌려줍니다(`?refresh=1`이면 다시 조회). 화면은 저장된 마지막 응답을 먼저 그립니다.
- KB 서버는 동시에 많이 부르면 가끔 필드가 밀린 일봉이나 500 오류를 돌려줘서, 날짜·거래량이 이상한 응답은 세 번까지 다시 부릅니다.
  값은 멀쩡해 보이지만 틀린 응답도 1,700종목에 한두 개꼴로 있어, 숫자가 이상하면 차트에서 확인하세요.
- 점검: `python volume.py --offline`(주간 계산·10분 시각·같은 시각 비교 자체 점검), `python volume.py`(실제 조회해 상위 20개 출력)

## 러셀 2000 거래량 증가

http://localhost:8000/volume?market=russell (거래량 페이지 위쪽의 **러셀 2000**). 코스닥과 같은 두 보기·같은 거르기를 씁니다.

- **종목 목록**: KB OpenAPI에는 미국 종목 목록·순위 TR이 없어 `russell2000.csv`(`.env`와 같은 폴더, git에 올리지 않음)에서 읽습니다.
  [iShares IWM](https://www.ishares.com/us/products/239710/ishares-russell-2000-etf) 페이지 Holdings의 **Download**(CSV)를 받아 이름만 바꿔 넣으면 됩니다
  (Asset Class가 Equity인 행만 씀). `Ticker` 열(선택: `Name`, `Exchange`)만 있는 CSV도 됩니다. 구성 종목이 바뀌면(연 1~2회 리밸런싱) 파일만 바꾸세요.
  서버에서 자동으로 받으려 하면 iShares가 막아서 직접 넣는 방식입니다. 파일이 없으면 화면에 넣는 방법이 나옵니다.
- **거래소·티커**: CSV의 Exchange 열(NASDAQ, New York Stock Exchange 등)을 먼저 쓰고, 틀리면 NAS·NYS·AMX 순으로 찾습니다.
  iShares 표기(`MOGA`)와 KB 표기(`MOG.A`)가 다르면 마지막 글자 앞에 점을 넣어 한 번 더 찾습니다. 화면의 종목 코드는 KB 티커라 차트로 바로 열립니다.
- **같은 시각**: 뉴욕 장중(평일 10:00~16:30 **뉴욕 시간**, 한국 밤 23:00~05:30 무렵, 서머타임이 끝나면 한 시간 늦게) 30분마다
  종목별 상세시세(`GSS10030`, 약 2,000번 호출)를 `russell.db`에 저장합니다. 그날(뉴욕 날짜) 체결이 없으면(휴장) 저장하지 않습니다.
  KB 미국 시세는 15분 지연이라 10:00 기록은 09:45 무렵 값이고, 16:30 기록에 종가까지 담깁니다. 화면에는 뉴욕 시각과 한국 시각을 함께 보여 줍니다.
- **주간 평균**: 종목별 일봉(`GSC10060`)으로 이번 주·전주 하루 평균 거래량을 비교합니다(`/api/russell-volume`, 10분 캐시).
- 점검: `python russell.py --offline`(CSV 읽기·거래소 이름·기록 시각 자체 점검), `python russell.py`(실제 조회해 상위 20개 출력)
- CSV를 넣은 뒤 종목이 KB에서 다 찾아지는지 확인: `python russell.py --check` 또는 Java 단위 테스트
  `../spring-app/mvnw test -Dtest=RussellTest -Drussell.live=true`(java-app 폴더에서, 서버면 `-Dkb.data.dir=/opt/kb_openapi_sample` 추가).
  몇 종목 중 몇 종목을 찾았는지, 표기를 바꿔 찾은 종목(`MOGA -> NYS:MOG.A`), 못 찾은 종목을 출력하고, 95% 미만이면 실패합니다.

## 첫 로딩 (마지막 응답 먼저 보여 주기)

히트맵·분기별 거래대금·보유 종목·배당·정기 구매·최근 거래내역 API는 성공할 때마다 응답을 `last_api/<API>[_<파라미터>].txt`(`.env`와 같은 폴더)에 덮어써 둡니다.
화면은 먼저 `?cached=1`로 이 값을 받아 바로 그리고("이전 조회 값 · 새로 불러오는 중…" 표시), 실제 API 응답이 오면 교체합니다.
새 조회가 실패하면 이전 값을 그대로 두고 실패 이유를 표시합니다. "KB에서 다시 조회"·"새로고침"은 이전 값 없이 바로 조회합니다.
파일은 계좌 정보가 들어 있어 git에 올리지 않습니다(`.gitignore`). 지워도 다음 조회 때 다시 만들어집니다.

## 섹터 히트맵

`python dividends_web.py` 실행 후 http://localhost:8000/heatmap (내 보유 종목은 `/me`)

- 국내: 시가총액 상위 200(`IVS10920`) + 거래대금 상위 100(`IVU10210`) 종목의 업종·시총·거래대금(`IVM10050`). ETF·ETN 제외. 약 5초.
- 미국: KB OpenAPI에 섹터·순위 TR이 없어 `heatmap.py`의 `US_STOCKS`(대형주 112개, GICS 섹터)를 `GSS10030`으로 조회. 15분 지연 시세.
- 한 화면에 거래대금과 시가총액을 함께 봅니다. 칸 크기 = 섹터 거래대금 합계, 칸 아래 줄 = 거래대금 · 시가총액, 색 = 섹터 시가총액의 전일 대비 등락률
  (오늘 시총 합 ÷ 전일 시총 합 − 1, 빨강 상승·파랑 하락, ±3%에서 가장 진함). 섹터를 누르면 종목 히트맵. 결과는 서버에서 60초 캐시.
- 히트맵 아래 분기별 거래대금(`/api/quarters`): 히트맵 종목마다 일봉(국내 `IVS11560`, 미국 `GSC10060`)을 약 400거래일 받아
  최근 5개 분기의 일평균 거래대금(분기 합계 ÷ 거래일수)을 섹터별로 합산하고, 전 분기·전년 동기 대비 변화를 보여 줍니다. 10분 캐시, 10~20초.
  국내 차트의 거래대금은 10원 단위라 10을 곱합니다. 필드가 밀린 행(날짜·금액이 깨진 행)은 건너뜁니다.
- 국내 상장 ETF 섹터별 개수: 전체 상장 종목(`IVS10920`, 약 4,300개)에서 운용사 브랜드로 ETF를 골라(ETN 제외) `heatmap.py`의 `ETF_RULES` 이름 키워드로 섹터를 나눕니다.
  조회할 때마다 ETF 코드 목록을 `etf_snapshot.json`에 날짜별로 저장하고, 직전 날짜 대비 새로 상장된 ETF 수를 "신규"로 보여 줍니다.
- 내 보유 (`/me`, `/api/holdings`): 잔고 TR(`SSQM2952` 국내, `SPQM2226` 해외·소수점 포함)로 보유 종목을 조회하고 오늘 등락률은 `IVU10140`·`GSS10030`으로 받습니다.
- 토스 계좌 합산: 저장소 루트 `.env`(또는 환경변수)에 `TOSS_OPENAPI_CLIENT_ID/SECRET`이 있으면 토스 보유 종목·매수 가능 금액(`fetch_my`)도 함께 조회해 종목마다 `KB`/`토스` 태그로 보여 줍니다. 토스 조회가 실패해도 KB 값은 보여 줍니다.
- 정기 구매 내역 (`/api/recurring`): 거래내역 TR(`SWQA2301`) 최근 반년의 소수단위매수(`A23`)를 종목별로 모아 주기(추정)·최근 30일·기간 합계를 보여 줍니다. KB에 정기 구매 설정 조회 TR이 없어 체결 내역 기준이고, 토스 OpenAPI에는 정기 구매·거래내역 API가 없어 토스는 빠집니다. 점검: `python test_recurring.py`
- 최근 1달 거래내역 (`/api/transactions`): 거래내역 TR(`SWQA2301`) 최근 30일 전체를 최신순으로 보여 주고 구분(적요)으로 거를 수 있습니다. 토스는 거래내역 API가 없어 빠집니다.
  현금은 `SSQM2952`의 D+2 추정예수금 + 외화예수금 원화환산이고, 종목도 같은 D+2 기준인 결제 후 잔량(`ec_q`)으로 셉니다
  (매도 후 결제 대기 중인 종목은 빼고 그 대금은 현금에 포함). 원화 평가금액은 KB 앱과 같은 `SSQM2952`의 `val_amt`를 씁니다.
  히트맵 칸 크기 = 총자산(현금 포함) 대비 비중, 색 = 오늘 등락률(±3%) 또는 수익률(±10%). 보유 내역과 배당 내역은 하루(한국 날짜) 한 번만 KB를 조회해 `kb.db`(SQLite, `.env`와 같은 폴더)에 저장하고, 같은 날은 저장된 값을 돌려줍니다.
  `kb.db`에는 계좌 정보가 들어가므로 git에 올리지 않고(`.gitignore`) 파일 권한을 `chmod 600`으로 두세요.

## 사용법

```python
from kb_client import KBClient, KBApiError

client = KBClient.from_env()
price = client.call("IVU10140", {"excg_clsf": "1", "shrt_cd": "005930"})
print(price["is_nm"], price["now_prc"])    # 삼성전자 368500 (숫자로 변환됨)
```

- 엔드포인트: `POST /api/v1/{TR코드 소문자}`
- 헤더: `appKey`, `Authorization: bearer <token>` (소문자 bearer)
- 토큰은 `expires_in`(24시간)까지 재사용하고, 만료 60초 전에 다시 발급합니다.
- **HTTP 200이어도 실패일 수 있습니다.** 정상 응답은 `dataHeader.processFlag`가 `A`이고, 주문 오류나 자료 없음 같은 업무 오류는 `B`로 옵니다. `B`이면 `KBApiError`를 발생시킵니다.
- `call(..., raw=True)`를 쓰면 값 정리 없이 원본 응답을 받습니다.

## 범위

원본 저장소가 규격(입력 필드와 경로)을 공개한 **투자정보 TR 29종**만 대상으로 합니다.
계좌 조회(SSQM·SPQM 등)와 주문(SSAM·SKAM 등) TR은 저장소에 규격이 없고, 계좌번호와 비밀번호를 전달하는 방식도 확인되지 않아 넣지 않았습니다.
운영 서버만 있어서 주문 TR은 실제로 체결됩니다.
