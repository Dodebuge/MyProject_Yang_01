"""코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다.

코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
종목별 최근 일봉(IVS11560)을 받아 주(월~일)별로 묶고, 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
(주봉 chrt_clsf=W의 거래량은 일봉 합과 맞지 않아 쓰지 않습니다.)
여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.

장중 10분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (아래 '10분 기록').

    python volume.py   # 콘솔로 전주 대비 10% 이상 오른 종목 확인
"""

from __future__ import annotations

import json
import sqlite3
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import date, datetime, timedelta
from datetime import time as dtime
from pathlib import Path

import requests

from daily_store import KST
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


# ---------------------------------------------------------------------- 10분 기록 (같은 시각 거래량 비교)
# 장중(평일 09:00~15:40) 10분마다 코스닥 전 종목의 누적 거래량·현재가를 kosdaq.db에 저장합니다 (IVU10210 한 번).
# 화면은 고른 시각의 누적 거래량을 전일·전주(7일 전 이전의 마지막 거래일) 같은 시각과 비교합니다.
# 같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록을 씁니다. Java 버전(Volume.java)과 같은 파일·같은 테이블.

TICK_DB = Path(__file__).resolve().parent / "kosdaq.db"
TICK_SCHEMA = """CREATE TABLE IF NOT EXISTS kosdaq_tick (
  at      TEXT PRIMARY KEY,  -- 한국 시간 10분 단위 '2026-10-16T09:10'
  payload TEXT NOT NULL      -- [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...] JSON
)"""
TICK_MINUTES = 10
TICK_OPEN, TICK_CLOSE = dtime(9, 0), dtime(15, 40)  # 15:40: 15:30 종가 단일가까지 담기게
TICK_KEEP_DAYS = 14      # 전주 비교에 7일 + 긴 연휴 여유 (하루 약 4MB)
TICK_DELAY_SECONDS = 30  # 정각 바로는 KB 시세·일봉이 아직 안 바뀌었을 수 있어 조금 뒤에 조회


def next_tick(now: datetime) -> datetime:
    """now 다음의 장중 10분 시각 (평일 09:00~15:40)."""
    t = now.replace(second=0, microsecond=0, minute=now.minute - now.minute % TICK_MINUTES) + timedelta(minutes=TICK_MINUTES)
    if t.time() < TICK_OPEN:
        t = t.replace(hour=TICK_OPEN.hour, minute=TICK_OPEN.minute)
    elif t.time() > TICK_CLOSE:
        t = (t + timedelta(days=1)).replace(hour=TICK_OPEN.hour, minute=TICK_OPEN.minute)
    while t.weekday() >= 5:  # 토·일이면 다음 날 09:00
        t = (t + timedelta(days=1)).replace(hour=TICK_OPEN.hour, minute=TICK_OPEN.minute)
    return t


def _tick_db():
    con = sqlite3.connect(TICK_DB, timeout=30)
    con.execute(TICK_SCHEMA)
    return con


def take_tick(client: KBClient, at: datetime) -> int:
    """at 시각의 코스닥 전 종목 시세를 저장하고 종목 수를 돌려줍니다. 휴장일(오늘 일봉이 없음)이면 저장하지 않고 0."""
    listed = client.call("IVU10210", {"srt_clsf": "", "inq_cnt": "3000", "excg_clsf": "0", "mkt_clsf": "3",
                                      "thdy_bdy_clsf": ""}).get("out2", [])
    rows = [[r["is_cd"], r["is_nm"], r.get("now_prc"), float(r.get("up_dwn_r_p2") or 0), r["vlm"], int(r.get("dl_tw_amt") or 0)]
            for r in listed if len(str(r.get("is_cd") or "")) == 6 and type(r.get("vlm")) is int]  # 필드가 밀린 행은 버림
    if not rows:
        return 0
    last = client.call("IVS11560", {"info_ccd": "1", "mkt_clsf": "1", "chrt_clsf": "D", "minute_tck_indx": "", "is_cd": rows[0][0],
                                    "inq_clsf": "2", "strt_dy": "", "inq_cnt": "1"}).get("out2", [])
    if not last or last[0].get("dt") != at.strftime("%Y%m%d"):
        return 0  # 휴장일: 시세가 마지막 거래일 값 그대로
    save_tick(at.strftime("%Y-%m-%dT%H:%M"), rows)
    return len(rows)


def save_tick(at: str, rows: list[list]) -> None:
    oldest = (datetime.fromisoformat(at) - timedelta(days=TICK_KEEP_DAYS)).strftime("%Y-%m-%dT%H:%M")
    with closing(_tick_db()) as con, con:
        con.execute("INSERT OR REPLACE INTO kosdaq_tick (at, payload) VALUES (?, ?)", (at, json.dumps(rows, ensure_ascii=False)))
        con.execute("DELETE FROM kosdaq_tick WHERE at < ?", (oldest,))


def intraday(day: str = "", at: str = "") -> dict:
    """GET /api/kosdaq-intraday?date=2026-10-16&at=10:30 (없으면 마지막 기록)
    -> {"date", "at", "dates", "slots", "prev": {"date", "at"}, "week": {...}, "nextTick",
        "stocks": [{code, name, price, change, vlm, value, prevVlm, prevRatio, weekVlm, weekRatio}]}"""
    with closing(_tick_db()) as con:
        ats = [r[0] for r in con.execute("SELECT at FROM kosdaq_tick ORDER BY at")]
        out = {"dates": sorted({a[:10] for a in ats}, reverse=True), "nextTick": next_tick(datetime.now(KST)).strftime("%Y-%m-%dT%H:%M")}
        if not ats:
            return {**out, "date": None, "at": None, "slots": [], "prev": None, "week": None, "stocks": []}
        day = day if day in out["dates"] else out["dates"][0]
        slots = [a[11:] for a in ats if a[:10] == day]
        at = max([s for s in slots if s <= at] or [slots[0]]) if at else slots[-1]

        def find(before: str) -> dict | None:  # before 이전 마지막 기록일의, at 이전 마지막 기록
            days = [d for d in out["dates"] if d < before]
            hit = [a for a in ats if days and a[:10] == days[0] and a[11:] <= at]
            return {"date": days[0], "at": hit[-1][11:]} if hit else None

        def load(ref: dict | None) -> list:
            row = con.execute("SELECT payload FROM kosdaq_tick WHERE at = ?", (f"{ref['date']}T{ref['at']}",)).fetchone() if ref else None
            return json.loads(row[0]) if row else []

        prev = find(day)
        week = find((date.fromisoformat(day) - timedelta(days=6)).isoformat())  # 7일 전(같은 요일), 휴장이면 그 이전 마지막 기록일
        now = load({"date": day, "at": at})
        prev_v = {r[0]: r[4] for r in load(prev)}
        week_v = {r[0]: r[4] for r in load(week)}
    ratio = lambda v, b: round((v / b - 1) * 100, 2) if b else None
    stocks = [{"code": c, "name": n, "price": p, "change": ch, "vlm": v, "value": amt,
               "prevVlm": prev_v.get(c), "prevRatio": ratio(v, prev_v.get(c)),
               "weekVlm": week_v.get(c), "weekRatio": ratio(v, week_v.get(c))} for c, n, p, ch, v, amt in now]
    return {**out, "date": day, "at": at, "slots": slots, "prev": prev, "week": week, "stocks": stocks}


def start(client: KBClient) -> None:
    """백그라운드 스레드로 장중 10분마다 take_tick."""
    def loop():
        while True:
            at = next_tick(datetime.now(KST))
            while (wait := (at - datetime.now(KST)).total_seconds() + TICK_DELAY_SECONDS) > 0:
                time.sleep(min(wait, 600))  # 길게 한 번에 자지 않음 (PC 절전 후 시계가 건너뛰어도 늦지 않게)
            try:
                n = take_tick(client, at)
                print(f"[코스닥 10분] {at:%Y-%m-%d %H:%M} " + (f"{n}종목 저장" if n else "휴장 · 저장 안 함"), flush=True)
            except Exception as e:  # KB 오류여도 다음 시각에 다시
                print(f"[코스닥 10분] {at:%Y-%m-%d %H:%M} 실패: {type(e).__name__}: {e}", flush=True)

    threading.Thread(target=loop, name="kosdaq-tick", daemon=True).start()
    print(f"[코스닥 10분] 장중 10분마다 코스닥 거래량 기록 -> {TICK_DB.name} (다음 {next_tick(datetime.now(KST)):%m-%d %H:%M})", flush=True)


if __name__ == "__main__":
    import tempfile

    # 자체 점검: 휴장일로 3일뿐인 전주와 5일인 이번 주를 하루 평균으로 비교
    rows = [{"dt": d, "vlm": v} for d, v in [("20261016", 200), ("20261015", 100), ("20261014", 100), ("20261013", 100),
                                             ("20261012", 100), ("20261008", 100), ("20261007", 100), ("20261006", 100),
                                             ("20261002", 999)]]
    assert week_change(rows) == {"avg": 120, "prevAvg": 100, "days": 5, "prevDays": 3, "ratio": 20.0}
    assert week_change(rows[:5]) is None
    assert sane(rows) and not sane([{"dt": "20261016", "vlm": ""}]) and not sane(rows[1:2] + rows[:1]) and not sane([])
    # 10분 시각: 장중만, 15:40 다음은 다음 평일 09:00, 금요일 다음은 월요일
    k = lambda *a: datetime(2026, 10, *a)
    assert next_tick(k(16, 9, 3)) == k(16, 9, 10) and next_tick(k(16, 8, 0)) == k(16, 9, 0)
    assert next_tick(k(15, 15, 40)) == k(16, 9, 0) and next_tick(k(16, 15, 35)) == k(16, 15, 40)
    assert next_tick(k(16, 23, 0)) == k(19, 9, 0) and next_tick(k(17, 12, 0)) == k(19, 9, 0)
    # 같은 시각 비교: 전일 = 직전 기록일, 전주 = 7일 전 이전의 마지막 기록일. 같은 시각이 없으면 그 이전 마지막 기록
    TICK_DB = Path(tempfile.mkdtemp()) / "kosdaq.db"
    for at, v in [("2026-10-08T10:30", 50), ("2026-10-09T10:20", 80), ("2026-10-15T10:30", 100), ("2026-10-16T10:30", 150),
                  ("2026-10-16T10:40", 160)]:
        save_tick(at, [["000001", "가", 1000, 1.5, v, v * 1000]])
    r = intraday("2026-10-16", "10:35")
    assert (r["at"], r["prev"], r["week"]) == ("10:30", {"date": "2026-10-15", "at": "10:30"}, {"date": "2026-10-09", "at": "10:20"}), r
    assert (r["stocks"][0]["prevRatio"], r["stocks"][0]["weekRatio"]) == (50.0, 87.5), r["stocks"]
    assert intraday()["at"] == "10:40" and intraday("2026-10-08")["prev"] is None
    if "--offline" in sys.argv:
        print("ok")
        sys.exit()
    t = time.time()
    data = query(KBClient.from_env())
    up = sorted((s for s in data["stocks"] if (s["ratio"] or 0) >= 10), key=lambda s: -s["ratio"])
    print(f"{data['total']}종목 중 {len(up)}종목이 전주 대비 10% 이상, {time.time() - t:.1f}초")
    for s in up[:20]:
        print(f"  {s['name']:<14} {s['ratio']:>8.1f}%  하루 평균 {s['prevAvg']:>12,} -> {s['avg']:>12,}")
