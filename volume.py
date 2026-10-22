"""코스닥 전 종목의 이번 주 거래량을 전주와 비교합니다.

코스닥 종목 목록: 거래대금 상위(IVU10210, mkt_clsf=3)를 전부(약 1,700종목) 받습니다.
종목별 최근 일봉(IVS11560, 차트와 같은 약 1년)을 받아 최근 15거래일을 주(월~일)별로 묶고, 이번 주와 전주의 '하루 평균 거래량'을 비교합니다.
휴장일이 있는 주나 아직 끝나지 않은 이번 주도 공정하게 비교하려고 합계 대신 하루 평균을 씁니다.
종가(closes)도 함께 보내 화면이 차트 페이지와 같은 기술적 시그널(signals.js)을 태그로 보여 줍니다.
(주봉 chrt_clsf=W의 거래량은 일봉 합과 맞지 않아 쓰지 않습니다.)
여러 종목을 동시에 부르면 가끔(수백 번에 한 번꼴) 필드가 밀린 응답(거래량 빈 값 등)이나 500 오류가 와서, 그런 응답은 다시 부릅니다.

장중 5분마다 코스닥 전 종목의 누적 거래량을 kosdaq.db에 저장해, 같은 시각의 거래량을 전일·전주와 비교합니다 (아래 '장중 기록').
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
from statistics import median
from pathlib import Path
from typing import Callable

import requests

import chart
import sim_modules
from daily_store import KST
from heatmap import _valid_day
from kb_client import KBApiError, KBClient

CACHE_SECONDS = 600  # 1,700번 넘게 부르는 조회라 10분 동안은 다시 부르지 않음
DAYS = 15            # 이번 주 + 전주 (휴장일이 끼어도 넉넉하게)
LAST_DAYS = 10       # 화면이 시그널 교차 날짜(최근 5거래일)를 보여 줄 수 있게 날짜를 보내는 마지막 거래일 수
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


def whole(rows: list[dict], suffix: str) -> bool:
    """일봉 행이 하나도 깨지지 않았는지 (깨진 행은 차트 계산에서 빠져 시그널이 달라질 수 있음)."""
    return len(chart._candles(rows, suffix, 1)) == len(rows)


def closes(candles: list[dict]) -> dict:
    """차트 페이지와 같은 기술적 시그널(signals.js)을 화면에서 계산하도록 일봉 종가(오래된 날부터, 약 1년)와
    마지막 LAST_DAYS 거래일 날짜(교차 날짜 표시용)를 보냅니다. 날짜를 다 보내지 않는 건 응답 크기 때문."""
    num = lambda v: int(v) if v == int(v) else v  # 12345.0 -> 12345 (응답 크기)
    return {"closes": [num(c["c"]) for c in candles], "lastDays": [c["d"] for c in candles[-LAST_DAYS:]]}


def daily(client: KBClient, code: str) -> list[dict] | None:
    """코스닥 종목 일봉 행 (IVS11560, 차트와 같은 약 1년, 최신순). 밀린 응답·500 오류면 다시 부르고, 끝내 안 되면 None."""
    rows = None
    for _ in range(3):
        try:
            got = client.call("IVS11560", {"info_ccd": "1", "mkt_clsf": "1", "chrt_clsf": "D", "minute_tck_indx": "",
                                           "is_cd": code, "inq_clsf": "2", "strt_dy": "", "inq_cnt": chart.CHART_DAYS}).get("out2", [])
        except (KBApiError, requests.RequestException):
            continue
        if sane(got[:DAYS]):  # 주간 비교에 쓰는 최근 행은 반드시 멀쩡해야 함
            rows = got
            if whole(got, "p2"):  # 오래된 행까지 멀쩡하면 끝 (아니면 한 번 더 받아 보고, 끝내 안 되면 깨진 행만 빼고 씀)
                break
    return rows


def fetch(client: KBClient) -> list[dict]:
    listed = client.call("IVU10210", {"srt_clsf": "", "inq_cnt": "3000", "excg_clsf": "0", "mkt_clsf": "3",
                                      "thdy_bdy_clsf": ""}).get("out2", [])

    def one(row: dict) -> dict | None:
        rows = daily(client, row["is_cd"])
        if rows is None:
            return None  # 거래정지 등
        w = week_change(rows[:DAYS])
        if not w:
            return None
        return {"code": row["is_cd"], "name": row["is_nm"], "price": row.get("now_prc"),
                "change": float(row.get("up_dwn_r_p2") or 0), "value": int(row.get("dl_tw_amt") or 0), **w,
                **closes(chart._candles(rows, "p2", 10))}

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


def next_slot(now: datetime, minutes: int, open_: dtime, close: dtime) -> datetime:
    """now(현지 시간) 다음의 장중 minutes분 단위 시각 (평일 open~close). 장중 기록·시그널 알림(alerts.py)이 씀."""
    t = now.replace(second=0, microsecond=0, minute=now.minute - now.minute % minutes) + timedelta(minutes=minutes)
    start = lambda d: d.replace(hour=open_.hour, minute=open_.minute)
    if t.time() < open_:
        t = start(t)
    elif t.time() > close:
        t = start(t + timedelta(days=1))
    while t.weekday() >= 5:  # 토·일이면 다음 날 장 시작
        t = start(t + timedelta(days=1))
    return t


class Ticks:
    """take(client, at) -> [[종목번호, 이름, 현재가, 등락률, 누적 거래량, 거래대금], ...] (휴장이면 빈 목록)."""

    def __init__(self, name: str, db: Path, table: str, tz: tzinfo, minutes: int, open_: dtime, close: dtime,
                 take: Callable[[KBClient, datetime], list[list]]):
        self.name, self.db, self.table, self.tz = name, db, table, tz
        self.minutes, self.open, self.close, self._take = minutes, open_, close, take

    def next_tick(self, now: datetime) -> datetime:
        """now(현지 시간) 다음의 장중 기록 시각 (평일 open~close)."""
        return next_slot(now, self.minutes, self.open, self.close)

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

    def simulate(self, min_ratio: float = 100, amount: float = 300_000, tp: float = 0, sl: float = 0,
                 until: str = "15:00", cost: float = 0.23, spac: bool = True, hold: int = 30,
                 buy: str = "volume-open-up", sell: str = "tp-sl", start: str = "", max_hold: int = 0, max_buys: int = 0,
                 allowed: dict[str, dict[str, str]] | None = None) -> dict:
        """GET /api/kosdaq-sim : 저장된 기록으로 매수 조건 모듈(buy)이 hold분 이상 이어진 종목을 amount원씩 사고,
        매도 모듈(sell)로 판 결과. 모듈은 sim_modules.py (기본: 거래량 min_ratio% 이상 + 시가 위 + 상승, 익절 tp%·손절 sl%).
        시가: KB 순위 TR(IVU10210)에 시가가 없어 그날 첫 기록(09:00, 장 시작 30초 뒤)의 현재가로 봄.
        매수: 기록 시각마다(until까지, 그날 마지막 기록 제외) 조건을 확인해, 연속으로 만족한 첫 기록부터 hold분이 지난 기록의
        현재가로 하루 한 번 (중간에 한 번이라도 깨지면 처음부터 다시). hold=0이면 처음 만족한 기록에서 바로.
        매도: 이후 기록마다 매도 모듈이 이유를 주면 그 시각 현재가, 아니면 그날 마지막 기록(종가).
        cost%: 수수료·세금을 매도 금액에서 뺌. 상한가 근처(+29.5% 이상)는 살 수 없다고 보고 뺌. spac=True면 스팩 제외.
        start: 이 시각 전에는 사지 않음 (""면 처음부터). max_hold: 산 뒤 이 분이 지나면 그 시각 현재가로 팜 (0이면 안 함).
        max_buys: 하루 최대 매수 종목 수 (0이면 제한 없음, 같은 시각에 여럿이면 거래량 증가율 큰 순).
        allowed: {날짜: {종목: 시각}}이면 그 종목만 그 시각부터 삼 (알림 종목만, alerts.first_times).
        -> {"from", "to", "total": {trades, wins, invested, pnl, ret}, "days": [{date, trades, wins, invested, pnl, ret}], "trades": [...]}"""
        if (amount <= 0 or hold < 0 or max_hold < 0 or max_buys < 0 or len(until) != 5 or until[2] != ":"
                or (start and (len(start) != 5 or start[2] != ":"))):
            raise ValueError("매수 금액·시각·유지 시간이 올바르지 않습니다")
        buy_rule, sell_rule = sim_modules.pick(sim_modules.BUY, buy), sim_modules.pick(sim_modules.SELL, sell)
        o = {"min": min_ratio, "tp": tp, "sl": sl}
        num = lambda v: float(v) if v not in (None, "") else 0.0
        mins = lambda hm: int(hm[:2]) * 60 + int(hm[3:])
        with closing(self._connect()) as con:
            ats = [r[0] for r in con.execute(f"SELECT at FROM {self.table} ORDER BY at")]
            slots: dict[str, list[str]] = {}
            for a in ats:
                slots.setdefault(a[:10], []).append(a[11:])
            cache: dict[str, dict] = {}  # 하루 앞까지만 들고 있음 (14일 전부 올리면 수백 MB)

            def load(day: str, t: str) -> dict:
                k = f"{day}T{t}"
                if k not in cache:
                    row = con.execute(f"SELECT payload FROM {self.table} WHERE at = ?", (k,)).fetchone()
                    cache[k] = {r[0]: r for r in json.loads(row[0])}
                return cache[k]

            def clean(day: str) -> None:
                """현재가·등락률로 역산한 전일 종가가 그날 중앙값과 2% 넘게 다른 기록은 잘못 받은 값으로 보고 뺌
                (KB가 가끔 자릿수가 빠진 현재가를 줌: 6220 → 709 → 6510, 등락률은 그대로)."""
                recs = [load(day, t) for t in slots[day]]
                base: dict[str, list[float]] = {}
                for rec in recs:
                    for c, r in rec.items():
                        if num(r[2]):
                            base.setdefault(c, []).append(num(r[2]) / (1 + num(r[3]) / 100))
                mid = {c: median(v) for c, v in base.items()}  # ponytail: 그날 전체 중앙값(데이터 정리용), 하루 대부분이 깨진 종목은 못 거름
                for rec in recs:
                    for c in [c for c, r in rec.items() if num(r[2]) and abs(num(r[2]) / (1 + num(r[3]) / 100) / mid[c] - 1) > 0.02]:
                        del rec[c]

            trades, days = [], list(slots)
            for p, d in zip(days, days[1:]):
                for k in [k for k in cache if k[:10] < p]:
                    del cache[k]
                clean(d)
                held, bought, since = {}, set(), {}  # since: 조건을 연속으로 만족하기 시작한 기록 시각
                opens = {c: num(r[2]) for c, r in load(d, slots[d][0]).items()}  # 첫 기록 현재가 = 시가로 봄
                for i, t in enumerate(slots[d]):
                    now = load(d, t)
                    for code, tr in list(held.items()):
                        px = num(now[code][2]) if code in now else 0
                        if px and (hit := sell_rule(tr, px, o) or (max_hold and mins(t) - mins(tr["buyAt"]) >= max_hold and "보유 시간")):
                            tr.update(sellAt=t, sellPrice=px, reason=hit)
                            del held[code]
                    before = [s for s in slots[p] if s <= t]
                    if t > until or t < start or i == len(slots[d]) - 1 or not before:
                        continue
                    prev = load(p, before[-1])
                    ready = []  # 이 시각에 살 차례가 된 종목 (하루 최대 매수 수가 있으면 거래량 증가율 큰 순)
                    for code, r in now.items():
                        px, pv = num(r[2]), num(prev[code][4]) if code in prev else 0
                        if (code in bought or not pv or not px or num(r[3]) >= 29.5 or (spac and "스팩" in r[1]) or amount < px
                                or (allowed is not None and t < allowed.get(d, {}).get(code, "99:99"))
                                or not buy_rule(s := {"price": px, "change": num(r[3]), "ratio": (num(r[4]) / pv - 1) * 100,
                                                      "open": opens.get(code, px), "value": (val := num(r[5]) if len(r) > 5 else 0),
                                                      "vwap": val / num(r[4]) if num(r[4]) and val else px}, o)):  # 거래대금 없으면 현재가 (VWAP 위로 안 봄)
                            since.pop(code, None)
                            continue
                        began = since.setdefault(code, t)
                        if mins(t) - mins(began) < hold:
                            continue
                        ready.append((s["ratio"], code, r, s, began, px))
                    for _, code, r, s, began, px in sorted(ready, key=lambda x: -x[0])[:max_buys - len(bought) if max_buys else None]:
                        bought.add(code)
                        held[code] = {"date": d, "code": code, "name": r[1], "since": began, "open": s["open"], "buyAt": t, "buyPrice": px,
                                      "qty": int(amount // px), "ratio": round(s["ratio"], 1), "change": s["change"],
                                      "prevAt": f"{p} {before[-1]}"}
                        trades.append(held[code])
                last = load(d, slots[d][-1])
                for code, tr in held.items():  # 익절·손절 없으면 그날 마지막 기록에 팜 (값이 없으면 산 값)
                    tr.update(sellAt=slots[d][-1], sellPrice=num(last[code][2]) if code in last else tr["buyPrice"], reason="마지막 기록")
        for tr in trades:
            tr["invested"] = round(tr["qty"] * tr["buyPrice"])
            tr["pnl"] = round(tr["qty"] * tr["sellPrice"] * (1 - cost / 100) - tr["invested"])
            tr["ret"] = round(tr["pnl"] / tr["invested"] * 100, 2)

        def summary(ts: list[dict]) -> dict:
            inv, pnl = sum(t["invested"] for t in ts), sum(t["pnl"] for t in ts)
            return {"trades": len(ts), "wins": sum(t["pnl"] > 0 for t in ts), "invested": inv, "pnl": pnl,
                    "ret": round(pnl / inv * 100, 2) if inv else None}
        return {"from": days[1] if len(days) > 1 else None, "to": days[-1] if len(days) > 1 else None, "total": summary(trades),
                "days": [{"date": d, **summary([t for t in trades if t["date"] == d])} for d in days[1:]],
                "trades": trades}

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


# 장중(평일 09:00~15:40, 15:40은 15:30 종가 단일가까지 담기게) 5분마다. 하루 약 8MB
KOSDAQ = Ticks("코스닥 5분", Path(__file__).resolve().parent / "kosdaq.db", "kosdaq_tick", KST, 5, dtime(9, 0), dtime(15, 40), _kosdaq_rows)

if __name__ == "__main__":
    import tempfile

    # 자체 점검: 휴장일로 3일뿐인 전주와 5일인 이번 주를 하루 평균으로 비교
    rows = [{"dt": d, "vlm": v} for d, v in [("20261016", 200), ("20261015", 100), ("20261014", 100), ("20261013", 100),
                                             ("20261012", 100), ("20261008", 100), ("20261007", 100), ("20261006", 100),
                                             ("20261002", 999)]]
    assert week_change(rows) == {"avg": 120, "prevAvg": 100, "days": 5, "prevDays": 3, "ratio": 20.0}
    assert week_change(rows[:5]) is None
    assert sane(rows) and not sane([{"dt": "20261016", "vlm": ""}]) and not sane(rows[1:2] + rows[:1]) and not sane([])
    # 5분 시각: 장중만, 15:40 다음은 다음 평일 09:00, 금요일 다음은 월요일
    k = lambda *a: datetime(2026, 10, *a)
    nt = KOSDAQ.next_tick
    assert nt(k(16, 9, 3)) == k(16, 9, 5) and nt(k(16, 9, 5)) == k(16, 9, 10) and nt(k(16, 8, 0)) == k(16, 9, 0)
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
    # 시뮬레이션 (거래량 +40% 이상·시가(첫 기록)보다 높음·전일 대비 상승이 30분 이어지면 매수, 익절 5%)
    # A: 09:30부터 이어져 10:00에 사서 10:30 익절. B: 10:00에 시가 아래로 깨져 다시 세다 마지막 기록이라 안 삼.
    # C: 09:30은 하락이라 10:00부터 세어 10:30에 사서 마지막 기록에 팜. D스팩은 스팩 제외. E는 거래량이 안 늘어 안 삼
    s = Ticks("시험", Path(tempfile.mkdtemp()) / "s.db", "s_tick", KST, 5, dtime(9, 0), dtime(15, 40), lambda c, a: [])
    row = lambda c, px, ch, v: [c, c, px, ch, v, 0]
    days = {"09:00": [("A", 1000, 0.5, 100), ("B", 1000, 0.5, 100), ("C", 2000, 0.5, 100), ("D스팩", 1000, 0.5, 100), ("E", 1000, 0.5, 100)],
            "09:30": [("A", 1010, 1.0, 200), ("B", 1010, 1.0, 200), ("C", 2010, -1.0, 200), ("D스팩", 1010, 1.0, 200), ("E", 1010, 1.0, 100)],
            "10:00": [("A", 1020, 2.0, 300), ("B", 990, 1.0, 300), ("C", 2010, 1.0, 300), ("D스팩", 1020, 2.0, 300), ("E", 1020, 2.0, 100)],
            "10:30": [("A", 1080, 8.0, 400), ("B", 1010, 1.0, 400), ("C", 2020, 1.0, 400), ("D스팩", 1020, 2.0, 400), ("E", 1030, 3.0, 100)],
            "15:40": [("A", 900, -10.0, 500), ("B", 1010, 1.0, 500), ("C", 1900, -4.5, 500), ("D스팩", 1000, 1.0, 500), ("E", 1030, 3.0, 100)]}
    for hm, rs in days.items():
        s.save(f"2026-10-15T{hm}", [row(c, 1, 0, 100) for c, *_ in rs])
        s.save(f"2026-10-16T{hm}", [row(*x) for x in rs])
    r = s.simulate(min_ratio=40, tp=5, cost=0)
    assert [(x["code"], x["since"], x["buyAt"], x["qty"], x["sellAt"], x["sellPrice"], x["reason"], x["pnl"]) for x in r["trades"]] == [
        ("A", "09:30", "10:00", 294, "10:30", 1080, "익절", 17640), ("C", "10:00", "10:30", 148, "15:40", 1900, "마지막 기록", -17760)], r["trades"]
    assert r["total"] == {"trades": 2, "wins": 1, "invested": 598840, "pnl": -120, "ret": -0.02} and r["from"] == "2026-10-16", r
    assert [x["code"] for x in s.simulate(min_ratio=40, tp=5, cost=0, spac=False)["trades"]] == ["A", "D스팩", "C"]
    assert [(x["code"], x["buyAt"]) for x in s.simulate(min_ratio=40, hold=0)["trades"]][:2] == [("A", "09:30"), ("B", "09:30")]
    # 모듈 교체: 시가 무시면 B도 09:30부터 이어져 10:00에 삼. 추적 손절 5%면 A는 10:30 고점 1080에서 15:40 900으로 내려 팜
    assert [x["code"] for x in s.simulate(min_ratio=40, tp=5, cost=0, buy="volume-up")["trades"]] == ["A", "B", "C"]
    assert [(x["code"], x["sellAt"], x["reason"]) for x in s.simulate(min_ratio=40, sl=5, cost=0, sell="trailing")["trades"]] == [
        ("A", "15:40", "고점 대비 손절"), ("C", "15:40", "고점 대비 손절")]
    assert sim_modules.catalog()["buy"][0]["id"] == "volume-open-up"
    # 매수 시작 10:30이면 그 전 기록은 안 봄 (유지 0이라 10:30에 A·B·C). 보유 30분이면 A는 10:30, C는 다음 기록(15:40)에 '보유 시간'
    assert [(x["code"], x["buyAt"]) for x in s.simulate(min_ratio=40, hold=0, start="10:30")["trades"]] == [("A", "10:30"), ("B", "10:30"), ("C", "10:30")]
    assert [(x["code"], x["sellAt"], x["reason"]) for x in s.simulate(min_ratio=40, cost=0, max_hold=30)["trades"]] == [
        ("A", "10:30", "보유 시간"), ("C", "15:40", "보유 시간")]
    # 하루 최대 1종목: 09:30에 A·B가 같이 되면 거래량 증가율이 같아 먼저 나온 A만. 알림 종목만: C(10:00 알림)만 10:00부터 세어 10:30에
    assert [x["code"] for x in s.simulate(min_ratio=40, hold=0, max_buys=1)["trades"]] == ["A"]
    assert [(x["code"], x["since"], x["buyAt"]) for x in s.simulate(min_ratio=40, allowed={"2026-10-16": {"C": "10:00"}})["trades"]] == [("C", "10:00", "10:30")]
    # 자릿수가 빠진 현재가(09:30 100원, 등락률은 그대로)는 빼고 10:00에 1010원으로 삼 (안 빼면 100원에 사서 큰 수익으로 보임)
    g = Ticks("시험", Path(tempfile.mkdtemp()) / "g.db", "g_tick", KST, 5, dtime(9, 0), dtime(15, 40), lambda c, a: [])
    for hm, px, v in [("09:00", 1000, 100), ("09:30", 100, 200), ("10:00", 1010, 300), ("15:40", 1010, 400)]:
        g.save(f"2026-10-15T{hm}", [row("G", 1, 0, 100)])
        g.save(f"2026-10-16T{hm}", [row("G", px, 1.0 if px != 1000 else 0.0, v)])
    assert [(x["buyAt"], x["buyPrice"]) for x in g.simulate(min_ratio=40, hold=0, buy="volume-up")["trades"]] == [("10:00", 1010)]
    # 전일 = 직전 기록일: 월요일(10-19)은 기록이 없는 일·토요일을 건너뛰고 금요일(10-16) 같은 시각과 비교
    w = Ticks("시험", Path(tempfile.mkdtemp()) / "w.db", "w_tick", KST, 5, dtime(9, 0), dtime(15, 40), lambda c, a: [])
    for hm, px, ch, v in [("09:00", 1000, 0.0, 100), ("09:30", 1010, 1.0, 200), ("15:40", 1020, 2.0, 300)]:
        w.save(f"2026-10-16T{hm}", [row("A", 1000, 0.0, 100)])
        w.save(f"2026-10-19T{hm}", [row("A", px, ch, v)])
    assert [(x["date"], x["buyAt"], x["prevAt"]) for x in w.simulate(min_ratio=40, hold=0)["trades"]] == [("2026-10-19", "09:30", "2026-10-16 09:30")]
    assert w.intraday("2026-10-19", "09:30")["prev"] == {"date": "2026-10-16", "at": "09:30"}
    # VWAP = 누적 거래대금 ÷ 누적 거래량: 09:30은 1010원 < VWAP 1050원이라 안 사고 10:00(1100원 > 1066.7원)에 삼. 거래대금이 없는 Z는 안 삼
    v = Ticks("시험", Path(tempfile.mkdtemp()) / "v.db", "v_tick", KST, 5, dtime(9, 0), dtime(15, 40), lambda c, a: [])
    for hm, px, vol, val in [("09:00", 1000, 100, 100_000), ("09:30", 1010, 200, 210_000), ("10:00", 1100, 300, 320_000), ("15:40", 1100, 400, 430_000)]:
        v.save(f"2026-10-15T{hm}", [row("V", 1000, 0.0, 100), row("Z", 1000, 0.0, 100)])
        v.save(f"2026-10-16T{hm}", [["V", "V", px, px / 10 - 100, vol, val], row("Z", px, px / 10 - 100, vol)])  # 전일 종가 1000원
    assert [(x["code"], x["buyAt"]) for x in v.simulate(min_ratio=40, hold=0, buy="vwap-up")["trades"]] == [("V", "10:00")]
    if "--offline" in sys.argv:
        print("ok")
        sys.exit()
    t = time.time()
    data = query(KBClient.from_env())
    up = sorted((s for s in data["stocks"] if (s["ratio"] or 0) >= 10), key=lambda s: -s["ratio"])
    print(f"{data['total']}종목 중 {len(up)}종목이 전주 대비 10% 이상, {time.time() - t:.1f}초")
    for s in up[:20]:
        print(f"  {s['name']:<14} {s['ratio']:>8.1f}%  하루 평균 {s['prevAvg']:>12,} -> {s['avg']:>12,}")
