"""코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다.

코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
종목별 최근 일봉(IVS11560)을 받아 주(월~일)별로 묶고, 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
(주봉 chrt_clsf=W의 거래량은 일봉 합과 맞지 않아 쓰지 않습니다.)
여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.

장중 10분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (아래 '장중 기록').
러셀 2000(russell.py)도 같은 Ticks로 기록합니다.

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
from datetime import date, datetime, timedelta, tzinfo
from datetime import time as dtime
from pathlib import Path
from typing import Callable

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


# ---------------------------------------------------------------------- 장중 기록 (같은 시각 거래량 비교)
# 장중 N분마다 시장 전 종목의 누적 거래량·현재가를 SQLite에 저장합니다. 화면은 고른 시각의 누적 거래량을
# 전일·전주(7일 전 이전의 마지막 거래일) 같은 시각과 비교합니다. 같은 시각 기록이 없으면 그날 그 시각 이전의 마지막 기록을 씁니다.
# 시각은 그 시장의 현지 시간입니다 (코스닥 한국, 러셀 2000 뉴욕). Java 버전(Ticks.java)과 같은 파일·같은 테이블.

TICK_KEEP_DAYS = 14      # 전주 비교에 7일 + 긴 연휴 여유
TICK_DELAY_SECONDS = 30  # 정각 바로는 KB 시세·일봉이 아직 안 바뀌었을 수 있어 조금 뒤에 조회


class Ticks:
    """take(client, at) -> [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...] (휴장이면 빈 목록)."""

    def __init__(self, name: str, db: Path, table: str, tz: tzinfo, minutes: int, open_: dtime, close: dtime,
                 take: Callable[[KBClient, datetime], list[list]]):
        self.name, self.db, self.table, self.tz = name, db, table, tz
        self.minutes, self.open, self.close, self._take = minutes, open_, close, take

    def next_tick(self, now: datetime) -> datetime:
        """now(현지 시간) 다음의 장중 기록 시각 (평일 open~close)."""
        t = now.replace(second=0, microsecond=0, minute=now.minute - now.minute % self.minutes) + timedelta(minutes=self.minutes)
        start = lambda d: d.replace(hour=self.open.hour, minute=self.open.minute)
        if t.time() < self.open:
            t = start(t)
        elif t.time() > self.close:
            t = start(t + timedelta(days=1))
        while t.weekday() >= 5:  # 토·일이면 다음 날 장 시작
            t = start(t + timedelta(days=1))
        return t

    def _connect(self):
        con = sqlite3.connect(self.db, timeout=30)
        con.execute(f"CREATE TABLE IF NOT EXISTS {self.table} (at TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        return con  # at: 현지 시간 '2026-10-16T09:10', payload: [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...] JSON

    def save(self, at: str, rows: list[list]) -> None:
        oldest = (datetime.fromisoformat(at) - timedelta(days=TICK_KEEP_DAYS)).strftime("%Y-%m-%dT%H:%M")
        with closing(self._connect()) as con, con:
            con.execute(f"INSERT OR REPLACE INTO {self.table} (at, payload) VALUES (?, ?)", (at, json.dumps(rows, ensure_ascii=False)))
            con.execute(f"DELETE FROM {self.table} WHERE at < ?", (oldest,))

    def take(self, client: KBClient, at: datetime) -> int:
        """at 시각의 시세를 저장하고 종목 수를 돌려줍니다. 휴장일이면 저장하지 않고 0."""
        rows = self._take(client, at)
        if rows:
            self.save(at.strftime("%Y-%m-%dT%H:%M"), rows)
        return len(rows)

    def intraday(self, day: str = "", at: str = "") -> dict:
        """GET /api/<시장>-intraday?date=2026-10-16&at=10:30 (없으면 마지막 기록)
        -> {"date", "at", "dates", "slots", "prev": {"date", "at"}, "week": {...}, "nextTick", "utcOffset"(분),
            "stocks": [{code, name, price, change, vlm, value, prevVlm, prevRatio, weekVlm, weekRatio}]}"""
        with closing(self._connect()) as con:
            ats = [r[0] for r in con.execute(f"SELECT at FROM {self.table} ORDER BY at")]
            nxt = self.next_tick(datetime.now(self.tz).replace(tzinfo=None))
            out = {"dates": sorted({a[:10] for a in ats}, reverse=True), "nextTick": nxt.strftime("%Y-%m-%dT%H:%M"),
                   "utcOffset": _offset(nxt, self.tz)}
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
                row = con.execute(f"SELECT payload FROM {self.table} WHERE at = ?", (f"{ref['date']}T{ref['at']}",)).fetchone() if ref else None
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
        return {**out, "date": day, "at": at, "utcOffset": _offset(datetime.fromisoformat(f"{day}T{at}"), self.tz),
                "slots": slots, "prev": prev, "week": week, "stocks": stocks}

    def start(self, client: KBClient) -> None:
        """백그라운드 스레드로 장중 기록 시각마다 take."""
        def loop():
            while True:
                at = self.next_tick(datetime.now(self.tz).replace(tzinfo=None))
                # timestamp(): 서머타임이 바뀌는 주말을 지나도 실제 시간 차이로 기다림
                while (wait := at.replace(tzinfo=self.tz).timestamp() + TICK_DELAY_SECONDS - time.time()) > 0:
                    time.sleep(min(wait, 600))  # 길게 한 번에 자지 않음 (PC 절전 후 시계가 건너뛰어도 늦지 않게)
                try:
                    n = self.take(client, at)
                    print(f"[{self.name}] {at:%Y-%m-%d %H:%M} " + (f"{n}종목 저장" if n else "휴장 · 저장 안 함"), flush=True)
                except Exception as e:  # KB 오류여도 다음 시각에 다시
                    print(f"[{self.name}] {at:%Y-%m-%d %H:%M} 실패: {type(e).__name__}: {e}", flush=True)

        threading.Thread(target=loop, name=self.table, daemon=True).start()
        print(f"[{self.name}] 장중 {self.minutes}분마다 거래량 기록 -> {self.db.name} "
              f"(다음 현지 {self.next_tick(datetime.now(self.tz).replace(tzinfo=None)):%m-%d %H:%M})", flush=True)


def _offset(local: datetime, tz: tzinfo) -> int:
    """그 현지 시각의 UTC 오프셋(분). 화면이 한국 시간을 함께 보여 줄 때 씁니다 (뉴욕은 서머타임에 따라 -240/-300)."""
    return int(local.replace(tzinfo=tz).utcoffset().total_seconds() // 60)


def _kosdaq_rows(client: KBClient, at: datetime) -> list[list]:
    """코스닥 전 종목 시세 (IVU10210 한 번). 휴장일(오늘 일봉이 없음)이면 빈 목록."""
    listed = client.call("IVU10210", {"srt_clsf": "", "inq_cnt": "3000", "excg_clsf": "0", "mkt_clsf": "3",
                                      "thdy_bdy_clsf": ""}).get("out2", [])
    rows = [[r["is_cd"], r["is_nm"], r.get("now_prc"), float(r.get("up_dwn_r_p2") or 0), r["vlm"], int(r.get("dl_tw_amt") or 0)]
            for r in listed if len(str(r.get("is_cd") or "")) == 6 and type(r.get("vlm")) is int]  # 필드가 밀린 행은 버림
    if not rows:
        return []
    last = client.call("IVS11560", {"info_ccd": "1", "mkt_clsf": "1", "chrt_clsf": "D", "minute_tck_indx": "", "is_cd": rows[0][0],
                                    "inq_clsf": "2", "strt_dy": "", "inq_cnt": "1"}).get("out2", [])
    return rows if last and last[0].get("dt") == at.strftime("%Y%m%d") else []  # 휴장일: 시세가 마지막 거래일 값 그대로


# 장중(평일 09:00~15:40, 15:40은 15:30 종가 단일가까지 담기게) 10분마다. 하루 약 4MB
KOSDAQ = Ticks("코스닥 10분", Path(__file__).resolve().parent / "kosdaq.db", "kosdaq_tick", KST, 10, dtime(9, 0), dtime(15, 40), _kosdaq_rows)

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
    nt = KOSDAQ.next_tick
    assert nt(k(16, 9, 3)) == k(16, 9, 10) and nt(k(16, 8, 0)) == k(16, 9, 0)
    assert nt(k(15, 15, 40)) == k(16, 9, 0) and nt(k(16, 15, 35)) == k(16, 15, 40)
    assert nt(k(16, 23, 0)) == k(19, 9, 0) and nt(k(17, 12, 0)) == k(19, 9, 0)
    # 같은 시각 비교: 전일 = 직전 기록일, 전주 = 7일 전 이전의 마지막 기록일. 같은 시각이 없으면 그 이전 마지막 기록
    t = Ticks("시험", Path(tempfile.mkdtemp()) / "t.db", "t_tick", KST, 10, dtime(9, 0), dtime(15, 40), lambda c, a: [])
    for at, v in [("2026-10-08T10:30", 50), ("2026-10-09T10:20", 80), ("2026-10-15T10:30", 100), ("2026-10-16T10:30", 150),
                  ("2026-10-16T10:40", 160)]:
        t.save(at, [["000001", "가", 1000, 1.5, v, v * 1000]])
    r = t.intraday("2026-10-16", "10:35")
    assert (r["at"], r["prev"], r["week"]) == ("10:30", {"date": "2026-10-15", "at": "10:30"}, {"date": "2026-10-09", "at": "10:20"}), r
    assert (r["stocks"][0]["prevRatio"], r["stocks"][0]["weekRatio"]) == (50.0, 87.5), r["stocks"]
    assert t.intraday()["at"] == "10:40" and t.intraday("2026-10-08")["prev"] is None and r["utcOffset"] == 540
    if "--offline" in sys.argv:
        print("ok")
        sys.exit()
    t = time.time()
    data = query(KBClient.from_env())
    up = sorted((s for s in data["stocks"] if (s["ratio"] or 0) >= 10), key=lambda s: -s["ratio"])
    print(f"{data['total']}종목 중 {len(up)}종목이 전주 대비 10% 이상, {time.time() - t:.1f}초")
    for s in up[:20]:
        print(f"  {s['name']:<14} {s['ratio']:>8.1f}%  하루 평균 {s['prevAvg']:>12,} -> {s['avg']:>12,}")
