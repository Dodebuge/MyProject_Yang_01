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

import requests

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


def _parallel(fn, items, workers: int = 8):
    with ThreadPoolExecutor(workers) as pool:
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
        except (KBApiError, requests.RequestException):
            return None
        index_name = str(m.get("indx_nm") or "")
        sector = index_name.replace("코스피 ", "").replace("코스닥 ", "").strip()
        if not sector:  # ETF·ETN
            return None
        return {
            "code": row["is_cd"], "name": row["is_nm"], "sector": sector,
            "board": "KOSDAQ" if index_name.startswith("코스닥") else "KOSPI",  # 차트 TR의 mkt_clsf에 필요
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
        except (KBApiError, requests.RequestException):
            return None
        return {
            "code": symbol, "name": symbol, "sector": sector, "exchange": exchange,
            "cap": float(q.get("opn_prc_tl_amt") or 0),
            "value": float(q.get("dl_tw_amt") or 0),
            "change": float(q.get("up_dwn_r_p2") or 0),
            "price": q.get("now_prc_p4"),
            "date": q.get("dt"),
        }

    return [s for s in _parallel(one, US_STOCKS) if s and s["cap"]]


US_SECTOR = {symbol: sector for symbol, _, sector in US_STOCKS} | {"GOOG": "커뮤니케이션"}


def fetch_my(client: KBClient) -> dict:
    """내 보유 종목과 현금. 매번 잔고 TR로 조회합니다.

    국내: SSQM2952 보유종목 잔고 중 원화 종목(구분 "현금"). 해외 종목도 섞여 오지만 해외는 SPQM2226을 씁니다.
    해외: SPQM2226 해외주식 잔고 (소수점 보유 포함). 같은 종목의 온주·소수점 행은 합칩니다.
    cap = 평가금액(원), pl = 수익률(%; 해외는 달러 기준), change = 오늘 등락률(%).
    현금 = D+2 추정예수금(결제 예정 매매 반영) + 외화예수금 원화환산.

    현금이 D+2 기준이라 종목도 결제 후 잔량(ec_q) 기준으로 셉니다. 매도해 결제를 기다리는 종목은
    hld_q가 남아 있어도 ec_q가 0이고, 그 대금은 이미 D+2 예수금에 들어 있습니다.
    원화 평가금액은 국내·해외 모두 SSQM2952의 val_amt를 씁니다(KB 앱 표시와 같은 환율).
    """
    holdings: dict[str, dict] = {}
    balance = client.call("SSQM2952", {"excg_mktpr_ccd": ""})
    cash = {
        "krw": int(balance.get("nxt2_dy_tfnd") or 0),
        "fx_krw": round(float(balance.get("fcrncy_tfnd_krw_exch_amt") or 0)),
        "today": int(balance.get("dy_tfnd") or 0),
    }

    fx_value: dict[str, int] = {}  # 해외 종목 원화 평가금액 (온주·소수점 행 합계)
    for r in balance.get("Record1", []):
        if r.get("crncy_cd"):
            fx_value[r["is_cd"]] = fx_value.get(r["is_cd"], 0) + int(r.get("val_amt") or 0)
            continue
        qty = int(r.get("ec_q") or 0)
        if not qty:
            continue
        code, name = str(r["is_cd"]).removeprefix("A"), r["is_nm"]
        cost = int(r.get("byng_amt") or 0)
        is_etf = name.split(" ")[0] in ETF_BRANDS
        kr_group, us_sector, _ = classify_etf(name)
        holdings[code] = {
            "code": code, "name": name, "market": "국내", "qty": qty, "etf": is_etf,
            # 해외 보유와 같은 기준으로 묶도록 섹터 ETF는 GICS 섹터 이름을 씁니다.
            "sector": (us_sector or kr_group) if is_etf else None,
            "cap": int(r.get("val_amt") or 0),
            "cost": cost, "pl": float(r.get("val_yld") or 0) if cost else None,
        }

    for r in client.call("SPQM2226", {"fee_clsf": "", "nxt_key": "", "std_crncy_f": "1", "cn_f": "",
                                      "exch_r_aplc_f": "1"}).get("Record2", []):
        code = r["is_cd"]
        h = holdings.setdefault(code, {
            "code": code, "name": r["is_nm"], "market": "미국", "exchange": r.get("mkt_clsf"), "qty": 0.0,
            "etf": False, "sector": US_SECTOR.get(code, "미분류"), "cap": 0, "cost_usd": 0.0,
        })
        qty = float(r.get("frgn_hld_q_p6") or 0)
        h["qty"] += qty
        h["cap"] = fx_value.get(code) or h["cap"] + int(r.get("krw_val_amt") or 0)
        h["cost_usd"] += qty * float(r.get("byng_avr_prc_p4") or 0)
        h["price"] = float(r.get("now_prc_p4") or 0)

    def quote(h: dict) -> dict:
        try:
            if h["market"] == "국내":
                q = client.call("IVU10140", {"excg_clsf": "0", "shrt_cd": h["code"]})
                h["price"] = q.get("now_prc")
                if h["sector"] is None:
                    m = client.call("IVM10050", {"is_cd": h["code"]})
                    h["sector"] = str(m.get("indx_nm") or "미분류").replace("코스피 ", "").replace("코스닥 ", "").strip()
            else:
                q = client.call("GSS10030", {"krx_cd": h.pop("exchange"), "is_cd": h["code"]})
                cost = h.pop("cost_usd")
                h["pl"] = (h["qty"] * h["price"] / cost - 1) * 100 if cost else None
                h["qty"] = round(h["qty"], 6)
            h["change"] = float(q.get("up_dwn_r_p2") or 0)
        except KBApiError:
            h["change"] = 0.0
        h["value"] = h["cap"]  # 크기 기준을 평가금액 하나로 통일
        return h

    return {"stocks": [h for h in _parallel(quote, list(holdings.values())) if h["cap"] > 0], "cash": cash}


def query_holdings(client: KBClient) -> dict:
    """내 보유 종목 (내정보 화면). 서버 메모리에 60초만 캐시하고 파일로 저장하지 않습니다."""
    hit = _cache.get("holdings")
    if hit and time.time() - hit[0] < CACHE_SECONDS:
        return hit[1]
    client.access_token
    payload = {"fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), **fetch_my(client)}
    _cache["holdings"] = (time.time(), payload)
    return payload


# ---------------------------------------------------------------------- 분기별 거래대금

QUARTER_CACHE_SECONDS = 600  # 과거 분기 값은 자주 바뀌지 않음
QUARTERS = 5                 # 최근 5개 분기 (진행 중인 분기 포함)


def _amount(value) -> float | None:
    """거래대금 값. 가끔 오는 깨진 값(예: '5803-3893')은 None."""
    try:
        return float(value or 0)
    except (TypeError, ValueError):
        return None


def _valid_day(d: str) -> bool:
    """실제 달력 날짜이고 오늘 이전인지. 필드가 밀린 행은 날짜 자리에 엉뚱한 숫자가 옵니다."""
    try:
        return date(2000, 1, 1) <= datetime.strptime(d, "%Y%m%d").date() <= date.today()
    except ValueError:
        return False


def daily_values(client: KBClient, stock: dict, market: str) -> list[tuple[str, float]]:
    """종목의 최근 약 400거래일 (날짜, 거래대금). 국내 원, 미국 달러."""
    if market == "kr":
        rows = client.call("IVS11560", {
            "info_ccd": "1", "mkt_clsf": "1" if stock.get("board") == "KOSDAQ" else "0", "chrt_clsf": "D",
            "minute_tck_indx": "", "is_cd": stock["code"], "inq_clsf": "2", "strt_dy": "", "inq_cnt": "400",
        }).get("out2", [])
        days = [(str(r["dt"]), _amount(r.get("dl_tw_amt"))) for r in rows]
        return [(d, v * 10) for d, v in days if v is not None and _valid_day(d)]  # 국내 차트 거래대금은 10원 단위
    rows = client.call("GSC10060", {
        "chrt_clsf": "3", "mdfy_stk_prc_use_f": "", "is_cd": stock["code"], "clsf": "1",  # chrt_clsf 3 = 일봉
        "srch_strt_dy": date.today().strftime("%Y%m%d"), "rcrd_c": "400", "bndl": "", "krx_cd": stock["exchange"],
    }).get("out2", [])
    days = [(str(r["dt"]), _amount(r.get("dl_tw_amt"))) for r in rows]
    return [(d, v) for d, v in days if v is not None and _valid_day(d)]


def quarter_of(yyyymmdd: str) -> str:
    return f"{yyyymmdd[:4]}Q{(int(yyyymmdd[4:6]) - 1) // 3 + 1}"


def query_quarters(client: KBClient, market: str) -> dict:
    """히트맵과 같은 종목의 분기별 일평균 거래대금. 섹터 값 = 소속 종목 일평균의 합."""
    hit = _cache.get(("quarters", market))
    if hit and time.time() - hit[0] < QUARTER_CACHE_SECONDS:
        return hit[1]
    stocks = query_heatmap(client, market)["stocks"]

    def one(stock: dict) -> dict:
        try:
            days = daily_values(client, stock, market)
        except (KBApiError, requests.RequestException):  # 재시도 후에도 실패한 종목은 빼고 개수만 알림
            days = []
        by_q: dict[str, list[float]] = {}
        for d, v in days:
            by_q.setdefault(quarter_of(d), []).append(v)
        return {"code": stock["code"], "name": stock["name"], "sector": stock["sector"], "last": max((d for d, _ in days), default=""),
                "avg": {q: sum(vs) / len(vs) for q, vs in by_q.items()}}

    rows = _parallel(one, stocks, workers=4)
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
