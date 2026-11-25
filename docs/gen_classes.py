"""spring-app 클래스별 설명·코드 HTML 생성: python docs/gen_classes.py -> docs/classes.html
설명은 각 파일의 클래스 Javadoc(/** ... */)을 그대로 씁니다. 코드를 고치면 다시 실행하세요."""
import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "spring-app" / "src"
OUT = Path(__file__).resolve().parent / "classes.html"

# 화면 왼쪽 묶음 (여기 없는 파일은 '기타')
GROUPS = {
    "시작·설정": ["KbApplication", "KbProperties", "AppConfig", "Sqlite"],
    "웹": ["ApiController", "PageController", "LastResponseFilter", "LastResponse"],
    "API 클라이언트": ["KBClient", "TossClient", "KBApiException", "Values"],
    "내정보·시세": ["Heatmap", "Dividends", "Chart", "DailyStore", "Monitor", "MonitorQuotes", "Snapshots"],
    "거래량·장중 기록": ["Volume", "Russell", "Ticks", "Minutes", "MinuteBars"],
    "시그널·매매": ["Signals", "Alerts", "SimModules", "Scalp", "Trader"],
}

# 클래스별 SLI (README 'SLO / SLI'). 손으로 씀: 등급·목표가 바뀌면 README와 같이 고치세요
# 등급 T0 자동매매 · T1 장중 기록 · T2 알림 · T3 화면·조회 · 기반 = 다른 SLI의 원인 쪽
# 값: (등급, [(SLI = 좋은 이벤트 / 전체, 목표, 측정 위치), ...])
SLI = {
    "KbApplication": ("기반", [
        ("정규장 중 9080이 응답한 분 / 정규장 분", "99.5%", "deploy.sh `curl /api/settings`, `journalctl -u kb-openapi-spring | grep Started`")]),
    "KbProperties": ("기반", [
        ("시작할 때 필요한 키(KB·토스·ntfy)가 채워진 배포 / 배포", "100%", "키가 비면 기능이 조용히 꺼짐(토스 합산·자동매매·ntfy) → 시작 로그")]),
    "AppConfig": ("기반", [
        ("배포 뒤 5분 안에 Bean 생성·9080 응답 / 배포", "100%", "deploy.sh 응답 확인 60회")]),
    "Sqlite": ("기반", [
        ("busy timeout 30초 안에 끝난 쓰기 / 쓰기", "99.9%", "로그의 `database is locked` (Python 서버와 같이 쓰면 늘어남)")]),
    "ApiController": ("T3", [
        ("5xx가 아닌 /api/* 응답 / 전체 (평일 08:50~16:00)", "99%", "`access_log/access.*.log` → error_budget.py 매일 16:00"),
        ("KB 실조회 지연 p95", "히트맵 < 10s · 배당 < 30s · 주간 평균 첫 조회 < 90s", "같은 접근 로그 (%D µs), 목표 안 비율 95% = p95")]),
    "PageController": ("T3", [
        ("HTML 200 / 요청", "99.9%", "정적 파일이라 프로세스 가용성과 같음")]),
    "LastResponseFilter": ("T3", [
        ("`?cached=1` 200 / `?cached=1` 요청 (처음 실행 뒤)", "95%", "404면 화면이 KB 응답까지 빈 채로 기다림"),
        ("캐시 응답 지연 p95", "< 500ms", "접근 로그 `?cached=1` → error_budget.py")]),
    "LastResponse": ("T3", [
        ("마지막 조회가 1거래일 안인 last_api 파일 / 파일", "95%", "`ls -l last_api/` 수정 시각")]),
    "KBClient": ("기반", [
        ("재시도 뒤 HTTP 2xx이고 processFlag A(또는 '자료 없음') / KB 호출", "99%", "IOException·KBApiException 로그. 5xx 재시도 3회(0.5·1·2초)"),
        ("호출 지연 p95", "< 2s (타임아웃 10s)", "측정 없음 → 호출 시간 로그 필요")]),
    "TossClient": ("기반", [
        ("2xx / 토스 호출 (토큰 재발급 1회 재시도 포함)", "99%", "trader.log의 `주문 거절`·`주문 응답 없음`"),
        ("토큰 발급 실패", "0건 / 일", "허용 IP 미등록이면 전부 실패")]),
    "KBApiException": ("기반", [
        ("processFlag B 중 '자료 없음'이 아닌 업무 오류", "KBClient SLI의 나쁜 이벤트로 셈", "직접 SLI 없음")]),
    "Values": ("기반", [
        ("파싱 가능한 행 / 받은 행", "99.9%", "KB가 필드가 밀린 행을 1,700종목에 1~2개꼴로 줌 → 건너뛴 행 수")]),
    "Heatmap": ("T3", [
        ("/api/heatmap·quarters 성공 / 요청", "99%", "60초·10분 캐시"),
        ("지연 p95", "heatmap < 10s · quarters < 30s", "현재 약 5초, 10~20초")]),
    "Dividends": ("T3", [
        ("/api/dividends·recurring·transactions 성공 / 요청", "99%", "거래내역을 6건씩 넘겨 받아 KB 오류에 약함"),
        ("지연 p95", "< 30s", "현재 10~20초")]),
    "Chart": ("T3", [
        ("/api/chart 성공 / 요청", "99%", "미국은 NAS·NYS·AMX 순으로 찾음"),
        ("지연 p95", "search < 500ms (1시간 캐시) · chart < 3s", "접근 로그")]),
    "DailyStore": ("T3", [
        ("같은 날 두 번째부터 KB를 부르지 않은 요청 / 요청", "99%", "kb.db `daily`의 (kind, date) 행")]),
    "Monitor": ("T3", [
        ("장중 확인 때 `fetched_at`이 6분 안인 monitor_quotes / 확인", "99%", "`SELECT key, fetched_at FROM monitor_quotes` (kb.db)")]),
    "MonitorQuotes": ("T3", [
        ("monitor_quotes 쓰기 성공 / 5분 갱신", "99%", "Monitor SLI의 저장 쪽")]),
    "Snapshots": ("T3", [
        ("저장된 4시간 기록 / 하루 6회 (MYSQL_HOST 있을 때)", "100%", "MySQL `holdings_snapshot` 날짜별 개수")]),
    "Volume": ("T1", [
        ("kosdaq_tick 저장 슬롯 / 장 열린 날 81슬롯", "98% (에러 예산 기준)", "`deploy/jetson/error_budget.py`, error-budget.yml"),
        ("/api/kosdaq-volume 첫 조회 지연 p95", "< 90s (10분 캐시)", "접근 로그")]),
    "Russell": ("T1", [
        ("russell_tick 저장 슬롯 / 장 열린 날 14슬롯 (뉴욕)", "93%", "`SELECT substr(at,1,10), count(*) FROM russell_tick GROUP BY 1`"),
        ("KB에서 찾은 종목 / russell2000.csv 종목", "95%", "`RussellTest -Drussell.live=true`")]),
    "Ticks": ("T1", [
        ("슬롯 시각 → 저장까지 2분 안 / 슬롯", "99%", "기록은 정각 30초 뒤, 코스닥 81·러셀 14·kosdaq_flow"),
        ("현재가·등락률이 맞는 기록 / 기록", "99.9%", "KB가 자릿수 빠진 현재가를 주면 그날 계산에서 뺌")]),
    "Minutes": ("T1", [
        ("21:00 KST까지 저장된 종목 / IVU10210 코스닥 종목(약 1,700)", "99%", "`SELECT day, source, count(*) FROM kosdaq_min1 GROUP BY 1, 2`"),
        ("토스 실패로 KB 재조회한 종목 / 토스 몫", "< 5%", "source='kb'인 홀수 번째 종목")]),
    "MinuteBars": ("T1", [
        ("보관 중인 거래일 / 최근 30거래일 (서버가 켜진 날부터)", "100%", "`SELECT count(DISTINCT day) FROM kosdaq_min1`")]),
    "Signals": ("T2", [
        ("서버 시그널이 화면 signals.js와 같은 종목 / 종목", "100% (1,732종목 비교)", "SignalsTest")]),
    "Alerts": ("T2", [
        ("ntfy 전송 성공 / 새 시그널", "99%", "ntfy.sh 10초 타임아웃, 실패 로그"),
        ("5분 확인 시각 → signal_alert.at 기록까지 2분 안 / 새 시그널", "99%", "alerts.db `signal_alert`"),
        ("kosdaq_flow 저장 슬롯 / 장중 5분 슬롯 (알림 종목 있는 날)", "98%", "alerts.db `kosdaq_flow`")]),
    "SimModules": ("T0", [
        ("검사(id·연산자·규칙 30개·조건 10개)를 통과한 저장 규칙 / 저장 규칙", "100% (틀린 POST는 400)", "실제 자동매매(시뮬레이션 전략)가 같은 규칙을 씀 → SimModulesTest")]),
    "Scalp": ("T0", [
        ("단타 켠 정규장 분 중 Scalp.live 신호를 처리한 분 / 정규장 분", "99%", "Trader의 scalpDoneAt·scalpPhase (`/api/trader` 상태)"),
        ("후보·보유 종목 오늘 KB 1분봉 조회 성공 / 조회 (4개씩 동시에)", "99%", "Minutes.bars 실패 로그"),
        ("그날 실현 손익이 −dayLoss·+dayStop에 닿은 뒤 새 매수 · 단타 보유 합계 budget 초과 매수", "0건", "trader.log, trader.db `trader_position`"),
        ("/api/kosdaq-scalp 시뮬레이션 결과가 ScalpTest 기대값과 같음", "100%", "ScalpTest (minutes.db 1분봉 입력 품질은 Minutes SLI)")]),
    "Trader": ("T0", [
        ("같은 clientOrderId로 두 번 체결된 주문", "0건 (절대)", "trader.log, 토스 앱 체결 내역"),
        ("장마감 10분 전 매도 대상 중 마감 전에 팔린 비율 (closeSell 끈 종목 제외)", "100%", "trader.db `trader_position`"),
        ("정규장 중 5초 확인이 성공한 분 / 정규장 분", "99.5% (월 약 41분)", "측정 없음 → 마지막 tick 시각 기록 필요"),
        ("접수·의도된 거절 / 주문 요청", "99%", "trader.log `주문 거절`·`주문 응답 없음`"),
        ("기록이 간격+10분보다 오래됐는데 낸 주문 · 동시에 임대를 가진 프로세스", "0건", "trader_lease, trader.log `주문 담당`"),
        ("단타: 같은 종목 하루 두 번 매수", "0건", "trader_position UNIQUE (신호 SLI는 Scalp)")]),
}


def sli_table(name):
    if name not in SLI:
        return ""
    tier, rows = SLI[name]
    trs = "".join(f"<tr><td>{html.escape(s)}</td><td>{html.escape(o)}</td><td>{html.escape(m)}</td></tr>" for s, o, m in rows)
    return (f'<details open class="sli"><summary>SLI <b class="tier t-{tier}">{tier}</b></summary>'
            f"<table><thead><tr><th>SLI</th><th>목표 (SLO)</th><th>측정</th></tr></thead><tbody>{trs}</tbody></table></details>")


# 흐름도 (Mermaid). 코드에서 뽑지 않고 손으로 씀: 흐름이 바뀌면 여기도 고치세요
OVERVIEW = """flowchart LR
  B["브라우저 화면<br>home·heatmap·me·chart·volume·monitor·trader"] -->|"/api/*"| F[LastResponseFilter]
  F --> A[ApiController]
  A --> H[Heatmap] & D[Dividends] & C[Chart] & V[Volume] & R[Russell] & M[Monitor] & AL[Alerts] & T[Trader] & SC[Scalp]
  S(("@Scheduled")) --> V & R & AL & M & T & MI[Minutes] & SN[Snapshots]
  T -->|"단타 1분마다 Scalp.live"| SC
  AL -->|"Signal 이벤트"| T
  H & D & C & V & R & M & AL & SC & MI --> KB[(KB증권 OpenAPI)]
  T & MI & H --> TO[(토스증권 OpenAPI)]
  H & D --> DS[DailyStore] --> KBDB[(kb.db)]
  M --> MQ[MonitorQuotes] --> KBDB
  V & R & AL --> TK[Ticks] --> TDB[("kosdaq.db·russell.db·alerts.db")]
  MI --> MB[MinuteBars] --> MDB[(minutes.db)]
  SN --> MY[(MySQL)]"""

FLOWS = {
    "ApiController": """flowchart TD
  Q["HTTP /api/*"] --> F{"LastResponseFilter<br>?cached=1 ?"}
  F -->|예| L["last_api/*.txt 바로 응답"]
  F -->|아니오| A[ApiController 메서드]
  A --> S["서비스 호출<br>Heatmap·Dividends·Chart·Volume·Russell·Monitor·Alerts·Trader·Scalp"]
  S -->|성공| J["200 JSON"] --> SV["200이면 last_api에 저장"]
  S -->|"IllegalArgumentException"| E4["400 {error}"]
  S -->|"IOException·KBApiException"| E5["502 {error}"]""",
    "LastResponseFilter": """flowchart TD
  R[요청] --> P{"저장 대상 API?<br>LastResponse.file"}
  P -->|아니오| C[컨트롤러로 통과]
  P -->|예| K{"?cached=1"}
  K -->|예| RD{"파일 있음?"} -->|예| OK["200 저장된 JSON"]
  RD -->|아니오| NF["404"]
  K -->|아니오| W["ContentCachingResponseWrapper로 컨트롤러 실행"] --> S{"status 200?"}
  S -->|예| SAVE["임시 파일에 쓰고 통째로 교체"]
  S -->|아니오| END[그대로 응답]""",
    "KBClient": """flowchart TD
  C["call(TR, body)"] --> T{"토큰 유효?<br>만료 60초 전까지"}
  T -->|아니오| O["POST /oauth2/token 재발급"] --> P
  T -->|예| P["POST /api/v1/{tr소문자}"]
  P --> X{"HTTP 5xx?"}
  X -->|예, 3번까지| W["0.5s·1s·2s 쉬고 재시도"] --> P
  X -->|아니오| F{"dataHeader.processFlag == A?"}
  F -->|아니오| E["KBApiException"]
  F -->|예| N["고정길이 값 정리<br>'  368500' → 368500"] --> R[dataBody Map]""",
    "TossClient": """flowchart TD
  C["get/post(path)"] --> T{"토큰 있음?"}
  T -->|아니오| O["POST /oauth2/token (form)"] --> H
  T -->|예| H["계좌 API면 X-Tossinvest-Account 헤더"]
  H --> Q[HTTP 호출] --> S{응답}
  S -->|"token-revoked 등"| O2["한 번 재발급 후 재시도"] --> Q
  S -->|429| RA["Retry-After만큼 대기"] --> Q
  S -->|"5xx (3번까지)"| BK["0.5·1·2초 대기"] --> Q
  S -->|성공| R[JSON]
  FM["fetchMy()"] --> HO["/holdings · /exchange-rate · /buying-power · /stocks"] --> MY["내 보유 종목·현금(원화 환산)"]""",
    "Heatmap": """flowchart TD
  subgraph 히트맵
    K["queryHeatmap(kr)"] --> K1["IVS10920 시총 상위 + IVU10210 거래대금 상위"] --> K2["종목별 IVM10050 업종·시총"] --> K3["ETF·ETN 빼고 업종별 묶음"]
    U["queryHeatmap(us)"] --> U1["US_STOCKS 목록"] --> U2["GSS10030 종목별 시세"] --> U3["GICS 섹터별 묶음"]
  end
  E["etfSummary"] --> E1["IVS10920 전 종목에서 ETF 고름"] --> E2["이름 키워드로 분류"] --> E3["etf_snapshot.json과 비교"]
  QQ["queryQuarters"] --> Q1["IVS11560 / GSC10060 일봉"] --> Q2["분기별 일평균 거래대금"]
  H["queryHoldings(refresh)"] --> DS{"DailyStore: 오늘 저장값?"}
  DS -->|있음| RET[저장값 반환]
  DS -->|없음·refresh| H1["SSQM2952 국내 잔고 + SPQM2226 해외 잔고"] --> H2["IVU10140·GSS10030 현재가"] --> TS{"토스 키?"}
  TS -->|예| H3["TossClient.fetchMy 합산"] --> SV
  TS -->|아니오| SV["kb.db daily에 저장"]""",
    "Dividends": """flowchart TD
  Q["query(year, refresh)"] --> V{"2000 ≤ year ≤ 올해?"}
  V -->|아니오| E["IllegalArgumentException → 400"]
  V -->|예| DS{"DailyStore: 오늘 dividends:year 저장값?"}
  DS -->|있음| R[저장값]
  DS -->|없음| T["SWQA2301 거래내역 (페이지 반복)"] --> F["배당금 입금·원천세·추징·환급만"] --> W["원화 환산, 날짜순 entries"] --> S[kb.db 저장]
  RC["queryRecurring"] --> DS2{"DailyStore 'recurring'"} --> T2["SWQA2301 최근 반년"] --> F2["소수단위매수 → 정기 구매 요약"]""",
    "Chart": """flowchart TD
  S["search(q)"] --> M{"모양"}
  M -->|"6자리·한글"| K["IVS10920 상장 목록(1시간 캐시)에서 이름·코드 검색"]
  M -->|티커| U["US_STOCKS + 티커 후보"]
  C["chart(market, code, minutes)"] --> MK{market}
  MK -->|kr| K1["IVM10050 이름 + IVS11560 일봉/분봉"]
  MK -->|us| U1["GSC10060을 NAS→NYS→AMX 순으로 거래소 찾기"]
  K1 & U1 --> CD["candles 정리 (깨진 행 제거)"] --> RS["recent_searches.txt 맨 앞에 저장 (최대 10)"]""",
    "Monitor": """flowchart TD
  S(("시작 + 5분마다<br>@Scheduled")) --> R["refresh()"]
  P["POST /api/monitor<br>종목 목록 변경"] --> A["@Async changed()"] --> R
  R --> I["INDICES·BONDS + 추가 종목"] --> PL["8스레드로 종목별 일봉"]
  R --> MA["macro(): IVSA0070 WTI · IVA10370 국고채"]
  PL & MA --> RA["MonitorQuotes.replaceAll<br>한 트랜잭션: 덮어쓰고 빠진 key 삭제"] --> DB[(kb.db monitor_quotes)]
  G["GET /api/monitor-quotes"] --> Q["quotes(): DB만 읽음 (KB 조회 없음)"] --> DB""",
    "Volume": """flowchart TD
  Q["GET /api/kosdaq-volume"] --> C{"10분 캐시?"}
  C -->|있음| R[캐시 반환]
  C -->|없음| L["IVU10210 코스닥 전 종목 (~1,700)"] --> D["종목별 IVS11560 일봉<br>코루틴 8개씩 (parallelIO)"]
  D --> OK{"응답 멀쩡?"}
  OK -->|아니오| RE["다시 받기 (최대 3번)"] --> D
  OK -->|예| W["주별 하루 평균 거래량: 이번 주 vs 전주"]
  T(("평일 9~15시 5분마다 :30초")) --> TK["ticks.tick()"] --> KR["kosdaqRows: IVU10210 전 종목 현재가·누적거래량"] --> DB[(kosdaq.db)]
  I["GET /api/kosdaq-intraday"] --> TI["Ticks.intraday: 같은 시각 전일·전주 대비"] --> DB""",
    "Russell": """flowchart TD
  CSV["russell2000.csv (iShares IWM)"] --> Q["GET /api/russell-volume"]
  Q --> X["티커별 거래소 찾기: CSV Exchange → NAS·NYS·AMX<br>MOGA → MOG.A"] --> D["GSC10060 일봉, 코루틴 8개씩"] --> W["주간 평균 거래량 비교"]
  T(("뉴욕 10:00~16:30 30분마다")) --> TK["ticks.tick()"] --> G["GSS10030 상세시세 (15분 지연)"] --> DB[(russell.db)]
  I["GET /api/russell-intraday"] --> TI[Ticks.intraday] --> DB""",
    "Ticks": """flowchart TD
  S["가진 쪽 @Scheduled"] --> T["tick()"] --> IN{"장중(open~close)?"}
  IN -->|아니오| X[건너뜀]
  IN -->|예| TK["take.rows(at): 시장별 함수가 전 종목 조회"] --> E{"휴장(빈 목록)?"}
  E -->|예| X
  E -->|아니오| SV["save: INSERT OR REPLACE (at, payload JSON)<br>14일 지난 기록 삭제"]
  I["intraday(day, at)"] --> F["같은 시각(없으면 그 전 마지막) 기록"] --> CMP["누적 거래량: 전일·전주 같은 시각 대비"]
  L["live / 시뮬레이션"] --> RUN["SimModules 매수 규칙·매도 함수로 기록을 시간순 재생"] --> TR["매수·매도 거래 목록"]""",
    "Alerts": """flowchart TD
  S(("5분마다<br>코스닥 서울·러셀 뉴욕")) --> IN{"장중?"}
  IN -->|아니오| X[건너뜀]
  IN -->|예| HL{"top 1위 오늘 일봉 없음?"}
  HL -->|예| HX[휴장]
  HL -->|아니오| RK{"30분 지났거나 top 비어 있음?"}
  RK -->|예| R["Volume/Russell 주간 증가율 순위 → top 30 (스팩 제외)"] --> CH
  RK -->|아니오| CH["top 30 일봉 병렬(8) 조회"]
  CH --> SG["Signals.of: 매수 쪽(dir>0) 시그널"] --> NW{"직전에 없던 새 시그널?"}
  NW -->|예| REC["alerts.db INSERT OR IGNORE<br>(같은 날·종목·시그널 한 번)"] --> N1["ntfy 푸시"] & N2["Signal 이벤트 → Trader"] & N3["GET /api/alerts로 브라우저"]
  F(("5분마다 :30초")) --> FL["알림 종목 체결강도·호가 기록 (Ticks)"]""",
    "Signals": """flowchart LR
  C["일봉 종가·거래량"] --> MA["SMA 5·20·60·120"] & BB["볼린저 20, 2σ"] & RSI["RSI 14 (와일더)"] & MACD["MACD 12·26·9"]
  MA & BB & RSI & MACD --> X["최근 5거래일 안의 교차·돌파"] --> O["[{text, d, dir: 1 매수 / -1 매도}]"]""",
    "SimModules": """flowchart TD
  F["sim_buy_rules.json<br>(없으면 DEFAULT_BUY)"] --> RL["Rule {id, label, all: Cond[]}"]
  V["값 배열<br>PRICE·CHANGE·RATIO·OPEN·VWAP·VALUE"] --> EV{"모든 Cond 만족?<br>left op right"}
  RL --> EV
  EV -->|예| B[매수 신호]
  P["보유 거래 + 현재가"] --> SF["SELL_FN[이름] (tp-sl 등)"] --> R{"이유 반환?"}
  R -->|예| S[매도]
  R -->|null| H[계속 보유]""",
    "Trader": """flowchart TD
  S(("5초마다")) --> AC["계좌 새로고침 (주기적)"] --> LS{"trader.db 임대(lease) 얻음?"}
  LS -->|아니오| W[다른 서버가 주문 담당 → 대기]
  LS -->|예| MK["시장별 KR·US: tickMarket"]
  MK --> PD["미체결 주문 체결 확인"] --> SE{"/market-calendar 장 시간"}
  SE -->|"휴장·장 전·장 후"| X[대기]
  SE -->|"마감 10분 전 이후"| CL["미체결 매수 취소 + 전량 매도"]
  SE -->|장중| HA{"화면 '전량 매도'?"}
  HA -->|예| ALL[전량 매도]
  HA -->|아니오| TP["보유분 현재가로 익절·손절<br>전날 미청산은 바로 매도"]
  TP --> PR["KR: 미장 연동 (KODEX 200 / 인버스)"] --> SIM["시뮬레이션 전략 (Ticks.live)"]
  SIM --> EN{"자동매매 켜짐?"}
  EN -->|아니오| X2[새 매수 없음]
  EN -->|예| CA["후보: 직접 고른 종목 + 오늘 시그널 종목"] --> LIM{"하루 종목 수·금액 남음?"}
  LIM -->|아니오| X3[중단]
  LIM -->|예| IX{"지수(추종 ETF) 전일 대비 상승?"}
  IX -->|아니오| X4[대기, 5초 뒤 다시]
  IX -->|예| BUY["수량 = 종목당 금액 ÷ 현재가<br>clientOrderId로 시장가 주문"]
  EV["Alerts.Signal 이벤트"] --> SG["trader_signal 저장"] -.-> CA""",
    "Minutes": """flowchart TD
  ST(("서버 시작 @Async")) --> LD["마지막 거래일 이어 받기"] --> RUN
  S(("평일 20:10")) --> RUN["collect(day)"]
  RUN --> L["IVU10210 코스닥 전 종목"] --> HV["MinuteBars.codes(day): 이미 받은 종목 제외"]
  HV --> SP{"목록 순번"}
  SP -->|짝수| KB["KB IVS11560 1분봉 1,500개"]
  SP -->|"홀수 (토스 키 있을 때)"| TS["토스 /candles 1m 200개씩<br>시각 -1분, 거래대금 추정"]
  TS -->|실패| KB
  KB & TS --> SV["MinuteBars.saveAll<br>한 트랜잭션: 저장 + 30일 지난 날 삭제"] --> DB[(minutes.db)]""",
    "Snapshots": """flowchart TD
  S(("4시간마다 00·04·…·20시")) --> M{"MYSQL_HOST 있음?"}
  M -->|아니오| X[기록 안 함]
  M -->|예| H["Heatmap.queryHoldings(refresh)<br>KB + 토스"] --> SU["summarize: 총자산·주식·현금·KB·토스"] --> DB[("MySQL holdings_snapshot<br>ON DUPLICATE KEY UPDATE")]
  G["GET /api/holdings-history"] --> HH["kb.db 하루 기록 + 4시간 기록 합침"]""",
    "DailyStore": """flowchart TD
  D["daily(kind, refresh, fetch)"] --> R{"refresh?"}
  R -->|아니오| Q{"오늘(서울) kind 행 있음?"}
  Q -->|예| RET[저장 payload 반환]
  Q -->|아니오| F
  R -->|예| F["fetch() — KB 조회"] --> I["INSERT … ON CONFLICT (kind, date)"] --> RD[다시 읽어 반환]""",
}


def doc_of(src):
    """package/import 뒤 첫 /** */ 를 설명으로"""
    m = re.search(r"/\*\*(.*?)\*/", src, re.S)
    if not m:
        return ""
    lines = [re.sub(r"^\s*\* ?", "", l) for l in m.group(1).splitlines()]
    return html.unescape("\n".join(lines).strip())


def members(src):
    """클래스(와 안쪽 struct) 바로 아래 메서드·함수 선언 (줄 번호, 선언). 들여쓰기 4·8칸만"""
    java = re.compile(r"[\w<>\[\], .?]*\w+\s*\(.*\)\s*(throws [\w., ]+)?\s*\{$")
    kotlin = re.compile(r"((private|internal|override|public|suspend|inline) )*fun ")
    keywords = {"return", "new", "if", "for", "while", "switch", "throw", "else", "try", "catch", "synchronized", "do"}
    out = []
    for i, line in enumerate(src.splitlines(), 1):
        m = re.match(r"^( {4}| {8})(\S.*?)\s*$", line)
        head = m.group(2).split("(")[0] if m else ""
        if not head.strip() or head.split()[0] in keywords or "=" in head:
            continue
        s = m.group(2)
        if java.match(s) or kotlin.match(s):
            out.append((i, s.rstrip(" {")))
    return out


def main():
    files = sorted(p for p in ROOT.rglob("*") if p.suffix in (".java", ".kt"))
    by_name = {p.stem: p for p in files}
    order = [(g, n) for g, ns in GROUPS.items() for n in ns if n in by_name]
    seen = {n for _, n in order}
    order += [("기타", p.stem) for p in files if "main" in p.parts and p.stem not in seen]
    order += [("테스트", p.stem) for p in files if "test" in p.parts]

    nav, body, group = [], [], None
    for g, name in order:
        p = by_name[name]
        src = p.read_text(encoding="utf-8")
        if g != group:
            nav.append(f"<h3>{g}</h3>")
            group = g
        nav.append(f'<a href="#{name}">{name}<small>{p.suffix[1:]}</small></a>')
        outline = "".join(f'<li><a href="#{name}" data-line="{n}"><code>{html.escape(s)}</code></a></li>' for n, s in members(src))
        lang = "kotlin" if p.suffix == ".kt" else "java"
        lines = src.splitlines()
        code = html.escape(src)
        body.append(f"""<section id="{name}">
<h2>{name} <span class="meta">{p.relative_to(ROOT.parent).as_posix()} · {len(lines)}줄</span></h2>
<div class="doc">{html.escape(doc_of(src)) or '<i>설명 주석 없음</i>'}</div>
{sli_table(name)}
{f'<details open><summary>흐름도</summary><pre class="mermaid">{html.escape(FLOWS[name])}</pre></details>' if name in FLOWS else ''}
{f'<details><summary>메서드 {len(members(src))}개</summary><ul class="outline">{outline}</ul></details>' if outline else ''}
<details class="code"><summary>코드 보기</summary><pre><code class="language-{lang}">{code}</code></pre></details>
</section>""")

    overview = f'<section id="overview"><h2>전체 흐름</h2><pre class="mermaid">{html.escape(OVERVIEW)}</pre></section>'
    page = TEMPLATE.replace("{{NAV}}", '<a href="#overview">전체 흐름</a>\n' + "\n".join(nav))
    OUT.write_text(page.replace("{{BODY}}", overview + "\n".join(body)), encoding="utf-8")
    print(f"{OUT} ({len(order)}개 파일)")


TEMPLATE = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>spring-app 클래스</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github.min.css" media="(prefers-color-scheme: light)">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css" media="(prefers-color-scheme: dark)">
<style>
:root{--bg:#fff;--fg:#1f2328;--mute:#656d76;--line:#d0d7de;--side:#f6f8fa;--acc:#0969da}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--mute:#8d96a0;--line:#30363d;--side:#161b22;--acc:#4493f8}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 system-ui,"Malgun Gothic",sans-serif;display:flex}
nav{position:sticky;top:0;height:100vh;overflow:auto;width:240px;flex:none;background:var(--side);border-right:1px solid var(--line);padding:12px}
nav input{width:100%;padding:6px 8px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--fg)}
nav h3{font-size:12px;color:var(--mute);margin:14px 0 4px;text-transform:uppercase}
nav a{display:flex;justify-content:space-between;color:var(--fg);text-decoration:none;padding:2px 6px;border-radius:4px}
nav a:hover{background:var(--line)}nav small{color:var(--mute)}
main{flex:1;min-width:0;padding:16px 24px;max-width:1200px}
section{border-bottom:1px solid var(--line);padding:8px 0 20px}
h2{margin:16px 0 8px}.meta{font-size:13px;font-weight:normal;color:var(--mute)}
.doc{white-space:pre-wrap;background:var(--side);border-left:3px solid var(--acc);padding:10px 14px;border-radius:4px}
summary{cursor:pointer;color:var(--acc);margin-top:10px}
.outline{columns:2;font-size:13px}.outline a{color:var(--fg)}
pre{overflow:auto;border:1px solid var(--line);border-radius:6px;font-size:13px}
pre code{display:block;padding:12px;line-height:20px}
.sli table{border-collapse:collapse;width:100%;font-size:13px;margin-top:6px}
.sli th,.sli td{border:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}
.sli th{background:var(--side)}
.tier{font-size:12px;padding:1px 6px;border-radius:10px;border:1px solid var(--line);color:var(--fg)}
.t-T0{background:#ffebe9;border-color:#cf222e;color:#82071e}.t-T1{background:#fff8c5;border-color:#bf8700;color:#6f4e00}
.t-T2{background:#ddf4ff;border-color:#0969da;color:#0a3069}
@media (prefers-color-scheme:dark){.t-T0{background:#3d1417;color:#ffa198}.t-T1{background:#3b2e00;color:#e3b341}.t-T2{background:#0c2d6b;color:#79c0ff}}
pre.mermaid{border:none;background:none;text-align:center;font-size:14px}
@media (max-width:760px){body{display:block}nav{position:static;height:auto;width:auto}main{padding:16px}.outline{columns:1}}
</style></head><body>
<nav><input id="q" placeholder="클래스 찾기"><div id="list">{{NAV}}</div></nav>
<main><h1>spring-app 클래스</h1><p>각 클래스의 설명(클래스 주석)·메서드 목록·코드. 메서드를 누르면 코드의 그 줄로 갑니다.</p>{{BODY}}</main>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/kotlin.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/mermaid/10.9.1/mermaid.min.js"></script>
<script>
mermaid.initialize({startOnLoad: true, securityLevel: 'loose',
  theme: matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'default'});
// 코드는 펼칠 때만 칠함 (파일이 커서)
document.querySelectorAll('details.code').forEach(d => d.addEventListener('toggle', () => {
  const c = d.querySelector('code'); if (d.open && !c.dataset.highlighted) hljs.highlightElement(c);
}));
// 메서드 링크: 코드 펼치고 그 줄로
document.addEventListener('click', e => {
  const a = e.target.closest('.outline a'); if (!a) return;
  e.preventDefault();
  const d = a.closest('section').querySelector('details.code'); d.open = true;
  const c = d.querySelector('code');  // 줄 높이 20px 고정이라 줄 번호로 위치 계산
  setTimeout(() => window.scrollTo({top: c.getBoundingClientRect().top + scrollY + 12 + (a.dataset.line - 1) * 20 - innerHeight / 3}), 50);
});
document.getElementById('q').addEventListener('input', e => {
  const q = e.target.value.toLowerCase();
  document.querySelectorAll('#list a').forEach(a => a.style.display = a.textContent.toLowerCase().includes(q) ? '' : 'none');
});
</script></body></html>"""

if __name__ == "__main__":
    main()
