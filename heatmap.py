"""국내·해외 섹터 히트맵 데이터.

국내 상장 ETF: 시가총액 순위 전체(IVS10920)에서 운용사 브랜드로 ETF를 골라, 이름 키워드로 섹터를 나눕니다.
              조회할 때마다 ETF 코드 목록을 etf_snapshot.json에 날짜별로 저장해, 직전 날짜 대비 신규 상장을 셉니다.

국내: 시가총액 상위(IVS10920) + 거래대금 상위(IVU10210) 종목을 모아, 종목별 투자지표(IVM10050)에서
      업종(indx_nm), 시가총액, 거래대금을 받습니다. 업종이 없는 ETF·ETN은 뺍니다.
해외: KB OpenAPI에 섹터·순위 TR이 없어 아래 US_STOCKS(미국 대형주, GICS 섹터)를 해외주식 현재가(GSS10030)로 조회합니다.

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

from kb_client import KBApiError, KBClient

# (티커, 거래소, 섹터). 거래소: NAS=나스닥, NYS=뉴욕
# ponytail: 수동 목록, 편입·상장 거래소 변경 시 여기만 고치면 됩니다.
US_STOCKS = [
    *[(s, x, "정보기술") for s, x in [
        ("NVDA", "NAS"), ("AAPL", "NAS"), ("MSFT", "NAS"), ("AVGO", "NAS"), ("ORCL", "NYS"), ("AMD", "NAS"),
        ("PLTR", "NAS"), ("CRM", "NYS"), ("CSCO", "NAS"), ("IBM", "NYS"), ("MU", "NAS"), ("ACN", "NYS"),
        ("INTU", "NAS"), ("NOW", "NYS"), ("QCOM", "NAS"), ("TXN", "NAS"), ("AMAT", "NAS"), ("LRCX", "NAS"),
        ("KLAC", "NAS"), ("ADBE", "NAS"), ("ANET", "NYS"), ("INTC", "NAS"), ("ADI", "NAS"), ("PANW", "NAS"),
        ("CRWD", "NAS"), ("APH", "NYS"), ("DELL", "NYS")]],
    *[(s, x, "커뮤니케이션") for s, x in [
        ("GOOGL", "NAS"), ("META", "NAS"), ("NFLX", "NAS"), ("TMUS", "NAS"), ("DIS", "NYS"), ("T", "NYS"),
        ("VZ", "NYS"), ("CMCSA", "NAS")]],
    *[(s, x, "경기소비재") for s, x in [
        ("AMZN", "NAS"), ("TSLA", "NAS"), ("HD", "NYS"), ("MCD", "NYS"), ("BKNG", "NAS"), ("LOW", "NYS"),
        ("TJX", "NYS"), ("SBUX", "NAS"), ("NKE", "NYS")]],
    *[(s, x, "필수소비재") for s, x in [
        ("WMT", "NAS"), ("COST", "NAS"), ("PG", "NYS"), ("KO", "NYS"), ("PEP", "NAS"), ("PM", "NYS"), ("MO", "NYS")]],
    *[(s, x, "헬스케어") for s, x in [
        ("LLY", "NYS"), ("JNJ", "NYS"), ("UNH", "NYS"), ("ABBV", "NYS"), ("MRK", "NYS"), ("TMO", "NYS"),
        ("ABT", "NYS"), ("ISRG", "NAS"), ("AMGN", "NAS"), ("PFE", "NYS"), ("GILD", "NAS"), ("DHR", "NYS"),
        ("BSX", "NYS"), ("VRTX", "NAS")]],
    *[(s, x, "금융") for s, x in [
        ("BRK.B", "NYS"), ("JPM", "NYS"), ("V", "NYS"), ("MA", "NYS"), ("BAC", "NYS"), ("WFC", "NYS"),
        ("GS", "NYS"), ("MS", "NYS"), ("AXP", "NYS"), ("C", "NYS"), ("SCHW", "NYS"), ("BLK", "NYS"),
        ("SPGI", "NYS"), ("PGR", "NYS"), ("COIN", "NAS")]],
    *[(s, x, "산업재") for s, x in [
        ("GE", "NYS"), ("CAT", "NYS"), ("RTX", "NYS"), ("BA", "NYS"), ("HON", "NAS"), ("UNP", "NYS"),
        ("UBER", "NYS"), ("DE", "NYS"), ("LMT", "NYS"), ("ETN", "NYS"), ("GEV", "NYS"), ("UPS", "NYS")]],
    *[(s, x, "에너지") for s, x in [("XOM", "NYS"), ("CVX", "NYS"), ("COP", "NYS"), ("EOG", "NYS"), ("SLB", "NYS")]],
    *[(s, x, "소재") for s, x in [("LIN", "NAS"), ("SHW", "NYS"), ("FCX", "NYS"), ("NEM", "NYS"), ("ECL", "NYS")]],
    *[(s, x, "유틸리티") for s, x in [("NEE", "NYS"), ("SO", "NYS"), ("DUK", "NYS"), ("CEG", "NAS"), ("VST", "NYS")]],
    *[(s, x, "부동산") for s, x in [("PLD", "NYS"), ("AMT", "NYS"), ("EQIX", "NAS"), ("WELL", "NYS"), ("SPG", "NYS")]],
]

# ---------------------------------------------------------------------- 국내 상장 ETF

ETF_OTHER = "배당·전략·기타"

# 운용사 브랜드. 종목명이 이 단어로 시작하면 ETF로 봅니다 (ETN은 이름 끝이 "ETN").
ETF_BRANDS = ("KODEX", "TIGER", "RISE", "ACE", "PLUS", "SOL", "KIWOOM", "HANARO", "1Q", "KoAct", "TIME", "WON",
              "에셋플러스", "BNK", "마이티", "HK", "MIDAS", "파워", "FOCUS", "UNICORN", "DAISHIN", "IBK", "DS",
              "TREX", "TRUSTON", "더제이")

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


def fetch_etfs(client: KBClient) -> list[dict]:
    """시가총액 순위 전체(IVS10920)에서 ETF만 골라 분류합니다. cap은 원 단위(순자산 대신 시가총액)."""
    rows = client.call("IVS10920", {"inq_cnt": "9999"}).get("out", [])
    etfs = []
    for r in rows:
        name = r["is_nm"]
        if name.split(" ")[0] not in ETF_BRANDS or name.endswith("ETN"):
            continue
        kr, us, is_sector = classify_etf(name)
        etfs.append({"code": r["is_cd"], "name": name, "kr": kr, "us": us, "sector": is_sector,
                     "cap": int(r.get("opn_prc_tl_amt") or 0) * 1_000_000})  # 백만원 -> 원
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


def etf_summary(client: KBClient) -> dict:
    etfs = fetch_etfs(client)
    return {"total": len(etfs), "items": etfs, "changes": update_snapshot(etfs)}


CACHE_SECONDS = 60
_cache: dict[str, tuple[float, dict]] = {}


def _parallel(fn, items):
    with ThreadPoolExecutor(8) as pool:
        return list(pool.map(fn, items))


def fetch_kr(client: KBClient) -> list[dict]:
    """국내 종목. cap·value는 원 단위."""
    ranked = {r["is_cd"]: r for r in client.call("IVS10920", {"inq_cnt": "200"}).get("out", [])}
    by_value = client.call("IVU10210", {"srt_clsf": "", "inq_cnt": "100", "excg_clsf": "0", "mkt_clsf": "1",
                                        "thdy_bdy_clsf": ""}).get("out2", [])
    for r in by_value:
        ranked.setdefault(r["is_cd"], r)

    def one(row: dict) -> dict | None:
        try:
            m = client.call("IVM10050", {"is_cd": row["is_cd"]})
        except KBApiError:
            return None
        sector = str(m.get("indx_nm") or "").replace("코스피 ", "").replace("코스닥 ", "").strip()
        if not sector:  # ETF·ETN
            return None
        return {
            "code": row["is_cd"], "name": row["is_nm"], "sector": sector,
            "cap": int(m.get("opn_prc_tl_amt") or 0) * 100_000_000,  # 억원 -> 원
            "value": int(m.get("dl_tw_amt") or 0),
            "change": float(row.get("up_dwn_r_p2") or 0),
            "price": row.get("now_prc"),
        }

    return [s for s in _parallel(one, list(ranked.values())) if s]


def fetch_us(client: KBClient) -> list[dict]:
    """미국 종목. cap·value는 달러 단위."""
    def one(item) -> dict | None:
        symbol, exchange, sector = item
        try:
            q = client.call("GSS10030", {"krx_cd": exchange, "is_cd": symbol})
        except KBApiError:
            return None
        return {
            "code": symbol, "name": symbol, "sector": sector,
            "cap": float(q.get("opn_prc_tl_amt") or 0),
            "value": float(q.get("dl_tw_amt") or 0),
            "change": float(q.get("up_dwn_r_p2") or 0),
            "price": q.get("now_prc_p4"),
            "date": q.get("dt"),
        }

    return [s for s in _parallel(one, US_STOCKS) if s and s["cap"]]


def query_heatmap(client: KBClient, market: str) -> dict:
    if market not in ("kr", "us"):
        raise ValueError(f"market은 kr 또는 us: {market}")
    hit = _cache.get(market)
    if hit and time.time() - hit[0] < CACHE_SECONDS:
        return hit[1]
    client.access_token  # 병렬 호출 전에 토큰을 한 번만 발급
    stocks = fetch_kr(client) if market == "kr" else fetch_us(client)
    etf = etf_summary(client)
    payload = {
        "market": market,
        "currency": "KRW" if market == "kr" else "USD",
        "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "stocks": stocks,
        "etf": etf,
    }
    _cache[market] = (time.time(), payload)
    return payload


if __name__ == "__main__":
    from collections import defaultdict

    market = sys.argv[1] if len(sys.argv) > 1 else "kr"
    t = time.time()
    data = query_heatmap(KBClient.from_env(), market)
    sectors = defaultdict(lambda: [0, 0, 0])
    for s in data["stocks"]:
        sectors[s["sector"]][0] += s["cap"]
        sectors[s["sector"]][1] += s["value"]
        sectors[s["sector"]][2] += 1
    print(f"{len(data['stocks'])}종목, {time.time() - t:.1f}초, 국내 상장 ETF {data['etf']['total']}개")
    for name, (cap, value, n) in sorted(sectors.items(), key=lambda kv: -kv[1][0]):
        print(f"  {name:<14} {n:>3}종목  시총 {cap:>22,.0f}  거래대금 {value:>20,.0f}")
