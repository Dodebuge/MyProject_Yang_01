# 토스증권 OpenAPI 샘플 (Python)

상위 폴더의 [KB증권 샘플](../README.md)과 같은 화면·같은 구조를 [토스증권 OpenAPI](https://developers.tossinvest.com/docs)로 만든 버전입니다.

## 파일

| 파일 | 내용 | KB 버전 |
| --- | --- | --- |
| `toss_client.py` | `TossClient`: 토큰 발급·캐싱, REST 호출, 오류 envelope → `TossApiError`, 429·5xx 재시도 | `kb_client.py` |
| `examples.py` | 현재가, 호가, 일봉, 거래량 상위, 미국 주식 현재가, 환율, 보유 주식 | `examples.py` |
| `web.py` | 로컬 웹서버: `/` 첫 화면, `/heatmap`, `/me` 페이지와 `/api/*` | `dividends_web.py` |
| `heatmap.py` | 국내·미국 섹터 히트맵, 분기별 거래대금, 내 보유 종목 데이터 | `heatmap.py` |
| `daily_store.py` | 내정보를 하루 한 번만 조회해 `toss.db`(SQLite)에 저장 | `daily_store.py` |
| `home.html` / `heatmap.html` / `me.html` | 화면. 서버가 KB 버전과 같은 JSON을 돌려줘 거의 그대로 씁니다 | 같은 이름 |

## 실행

```bash
pip install -r requirements.txt
cp .env.example .env     # client_id / client_secret 입력
python examples.py       # 콘솔 예제
python web.py            # http://localhost:8000
python heatmap.py kr     # 히트맵 섹터 합계를 콘솔로 (us도 가능)
```

토스 WTS의 설정 > Open API에서 `client_id`·`client_secret`을 발급하고, 같은 화면의 **허용 IP 관리**에 호출할 PC의 IP를 등록해야 합니다(미등록 IP는 403).

## 리눅스에서 테스트

Python 3.9 이상만 있으면 됩니다.

```bash
# 1. 코드 받기 (토스 샘플은 저장소 안의 toss_openapi_sample 폴더)
git clone https://github.com/Dodebuge/MyProject_Yang_01.git
cd MyProject_Yang_01/toss_openapi_sample
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt

# 2. 키 넣기 (git에는 없음)
cp .env.example .env && chmod 600 .env
vi .env                          # TOSS_OPENAPI_CLIENT_ID, TOSS_OPENAPI_CLIENT_SECRET

# 3. 이 서버의 공인 IP를 토스 WTS > 설정 > Open API > 허용 IP 관리에 등록
curl -s https://ifconfig.me; echo

# 4. 콘솔 확인
python3 daily_store.py           # "ok" (네트워크 불필요, SQLite 자체 점검)
python3 examples.py              # 현재가·호가·일봉·거래량 상위·NVDA·환율·보유 주식
python3 heatmap.py kr            # "103/103종목 ..." 과 섹터별 합계
python3 heatmap.py us            # "112/112종목 ..."

# 5. 웹서버 확인 (127.0.0.1에만 열림)
python3 web.py --no-open &
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8000/heatmap          # 200
curl -s 'http://127.0.0.1:8000/api/heatmap?market=kr' | head -c 300; echo       # {"market": "kr", ...
curl -s http://127.0.0.1:8000/api/holdings | head -c 300; echo                  # {"fetchedAt": ..., "stocks": [...], "cash": {...}}
kill %1
```

**내 PC 브라우저로 화면 보기**: 서버는 계좌 정보 보호를 위해 `127.0.0.1`에만 열리므로 SSH 터널을 씁니다.

```bash
# 서버에서
python3 web.py --no-open
# 내 PC에서 (Windows PowerShell도 같은 명령)
ssh -L 8000:127.0.0.1:8000 사용자@서버IP
# 그다음 내 PC 브라우저에서 http://localhost:8000
```

| 증상 | 원인 |
| --- | --- |
| `[403] ...` | 서버 IP가 허용 IP에 없음 (3단계) |
| `[401] invalid_client ... client_id/client_secret` | `.env` 키 오타·재발급 전 값 |
| `[401] token-revoked`가 반복 | 같은 키로 다른 PC·프로세스가 토큰을 계속 새로 받음. 토큰은 클라이언트당 1개만 유효하니 한 곳에서만 실행 |
| 미국 거래대금이 어제 기준 | 정상. 미국 정규장(한국시간 22:30) 전에는 직전 정규장 기준 |

## 사용법

```python
from toss_client import TossClient, TossApiError

client = TossClient.from_env()
price = client.get("/api/v1/prices", {"symbols": "005930,AAPL"})
print(price[0]["lastPrice"])                          # "270000" (숫자는 문자열로 옵니다)
holdings = client.get("/api/v1/holdings", account=True)  # 계좌 API는 account=True
```

## KB와 다른 점

| | KB | 토스 |
| --- | --- | --- |
| 토큰 | `POST /oauth2/token` JSON, appKey/appSecret | `POST /oauth2/token` form, client_id/client_secret. **클라이언트당 1개만 유효**(새로 받으면 이전 토큰은 `token-revoked`) |
| 호출 | `POST /api/v1/{TR}` + dataHeader/dataBody | REST `GET /api/v1/prices?symbols=...`, 응답 `{"result": ...}` |
| 업무 오류 | HTTP 200 + `processFlag=B` | HTTP 4xx + `{"error": {code, message, requestId}}` (토큰 발급만 OAuth 형식 `{"error", "error_description"}`) |
| 계좌 | 잔고 TR(SSQM2952, SPQM2226) | `/holdings` + `X-Tossinvest-Account: {accountSeq}` 헤더 |
| 해외 | 별도 TR, 거래소 코드 필요 | 같은 API에 티커만 (`AAPL`) |
| 호출 한도 | - | API 그룹별 초당 1~20회. 429면 `Retry-After`만큼 기다렸다 재시도 |

## 화면별 차이

- **히트맵**: 토스에는 업종·시가총액·거래대금 필드가 없습니다.
  - 종목과 섹터는 `heatmap.py`의 `KR_STOCKS`(국내 대형주 103개)·`US_STOCKS`(미국 대형주 112개) 수동 목록입니다. KB 버전은 국내를 시총·거래대금 상위로 매번 골랐습니다.
  - 시가총액 = 발행주식수(`/stocks`) × 종가, 거래대금 = 거래량 × 종가(일봉) 근삿값입니다.
  - 미국 정규장(한국시간 22:30) 전에는 오늘 일봉에 데이마켓·프리마켓 거래만 있어, 장 운영 정보(`/market-calendar/US`)로 판단해 직전 정규장 기준으로 보여 줍니다.
  - 국내 상장 ETF는 전체 목록(`/stocks/all?market=KOSPI&securityType=ETF`)을 KB 버전과 같은 이름 규칙으로 분류합니다.
- **내정보**: 보유 종목 현황만 있습니다.
  - 배당 내역은 토스에 거래내역 API가 없어 뺐고, 소수점 구매 현황·정기 구매 계획도 토스 잔고가 소수점 보유를 따로 구분하지 않아 뺐습니다.
  - 현금은 예수금 API가 없어 매수 가능 금액(`/buying-power` 원화 + 달러 × 환율)입니다.
  - 오늘 등락률은 토스 일간 손익률(`dailyProfitLoss.rate`)입니다.
  - `toss.db`에는 계좌 정보가 들어가므로 git에 올리지 않습니다(`.gitignore`).

## 범위

Java(`java-app`), Spring Boot(`spring-app`), 리눅스 배포(`deploy`), 구조 문서(`docs`)는 옮기지 않았습니다.
주문 API(`/orders`)는 실제 체결되므로 예제에 넣지 않았습니다.
