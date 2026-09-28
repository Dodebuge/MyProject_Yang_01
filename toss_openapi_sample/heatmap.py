"""국내·해외 섹터 히트맵 데이터 (토스증권 OpenAPI). kb_openapi_sample/heatmap.py와 같은 JSON을 돌려줍니다.

토스 API에는 업종(섹터)·시가총액·거래대금 필드가 없어 아래처럼 구합니다.
- 종목·섹터: 국내·미국 모두 아래 KR_STOCKS·US_STOCKS 수동 목록 (KB 버전의 미국 목록과 같은 방식)
- 시가총액: 발행주식수(/stocks의 sharesOutstanding) × 현재가
- 등락률·거래대금: 일봉 2개(/candles?interval=1d&count=2). 거래대금 = 거래량 × 종가 (근삿값)
- 국내 상장 ETF: 전체 종목 목록(/stocks/all?market=KOSPI&securityType=ETF)을 이름 키워드로 분류

    python heatmap.py kr   # 콘솔로 섹터별 합계 확인
    python heatmap.py us
"""

from __future__ import annotations

import json
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from pathlib import Path

import requests

from daily_store import KST, daily
from toss_client import TossApiError, TossClient

# (종목코드, 섹터). 섹터 이름은 KB 업종 지수 이름(indx_nm)과 같게 맞춰 ETF 분류 규칙과 연결됩니다.
# ponytail: 수동 목록, 편입·상장폐지 시 여기만 고치면 됩니다. 목록 밖 종목은 히트맵에 나오지 않습니다.
KR_STOCKS = [
    *[(c, "전기 전자") for c in [
        "005930", "000660", "373220", "006400", "066570", "009150", "034220", "011070", "042700", "018260",
        "267260", "010120", "247540", "086520", "003670"]],
    *[(c, "제약") for c in ["207940", "068270", "196170", "028300", "128940", "000100", "326030", "302440"]],
    *[(c, "운송장비 부품") for c in [
        "005380", "000270", "012330", "012450", "329180", "009540", "042660", "010140", "047810", "064350",
        "079550", "272210", "267250"]],
    *[(c, "금융") for c in ["105560", "055550", "086790", "316140", "138040", "024110", "323410", "377300"]],
    *[(c, "보험") for c in ["032830", "000810", "005830"]],
    *[(c, "증권") for c in ["006800", "071050", "039490"]],
    *[(c, "IT 서비스") for c in ["035420", "035720", "402340", "034730", "003550"]],
    *[(c, "오락 문화") for c in ["259960", "036570", "352820", "251270", "035250", "041510", "035900"]],
    *[(c, "화학") for c in ["051910", "096770", "010950", "011170", "009830", "090430", "051900"]],
    *[(c, "금속") for c in ["005490", "010130", "004020"]],
    *[(c, "기계 장비") for c in ["034020", "241560", "298040", "000150", "000880"]],
    *[(c, "유통") for c in ["028260", "023530", "139480", "282330", "004170", "069960", "007070", "001040", "078930"]],
    *[(c, "운송 창고") for c in ["011200", "003490", "086280", "180640"]],
    *[(c, "건설") for c in ["000720", "028050"]],
    *[(c, "통신") for c in ["017670", "030200", "032640"]],
    *[(c, "음식료 담배") for c in ["033780", "097950", "271560", "004370"]],
    *[(c, "전기 가스") for c in ["015760", "036460", "051600", "052690"]],
]

# (티커, 섹터). 토스는 거래소 코드 없이 티커만 넣으면 됩니다.
# ponytail: 수동 목록, 편입 변경 시 여기만 고치면 됩니다.
US_STOCKS = [
    *[(s, "정보기술") for s in [
        "NVDA", "AAPL", "MSFT", "AVGO", "ORCL", "AMD", "PLTR", "CRM", "CSCO", "IBM", "MU", "ACN", "INTU", "NOW",
        "QCOM", "TXN", "AMAT", "LRCX", "KLAC", "ADBE", "ANET", "INTC", "ADI", "PANW", "CRWD", "APH", "DELL"]],
    *[(s, "커뮤니케이션") for s in ["GOOGL", "META", "NFLX", "TMUS", "DIS", "T", "VZ", "CMCSA"]],
    *[(s, "경기소비재") for s in ["AMZN", "TSLA", "HD", "MCD", "BKNG", "LOW", "TJX", "SBUX", "NKE"]],
    *[(s, "필수소비재") for s in ["WMT", "COST", "PG", "KO", "PEP", "PM", "MO"]],
    *[(s, "헬스케어") for s in [
        "LLY", "JNJ", "UNH", "ABBV", "MRK", "TMO", "ABT", "ISRG", "AMGN", "PFE", "GILD", "DHR", "BSX", "VRTX"]],
    *[(s, "금융") for s in [
        "BRK.B", "JPM", "V", "MA", "BAC", "WFC", "GS", "MS", "AXP", "C", "SCHW", "BLK", "SPGI", "PGR", "COIN"]],
    *[(s, "산업재") for s in ["GE", "CAT", "RTX", "BA", "HON", "UNP", "UBER", "DE", "LMT", "ETN", "GEV", "UPS"]],
    *[(s, "에너지") for s in ["XOM", "CVX", "COP", "EOG", "SLB"]],
    *[(s, "소재") for s in ["LIN", "SHW", "FCX", "NEM", "ECL"]],
    *[(s, "유틸리티") for s in ["NEE", "SO", "DUK", "CEG", "VST"]],
    *[(s, "부동산") for s in ["PLD", "AMT", "EQIX", "WELL", "SPG"]],
]
SECTOR = dict(KR_STOCKS) | dict(US_STOCKS) | {"GOOG": "커뮤니케이션"}

# ---------------------------------------------------------------------- 국내 상장 ETF

ETF_OTHER = "배당·전략·기타"

# (이름 패턴, 국내 업종, 미국 GICS 섹터). 위에서부터 처음 맞는 규칙을 씁니다.
# 섹터가 None인 규칙은 섹터 ETF가 아닌 묶음(채권, 원자재, 시장지수 등)입니다.
# ponytail: 이름 키워드 분류라 애매한 ETF(예: "AI코리아")는 가까운 섹터로 갑니다. 새 테마가 늘면 규칙을 추가하세요.
ETF_RULES = [
    (r"채권|국고채|국채|회사채|특수채|금융채|은행채|여전채|금리|CD|KOFR|머니마켓|MMF|단기채|국공채|통안채|전단채|단기자금|물가채|하이일드|TDF|TRF", "채권·금리·자산배분", None),
    (r"금현물|금선물|골드|은선물|원유|WTI|구리선물|천연가스|농산물|원자재|달러|엔화|통화|비트코인|국제금|금액티브|은액티브|구리실물|콩선물|팔라듐|탄소배출권", "원자재·통화", None),
    (r"리츠|부동산", "부동산", "부동산"),
    (r"반도체|HBM|하이닉스|삼성전자|디스플레이|전기전자|전자|엔비디아|TSMC|파운드리|브로드컴", "전기 전자", "정보기술"),
    (r"2차전지|이차전지|배터리|리튬|양극재", "전기 전자", "경기소비재"),
    (r"소프트웨어|인터넷|플랫폼|게임|클라우드|사이버보안|IT서비스|SW|마이크로소프트|팔란티어|메타버스", "IT 서비스", "정보기술"),
    (r"증권", "증권", "금융"),
    (r"보험", "보험", "금융"),
    (r"은행|금융|지주", "금융", "금융"),
    (r"바이오|헬스케어|제약|의료|메디컬|비만|일라이릴리", "제약", "헬스케어"),
    (r"자동차|모빌리티|전기차|수소차|테슬라|자율주행|스마트카|BYD", "운송장비 부품", "경기소비재"),
    (r"조선|방산|방위|우주|항공우주|K-?디펜스", "운송장비 부품", "산업재"),
    (r"원자력|원전|전력|기계|인프라|로봇|휴머노이드|SMR|산업재", "기계 장비", "산업재"),
    (r"화장품|뷰티", "화학", "필수소비재"),
    (r"화학|소재|정유|에너지", "화학", "에너지"),
    (r"철강|금속|비철|고려아연", "금속", "소재"),
    (r"건설", "건설", "산업재"),
    (r"통신|5G", "통신", "커뮤니케이션"),
    (r"미디어|엔터|콘텐츠|컨텐츠|K-?POP|케이팝|방송|웹툰|드라마|K컬처|구글|골프", "오락 문화", "커뮤니케이션"),
    (r"음식료|필수소비|푸드|K-?푸드|라면", "음식료 담배", "필수소비재"),
    (r"유통|소비|여행|레저|럭셔리|명품|커머스|내수", "유통", "경기소비재"),
    (r"운송|해운|물류|항공", "운송 창고", "산업재"),
    (r"유틸리티|가스", "전기 가스", "유틸리티"),
    (r"테크|빅테크|IT(?![A-Za-z])|AI|FANG|팡플", "전기 전자", "정보기술"),
    (r"배당|커버드콜|인컴", ETF_OTHER, None),
    (r"200|코스피|코스닥|KRX|KOSPI|KOSDAQ|S&P|나스닥|NASDAQ|다우|MSCI|TOPIX|니케이|항셍|CSI|유로|인도|베트남|중국|차이나|과창판|HSCEI|A50|ChiNext|차이넥스트|DAX|라틴|아시아|일본|미국|글로벌|선진국|신흥국|대만|KTOP|TOP10|Top10|우량주|블루칩|레버리지|인버스",
     "시장지수", None),
]
SNAPSHOT = Path(__file__).resolve().parent / "etf_snapshot.json"


def classify_etf(name: str) -> tuple[str, str | None, bool]:
    """(국내 업종 또는 묶음 이름, 미국 섹터, 섹터 ETF 여부)."""
    for pattern, kr, us in ETF_RULES:
        if re.search(pattern, name, re.I):
            return kr, us, us is not None
    return ETF_OTHER, None, False


# ---------------------------------------------------------------------- 공통 조회


def _parallel(fn, items, workers: int = 4):
    # 토스는 API 그룹별 초당 호출 수가 정해져 있어(차트 20회/초 등) KB 버전(8)보다 적게 돌립니다. 429는 클라이언트가 기다렸다 재시도.
    with ThreadPoolExecutor(workers) as pool:
        return list(pool.map(fn, items))


def _batches(items: list, size: int = 200):
    return [items[i:i + size] for i in range(0, len(items), size)]


def stock_info(client: TossClient, symbols: list[str]) -> dict[str, dict]:
    """/stocks 다건 조회 (200개씩). {symbol: {name, securityType, sharesOutstanding, ...}}"""
    out = {}
    for chunk in _batches(symbols):
        out |= {s["symbol"]: s for s in client.get("/api/v1/stocks", {"symbols": ",".join(chunk)})}
    return out


def last_prices(client: TossClient, symbols: list[str]) -> dict[str, float]:
    out = {}
    for chunk in _batches(symbols):
        out |= {p["symbol"]: float(p["lastPrice"] or 0) for p in client.get("/api/v1/prices", {"symbols": ",".join(chunk)})}
    return out


def _ymd(timestamp: str) -> str:
    return timestamp[:10].replace("-", "")


def candles(client: TossClient, symbol: str, count: int) -> list[dict]:
    """일봉 최신순. 한 번에 200개까지라 넘으면 nextBefore로 이어 받습니다."""
    rows, before = [], None
    while len(rows) < count:
        params = {"symbol": symbol, "interval": "1d", "count": min(200, count - len(rows))}
        if before:
            params["before"] = before
        page = client.get("/api/v1/candles", params)
        rows += page["candles"]
        before = page.get("nextBefore")
        if not before or not page["candles"]:
            break
    return rows


def us_pending_day(client: TossClient) -> str | None:
    """미국 정규장 시작 전이면 오늘 날짜(YYYYMMDD). 그날 일봉에는 데이마켓·프리마켓 거래만 있어 빼고 직전 정규장을 씁니다."""
    today = client.get("/api/v1/market-calendar/US").get("today") or {}
    start = (today.get("regularMarket") or {}).get("startTime")
    if start and datetime.now(KST) < datetime.fromisoformat(start):
        return today["date"].replace("-", "")
    return None


def day_quote(client: TossClient, symbol: str, skip_day: str | None = None) -> dict | None:
    """최근 거래일 종가·등락률·거래대금. 일봉 2개로 계산합니다. skip_day 날짜의 봉은 건너뜁니다."""
    try:
        rows = [r for r in candles(client, symbol, 3) if _ymd(r["timestamp"]) != skip_day][:2]
    except (TossApiError, requests.RequestException):
        return None
    if len(rows) < 2:
        return None
    today, prev = rows[0], rows[1]
    close, prev_close = float(today["closePrice"]), float(prev["closePrice"])
    return {
        "price": close,
        "change": (close / prev_close - 1) * 100 if prev_close else 0.0,
        "value": float(today["volume"]) * close,  # ponytail: 거래량 × 종가 근삿값, 토스 일봉에 거래대금 필드가 없음
        "date": _ymd(today["timestamp"]),
    }


# ---------------------------------------------------------------------- 히트맵

CACHE_SECONDS = 60
_cache: dict = {}


def fetch_stocks(client: TossClient, market: str) -> list[dict]:
    """히트맵 종목. cap·value는 국내 원, 미국 달러."""
    universe = KR_STOCKS if market == "kr" else US_STOCKS
    info = stock_info(client, [s for s, _ in universe])
    skip = us_pending_day(client) if market == "us" else None
    quotes = _parallel(lambda s: day_quote(client, s[0], skip), universe)
    stocks = []
    for (symbol, sector), q in zip(universe, quotes):
        i = info.get(symbol)
        if not i or not q:
            continue
        stocks.append({
            "code": symbol, "name": i["name"] if market == "kr" else symbol, "sector": sector,
            "cap": float(i.get("sharesOutstanding") or 0) * q["price"], **q,
        })
    if market == "kr":
        for s in stocks:
            del s["date"]  # 화면은 date가 있으면 '미국 ... 기준'으로 표시
    return [s for s in stocks if s["cap"]]


def fetch_etfs(client: TossClient) -> list[dict]:
    """국내 상장 ETF 전체를 이름으로 분류합니다. cap = 발행주식수 × 현재가 (원)."""
    rows = client.get("/api/v1/stocks/all", {"market": "KOSPI", "securityType": "ETF"})
    symbols = [r["symbol"] for r in rows]
    info, prices = stock_info(client, symbols), last_prices(client, symbols)
    etfs = []
    for r in rows:
        kr, us, is_sector = classify_etf(r["name"])
        shares = float(info.get(r["symbol"], {}).get("sharesOutstanding") or 0)
        etfs.append({"code": r["symbol"], "name": r["name"], "kr": kr, "us": us, "sector": is_sector,
                     "cap": round(shares * prices.get(r["symbol"], 0))})
    return etfs


def update_snapshot(etfs: list[dict]) -> dict:
    """오늘 ETF 코드 목록을 저장하고, 직전 날짜와 비교해 새로 상장·상장폐지된 ETF를 돌려줍니다."""
    today = date.today().isoformat()
    try:
        snaps = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError):
        snaps = {}
    snaps[today] = sorted(e["code"] for e in etfs)
    snaps = dict(sorted(snaps.items())[-60:])  # 최근 60일치만 보관
    SNAPSHOT.write_text(json.dumps(snaps, ensure_ascii=False), encoding="utf-8")

    earlier = [d for d in snaps if d < today]
    if not earlier:
        return {"since": None, "listed": [], "delisted": []}
    before = set(snaps[earlier[-1]])
    return {
        "since": earlier[-1],
        "listed": [e["code"] for e in etfs if e["code"] not in before],
        "delisted": sorted(before - {e["code"] for e in etfs}),
    }


def etf_summary(client: TossClient) -> dict:
    hit = _cache.get("etf")
    if hit and time.time() - hit[0] < 600:  # 전체 목록은 일 배치 갱신, /stocks/all은 초당 1회 제한
        return hit[1]
    etfs = fetch_etfs(client)
    payload = {"total": len(etfs), "items": etfs, "changes": update_snapshot(etfs)}
    _cache["etf"] = (time.time(), payload)
    return payload


def query_heatmap(client: TossClient, market: str) -> dict:
    if market not in ("kr", "us"):
        raise ValueError(f"market은 kr 또는 us: {market}")
    hit = _cache.get(market)
    if hit and time.time() - hit[0] < CACHE_SECONDS:
        return hit[1]
    client.access_token  # 병렬 호출 전에 토큰을 한 번만 발급
    payload = {
        "market": market,
        "currency": "KRW" if market == "kr" else "USD",
        "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "stocks": fetch_stocks(client, market),
        "etf": etf_summary(client),
    }
    _cache[market] = (time.time(), payload)
    return payload


# ---------------------------------------------------------------------- 내 보유 종목


def fetch_my(client: TossClient) -> dict:
    """내 보유 종목과 현금 (/holdings, /buying-power).

    cap = 평가금액(원; 미국 종목은 현재 USD/KRW 환율로 환산), pl = 수익률(%), change = 오늘 손익률(%, dailyProfitLoss).
    현금 = 매수 가능 금액(원화) + 달러 매수 가능 금액 원화환산. 토스에는 예수금 API가 없어 매수 가능 금액을 씁니다.
    """
    h = client.get("/api/v1/holdings", account=True)
    rate = float(client.get("/api/v1/exchange-rate", {"baseCurrency": "USD", "quoteCurrency": "KRW"})["rate"])
    buying = {c: float(client.get("/api/v1/buying-power", {"currency": c}, account=True)["cashBuyingPower"] or 0)
              for c in ("KRW", "USD")}
    info = stock_info(client, [i["symbol"] for i in h["items"]]) if h["items"] else {}

    stocks = []
    for i in h["items"]:
        us = i["marketCountry"] == "US"
        etf = info.get(i["symbol"], {}).get("securityType") in ("ETF", "FOREIGN_ETF")
        kr_group, us_sector, _ = classify_etf(i["name"])
        amount = float(i["marketValue"]["amount"] or 0)
        cap = round(amount * rate if us else amount)
        stocks.append({
            "code": i["symbol"], "name": i["name"], "market": "미국" if us else "국내", "etf": etf,
            # 해외 보유와 같은 기준으로 묶도록 섹터 ETF는 GICS 섹터 이름을 씁니다.
            "sector": (us_sector or kr_group) if etf else SECTOR.get(i["symbol"], "미분류"),
            "qty": float(i["quantity"]), "price": float(i["lastPrice"]),
            "cap": cap, "value": cap,  # 크기 기준을 평가금액 하나로 통일
            "pl": float(i["profitLoss"]["rate"]) * 100 if i["profitLoss"].get("rate") is not None else None,
            "change": float(i["dailyProfitLoss"].get("rate") or 0) * 100,
        })
    cash = {"krw": round(buying["KRW"]), "fx_krw": round(buying["USD"] * rate), "usd": buying["USD"], "rate": rate}
    return {"stocks": [s for s in stocks if s["cap"] > 0], "cash": cash}


def query_holdings(client: TossClient, refresh: bool = False) -> dict:
    """내 보유 종목 (내정보 화면). 하루 한 번만 토스에서 조회해 toss.db에 저장하고, 같은 날은 저장된 값을 돌려줍니다.
    refresh=True면 지금 다시 조회해 오늘 값을 덮어씁니다."""
    return daily("holdings", lambda: {"fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), **fetch_my(client)}, refresh)


# ---------------------------------------------------------------------- 분기별 거래대금

QUARTER_CACHE_SECONDS = 600  # 과거 분기 값은 자주 바뀌지 않음
QUARTERS = 5                 # 최근 5개 분기 (진행 중인 분기 포함)


def quarter_of(yyyymmdd: str) -> str:
    return f"{yyyymmdd[:4]}Q{(int(yyyymmdd[4:6]) - 1) // 3 + 1}"


def query_quarters(client: TossClient, market: str) -> dict:
    """히트맵과 같은 종목의 분기별 일평균 거래대금(거래량 × 종가). 섹터 값 = 소속 종목 일평균의 합."""
    hit = _cache.get(("quarters", market))
    if hit and time.time() - hit[0] < QUARTER_CACHE_SECONDS:
        return hit[1]
    stocks = query_heatmap(client, market)["stocks"]
    skip = us_pending_day(client) if market == "us" else None

    def one(stock: dict) -> dict:
        try:
            days = [(d, float(r["volume"]) * float(r["closePrice"])) for r in candles(client, stock["code"], 400)
                    if (d := _ymd(r["timestamp"])) != skip]
        except (TossApiError, requests.RequestException):  # 재시도 후에도 실패한 종목은 빼고 개수만 알림
            days = []
        by_q: dict[str, list[float]] = {}
        for d, v in days:
            by_q.setdefault(quarter_of(d), []).append(v)
        return {"code": stock["code"], "name": stock["name"], "sector": stock["sector"], "last": max((d for d, _ in days), default=""),
                "avg": {q: sum(vs) / len(vs) for q, vs in by_q.items()}}

    rows = _parallel(one, stocks)
    failed = sum(1 for r in rows if not r["last"])
    last = max((r["last"] for r in rows), default="")
    if not last:
        raise ValueError("차트 데이터를 받지 못했습니다.")
    y, q = int(last[:4]), (int(last[4:6]) - 1) // 3 + 1
    quarters = []
    for _ in range(QUARTERS):
        quarters.insert(0, f"{y}Q{q}")
        y, q = (y, q - 1) if q > 1 else (y - 1, 4)

    sectors: dict[str, list[float]] = {}
    for r in rows:
        r["values"] = [r["avg"].get(q) for q in quarters]
        del r["avg"], r["last"]
        acc = sectors.setdefault(r["sector"], [0.0] * QUARTERS)
        for i, v in enumerate(r["values"]):
            acc[i] += v or 0
    payload = {
        "market": market, "currency": "KRW" if market == "kr" else "USD", "quarters": quarters, "asOf": last,
        "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "sectors": [{"name": n, "values": v} for n, v in sectors.items()],
        "failed": failed,
        "stocks": rows,
    }
    _cache[("quarters", market)] = (time.time(), payload)
    return payload


if __name__ == "__main__":
    from collections import defaultdict

    market = sys.argv[1] if len(sys.argv) > 1 else "kr"
    t = time.time()
    data = query_heatmap(TossClient.from_env(), market)
    sectors = defaultdict(lambda: [0, 0, 0])
    for s in data["stocks"]:
        sectors[s["sector"]][0] += s["cap"]
        sectors[s["sector"]][1] += s["value"]
        sectors[s["sector"]][2] += 1
    universe = KR_STOCKS if market == "kr" else US_STOCKS
    print(f"{len(data['stocks'])}/{len(universe)}종목, {time.time() - t:.1f}초, 국내 상장 ETF {data['etf']['total']}개")
    for name, (cap, value, n) in sorted(sectors.items(), key=lambda kv: -kv[1][0]):
        print(f"  {name:<14} {n:>3}종목  시총 {cap:>22,.0f}  거래대금 {value:>20,.0f}")
