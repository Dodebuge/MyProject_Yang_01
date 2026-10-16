"""코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다.

코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
종목별 최근 일봉(IVS11560)을 받아 주(월~일)별로 묶고, 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
(주봉 chrt_clsf=W의 거래량은 일봉 합과 맞지 않아 쓰지 않습니다.)
여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.

    python volume.py   # 콘솔로 전주 대비 10% 이상 오른 종목 확인
"""

from __future__ import annotations

import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime

import requests

from heatmap import _valid_day
from kb_client import KBApiError, KBClient

CACHE_SECONDS = 600  # 1,700번 넘게 부르는 조회라 10분 동안은 다시 부르지 않음
DAYS = 15            # 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
_cache: tuple[float, dict] | None = None
_lock = threading.Lock()  # 동시에 두 요청이 와도 KB 조회는 한 번


def week_change(rows: list[dict]) -> dict | None:
    """일봉 [{dt, vlm}] -> 이번 주·전주 하루 평균 거래량과 증가율(%). 두 주가 안 되면 None."""
    weeks: dict[tuple[int, int], list[int]] = {}
    for r in rows:
        d = str(r.get("dt") or "")
        if _valid_day(d):  # 필드가 밀린 행은 날짜 자리에 엉뚱한 숫자
            weeks.setdefault(date(int(d[:4]), int(d[4:6]), int(d[6:])).isocalendar()[:2], []).append(int(r.get("vlm") or 0))
    if len(weeks) < 2:
        return None
    this, prev = (weeks[k] for k in sorted(weeks, reverse=True)[:2])
    now, before = sum(this) / len(this), sum(prev) / len(prev)
    return {"avg": round(now), "prevAvg": round(before), "days": len(this), "prevDays": len(prev),
            "ratio": round((now / before - 1) * 100, 2) if before else None}


def sane(rows: list[dict]) -> bool:
    """필드가 밀리지 않은 일봉인지: 날짜가 실제 날짜이고 최신순으로 겹치지 않으며, 거래량이 숫자."""
    days = [str(r.get("dt") or "") for r in rows]
    return (bool(rows) and all(map(_valid_day, days)) and days == sorted(set(days), reverse=True)
            and all(type(r.get("vlm")) is int for r in rows))


def fetch(client: KBClient) -> list[dict]:
    listed = client.call("IVU10210", {"srt_clsf": "", "inq_cnt": "3000", "excg_clsf": "0", "mkt_clsf": "3",
                                      "thdy_bdy_clsf": ""}).get("out2", [])

    def one(row: dict) -> dict | None:
        for _ in range(3):  # 밀린 응답·500 오류면 다시
            try:
                rows = client.call("IVS11560", {"info_ccd": "1", "mkt_clsf": "1", "chrt_clsf": "D", "minute_tck_indx": "",
                                                "is_cd": row["is_cd"], "inq_clsf": "2", "strt_dy": "", "inq_cnt": str(DAYS)}).get("out2", [])
            except (KBApiError, requests.RequestException):
                continue
            if sane(rows):
                break
        else:
            return None  # 거래정지 등
        w = week_change(rows)
        if not w:
            return None
        return {"code": row["is_cd"], "name": row["is_nm"], "price": row.get("now_prc"),
                "change": float(row.get("up_dwn_r_p2") or 0), "value": int(row.get("dl_tw_amt") or 0), **w}

    client.access_token  # 병렬 호출 전에 토큰을 한 번만 발급
    with ThreadPoolExecutor(8) as pool:
        return [s for s in pool.map(one, listed) if s]


def query(client: KBClient, refresh: bool = False) -> dict:
    """GET /api/kosdaq-volume -> {"fetchedAt", "total", "stocks": [...]} (stocks는 전 종목, 화면에서 거름)"""
    global _cache
    with _lock:
        if not refresh and _cache and time.time() - _cache[0] < CACHE_SECONDS:
            return _cache[1]
        stocks = fetch(client)
        payload = {"fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "total": len(stocks), "stocks": stocks}
        _cache = (time.time(), payload)
        return payload


if __name__ == "__main__":
    # 자체 점검: 휴장일로 3일뿐인 전주와 5일인 이번 주를 하루 평균으로 비교
    rows = [{"dt": d, "vlm": v} for d, v in [("20261016", 200), ("20261015", 100), ("20261014", 100), ("20261013", 100),
                                             ("20261012", 100), ("20261008", 100), ("20261007", 100), ("20261006", 100),
                                             ("20261002", 999)]]
    assert week_change(rows) == {"avg": 120, "prevAvg": 100, "days": 5, "prevDays": 3, "ratio": 20.0}
    assert week_change(rows[:5]) is None
    assert sane(rows) and not sane([{"dt": "20261016", "vlm": ""}]) and not sane(rows[1:2] + rows[:1]) and not sane([])
    if "--offline" in sys.argv:
        print("ok")
        sys.exit()
    t = time.time()
    data = query(KBClient.from_env())
    up = sorted((s for s in data["stocks"] if (s["ratio"] or 0) >= 10), key=lambda s: -s["ratio"])
    print(f"{data['total']}종목 중 {len(up)}종목이 전주 대비 10% 이상, {time.time() - t:.1f}초")
    for s in up[:20]:
        print(f"  {s['name']:<14} {s['ratio']:>8.1f}%  하루 평균 {s['prevAvg']:>12,} -> {s['avg']:>12,}")
