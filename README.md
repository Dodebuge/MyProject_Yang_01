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
| `me.html` | 내정보: 보유 종목 현황 + 배당 내역 |
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

```bash
python dividends_web.py          # http://localhost:8000 첫 화면이 열립니다
python dividends_web.py --port 8080 --no-open
```

- `/` 첫 화면: 히트맵과 내정보 중 선택
- `/heatmap` 섹터 히트맵 (아래 "섹터 히트맵" 참고)
- `/me` 내정보: 보유 종목 현황(총자산·수익률·현금 비율, 총자산 대비 금액 비중 히트맵, 보유 종목 표)과 배당 내역

### 배당 내역 (내정보 아래쪽)

페이지를 열거나 연도를 바꾸거나 "조회"를 누를 때마다 KB OpenAPI로 거래내역을 새로 조회합니다(10~20초).
실수령·세전·세금 요약, 월별 국내/해외 누적 막대, 종목별 배당, 월별 합계표, 상세 내역(월 선택, CSV 저장)을 보여 줍니다.
appKey/appSecret은 서버(`dividends_web.py`)에만 있고, 서버는 127.0.0.1에만 열려 다른 기기에서 접속할 수 없습니다.
화면을 바꾸려면 `me.html`만 수정하면 됩니다.

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
- 내 보유 (`/me`, `/api/holdings`): 잔고 TR(`SSQM2952` 국내, `SPQM2226` 해외·소수점 포함)로 보유 종목을 **매번 조회**하고 오늘 등락률은 `IVU10140`·`GSS10030`으로 받습니다.
  현금은 `SSQM2952`의 D+2 추정예수금 + 외화예수금 원화환산이고, 종목도 같은 D+2 기준인 결제 후 잔량(`ec_q`)으로 셉니다
  (매도 후 결제 대기 중인 종목은 빼고 그 대금은 현금에 포함). 원화 평가금액은 KB 앱과 같은 `SSQM2952`의 `val_amt`를 씁니다.
  히트맵 칸 크기 = 총자산(현금 포함) 대비 비중, 색 = 오늘 등락률(±3%) 또는 수익률(±10%). 보유 내역은 서버 메모리에 60초만 캐시하고 파일로 저장하지 않습니다.

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
