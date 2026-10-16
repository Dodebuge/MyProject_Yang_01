"""러셀 2000 거래량 (코스닥 거래량과 같은 두 보기: 주간 평균, 같은 시각).

KB OpenAPI에는 미국 종목 목록·순위 TR이 없어, 구성 종목은 russell2000.csv(.env와 같은 폴더)에서 읽습니다.
iShares IWM(러셀 2000 ETF) 페이지 Holdings의 'Download' CSV를 그대로 넣으면 되고(Asset Class가 Equity인 행만),
Ticker 열(선택: Name, Exchange)만 있는 CSV도 됩니다. 구성 종목이 바뀌면(연 1~2회 리밸런싱) 파일만 바꾸면 됩니다.

주간 평균: 종목별 일봉(GSC10060, 차트와 같은 약 1년)으로 이번 주·전주 하루 평균 거래량 비교 (volume.week_change와 같은 계산).
         종가도 함께 보내 화면이 기술적 시그널을 태그로 보여 줍니다 (volume.closes).
같은 시각: 뉴욕 장중(평일 10:00~16:30, 뉴욕 시간) 30분마다 종목별 상세시세(GSS10030)의 누적 거래량을 russell.db에 저장.
         KB 미국 시세는 15분 지연이라 10:00 기록은 09:45 무렵 값이고, 16:30 기록에 16:00 종가까지 담깁니다.
화면·기록의 종목 코드는 KB 티커(MOG.A)라 차트 링크로 바로 열립니다.
거래소: CSV의 Exchange 열(NASDAQ, New York Stock Exchange 등)을 먼저 쓰고, 없거나 틀리면 NAS·NYS·AMX 순으로 찾습니다.
       iShares 표기(MOGA)가 KB 표기(MOG.A)와 다르면 마지막 글자 앞에 점을 넣어 한 번 더 찾습니다.

    python russell.py           # 주간 평균 전주 대비 10% 이상 오른 종목 (CSV 필요)
    python russell.py --offline # CSV 읽기·거래소 이름 자체 점검
    python russell.py --check   # russell2000.csv 종목을 실제 KB에서 찾아 봄 (Java: RussellTest -Drussell.live=true)
"""

from __future__ import annotations

import csv
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime
from datetime import time as dtime
from pathlib import Path
from typing import Callable
from zoneinfo import ZoneInfo

import requests

from kb_client import KBApiError, KBClient
import chart
from volume import Ticks, closes, sane, week_change, whole

HERE = Path(__file__).resolve().parent
CSV = HERE / "russell2000.csv"
NEW_YORK = ZoneInfo("America/New_York")
EXCHANGES = ("NAS", "NYS", "AMX")
TICKER = re.compile(r"[A-Z][A-Z0-9.]{0,7}")
CACHE_SECONDS = 600  # 2,000번 가까이 부르는 조회라 10분 동안은 다시 부르지 않음
DAYS = 15            # 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
WORKERS = 8
_cache: tuple[float, dict] | None = None
_lock = threading.Lock()  # 동시에 두 요청이 와도 KB 조회는 한 번
_found: dict[str, tuple[str, str]] = {}  # 티커 -> (KB 거래소, KB 티커). 한 번 찾으면 다시 찾지 않음


class _Shifted(Exception):
    """필드가 밀린 응답 (다시 부름)."""


def exchange_of(name: str) -> str:
    """iShares CSV의 Exchange 값 -> KB 거래소 코드 (모르면 '')."""
    n = name.lower()
    if "nasdaq" in n:
        return "NAS"
    if "new york stock exchange" in n or n.strip() in ("nyse", "nys"):
        return "NYS"
    if any(k in n for k in ("mkt", "american", "arca", "cboe", "bats", "amex")):
        return "AMX"
    return ""


def load_list(path: Path = CSV) -> list[dict]:
    """russell2000.csv -> [{"code", "name", "exchange"}]. 파일이 없거나 Ticker 열이 없으면 ValueError (화면에 그대로 보임)."""
    if not path.is_file():
        raise ValueError(f"{path.name}이 없습니다. iShares IWM(러셀 2000 ETF) 페이지 Holdings에서 CSV를 받아 "
                         f"{path.parent}에 {path.name}로 넣어 주세요.")
    lines = path.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    head = next((i for i, line in enumerate(lines) if line.lstrip('"').startswith("Ticker")), None)
    if head is None:
        raise ValueError(f"{path.name}에서 Ticker 열을 찾지 못했습니다.")
    out, seen = [], set()
    for r in csv.DictReader(lines[head:]):
        code = (r.get("Ticker") or "").strip().upper()
        if (r.get("Asset Class") or "Equity").strip() != "Equity" or not TICKER.fullmatch(code) or code in seen:
            continue  # 현금·선물 행, 표 아래 안내문
        seen.add(code)
        out.append({"code": code, "name": (r.get("Name") or code).strip(), "exchange": exchange_of(r.get("Exchange") or "")})
    if not out:
        raise ValueError(f"{path.name}에 종목이 없습니다.")
    return out


def _resolve(client: KBClient, item: dict, probe: Callable[[str, str], dict | None]) -> dict | None:
    """probe(거래소, KB 티커)가 값을 돌려줄 때까지 거래소·티커 표기를 바꿔 가며 부릅니다. 밀린 응답·500 오류면 세 번까지."""
    code = item["code"]
    if code in _found:
        tries = [_found[code]]
    else:
        names = [code] + ([code[:-1] + "." + code[-1]] if len(code) >= 4 and "." not in code else [])
        exchanges = [item["exchange"]] + [x for x in EXCHANGES if x != item["exchange"]] if item["exchange"] else list(EXCHANGES)
        tries = [(x, n) for n in names for x in exchanges]
    for x, n in tries:
        for _ in range(3):
            try:
                got = probe(x, n)
            except (KBApiError, requests.RequestException, _Shifted):
                continue
            if got:
                _found[code] = (x, n)
                return got
            break  # 빈 응답: 이 거래소·표기에는 없음
    return None


def _quote(client: KBClient, item: dict) -> dict | None:
    def probe(x: str, n: str) -> dict | None:
        q = client.call("GSS10030", {"krx_cd": x, "is_cd": n})
        if q.get("now_prc_p4") in ("", None):
            return None  # 이 거래소·표기에는 없음
        if not isinstance(q["now_prc_p4"], (int, float)) or type(q.get("vlm")) is not int:
            raise _Shifted
        return q
    return _resolve(client, item, probe)


def _candles(client: KBClient, item: dict) -> list[dict] | None:
    def probe(x: str, n: str) -> list[dict] | None:
        rows = client.call("GSC10060", {"chrt_clsf": "3", "mdfy_stk_prc_use_f": "", "is_cd": n, "clsf": "1",  # chrt_clsf 3 = 일봉
                                        "srch_strt_dy": date.today().strftime("%Y%m%d"), "rcrd_c": chart.CHART_DAYS, "bndl": "",
                                        "krx_cd": x}).get("out2", [])
        if not rows:
            return None  # 이 거래소·표기에는 없음
        if not sane(rows[:DAYS]):  # 주간 비교에 쓰는 최근 행만 엄격히 (오래된 밀린 행은 closes에서 건너뜀)
            raise _Shifted
        return rows
    return _resolve(client, item, probe)


def fetch(client: KBClient) -> list[dict]:
    items = load_list()

    def one(item: dict) -> dict | None:
        rows = _candles(client, item)
        for _ in range(2):  # 오래된 행이 깨졌으면 한 번 더 (끝내 안 되면 깨진 행만 빼고 씀)
            if not rows or whole(rows, "p4"):
                break
            rows = _candles(client, item) or rows
        w = week_change(rows[:DAYS]) if rows else None
        if not w:
            return None
        last = rows[0]
        return {"code": _found[item["code"]][1], "name": item["name"], "price": last.get("cls_prc_p4"),
                "change": float(last.get("up_dwn_r_p2") or 0), "value": int(last.get("dl_tw_amt") or 0), **w,
                **closes(chart._candles(rows, "p4", 1))}

    client.access_token  # 병렬 호출 전에 토큰을 한 번만 발급
    with ThreadPoolExecutor(WORKERS) as pool:
        return [s for s in pool.map(one, items) if s]


def query(client: KBClient, refresh: bool = False) -> dict:
    """GET /api/russell-volume -> {"fetchedAt", "total", "listed", "stocks": [...]} (volume.query와 같은 모양, listed: CSV 종목 수)"""
    global _cache
    with _lock:
        if not refresh and _cache and time.time() - _cache[0] < CACHE_SECONDS:
            return _cache[1]
        listed = len(load_list())
        stocks = fetch(client)
        payload = {"fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "total": len(stocks), "listed": listed, "stocks": stocks}
        _cache = (time.time(), payload)
        return payload


def check(client: KBClient) -> dict:
    """점검: CSV 종목마다 KB 시세를 찾아 {"listed", "found", "missing": [...], "renamed": ["MOGA -> NYS:MOG.A", ...]}."""
    items = load_list()
    client.access_token
    with ThreadPoolExecutor(WORKERS) as pool:
        missing = [i["code"] for i, q in zip(items, pool.map(lambda i: _quote(client, i), items)) if not q]
    renamed = [f"{i['code']} -> {_found[i['code']][0]}:{_found[i['code']][1]}" for i in items
               if i["code"] in _found and _found[i["code"]][1] != i["code"]]
    return {"listed": len(items), "found": len(items) - len(missing), "missing": missing, "renamed": renamed}


def _rows(client: KBClient, at: datetime) -> list[list]:
    """러셀 2000 종목별 상세시세. 그날(뉴욕 날짜) 체결이 있는 종목만 (휴장일이면 빈 목록)."""
    items = load_list()
    day = at.strftime("%Y%m%d")

    def one(item: dict) -> list | None:
        q = _quote(client, item)
        if not q or q.get("dt") != day:
            return None
        return [_found[item["code"]][1], item["name"], q["now_prc_p4"], float(q.get("up_dwn_r_p2") or 0), q["vlm"], int(q.get("dl_tw_amt") or 0)]

    client.access_token
    with ThreadPoolExecutor(WORKERS) as pool:
        return [r for r in pool.map(one, items) if r]


# 뉴욕 장중 30분마다 (10:00~16:30 뉴욕 시간). 하루 약 1.5MB
RUSSELL = Ticks("러셀 2000 30분", HERE / "russell.db", "russell_tick", NEW_YORK, 30, dtime(10, 0), dtime(16, 30), _rows)


if __name__ == "__main__":
    import tempfile

    # 자체 점검: iShares CSV 모양 (위 안내 줄, 현금 행, 아래 안내문), 거래소 이름, 뉴욕 기록 시각
    sample = Path(tempfile.mkdtemp()) / "russell2000.csv"
    sample.write_text('﻿iShares Russell 2000 ETF\nFund Holdings as of,"Oct 15, 2026"\n\xa0\n'
                      'Ticker,Name,Sector,Asset Class,Market Value,Weight (%),Exchange\n'
                      '"CRDO","CREDO TECHNOLOGY","Information Technology","Equity","1","0.5","NASDAQ"\n'
                      '"MOGA","MOOG INC CLASS A","Industrials","Equity","1","0.1","New York Stock Exchange Inc."\n'
                      '"USD","USD CASH","Cash and/or Derivatives","Cash","1","0.1","-"\n'
                      '"CRDO","CREDO TECHNOLOGY","Information Technology","Equity","1","0.5","NASDAQ"\n'
                      '\xa0\n"The content contained herein is owned or licensed by BlackRock"\n', encoding="utf-8")
    assert load_list(sample) == [{"code": "CRDO", "name": "CREDO TECHNOLOGY", "exchange": "NAS"},
                                 {"code": "MOGA", "name": "MOOG INC CLASS A", "exchange": "NYS"}], load_list(sample)
    sample.write_text("Ticker\nAAPL\nmsft\n", encoding="utf-8")
    assert [s["code"] for s in load_list(sample)] == ["AAPL", "MSFT"]
    assert [exchange_of(x) for x in ("NASDAQ", "New York Stock Exchange Inc.", "Nyse Mkt Llc", "Cboe BZX", "-")] == ["NAS", "NYS", "AMX", "AMX", ""]
    k = lambda *a: datetime(2026, 10, *a)
    nt = RUSSELL.next_tick
    assert nt(k(16, 9, 0)) == k(16, 10, 0) and nt(k(16, 10, 5)) == k(16, 10, 30) and nt(k(16, 16, 10)) == k(16, 16, 30)
    assert nt(k(16, 16, 30)) == k(19, 10, 0)
    if "--offline" in sys.argv:
        print("ok")
        sys.exit()
    if "--check" in sys.argv:
        r = check(KBClient.from_env())
        print(f"{CSV}: {r['listed']}종목 중 {r['found']}종목 KB에서 찾음")
        print(f"  표기를 바꿔 찾은 종목 {len(r['renamed'])}개: {r['renamed']}")
        print(f"  못 찾은 종목 {len(r['missing'])}개: {r['missing']}")
        sys.exit(0 if r["found"] >= r["listed"] * 0.95 else 1)
    t = time.time()
    data = query(KBClient.from_env())
    up = sorted((s for s in data["stocks"] if (s["ratio"] or 0) >= 10), key=lambda s: -s["ratio"])
    print(f"CSV {data['listed']}종목 중 {data['total']}종목 조회, {len(up)}종목이 전주 대비 10% 이상, {time.time() - t:.1f}초")
    for s in up[:20]:
        print(f"  {s['code']:<6} {s['name'][:24]:<24} {s['ratio']:>8.1f}%  하루 평균 {s['prevAvg']:>12,} -> {s['avg']:>12,}")
