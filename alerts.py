"""시그널 알림: 코스닥·러셀 2000의 주간 평균 거래량 증가율 top 30을 장중 5분마다 확인해, 새로 생긴 상승(매수 쪽) 시그널을 알립니다.

순위: 거래량 페이지 '주간 평균'과 같은 증가율(volume.query, russell.query). 전 종목 조회라 30분마다만 다시 뽑습니다 (코스닥은 스팩 제외).
확인: top 30 종목마다 차트와 같은 약 1년 일봉을 새로 받아 차트와 같은 규칙(signals.py = signals.js)으로 시그널을 계산합니다.
새 시그널: 직전 확인 때 없던 매수 쪽 시그널. 처음 보는 종목(서버 시작, top 30 새로 진입)은 오늘 일어난 교차만 알립니다.
         같은 날 같은 종목·같은 시그널은 한 번만 알립니다 (alerts.db의 UNIQUE, 서버를 다시 켜도, Python·Java 서버가 함께 돌아도).
장중: 코스닥 평일 09:05~15:35(한국), 러셀 2000 평일 09:50~16:20(뉴욕, KB 미국 시세 15분 지연). 5분마다.

알림 받는 곳
- 휴대폰: .env(또는 환경변수)에 NTFY_TOPIC이 있으면 ntfy(https://ntfy.sh, NTFY_SERVER로 바꿀 수 있음)로 보냅니다.
  휴대폰에 ntfy 앱을 깔고 같은 주제를 구독하면 됩니다. 주제 이름을 아는 사람은 누구나 볼 수 있어 길고 무작위로 정하세요.
  APP_URL(예: http://192.168.0.10:8000)이 있으면 알림을 눌렀을 때 그 종목 차트가 열립니다.
- 브라우저: 거래량 페이지가 GET /api/alerts를 1분마다 읽어 브라우저 알림으로 띄우고 '시그널 알림' 목록에 보여 줍니다.

Java 버전(Alerts.java)과 같은 alerts.db·같은 테이블을 씁니다.

    python alerts.py --offline   # 새 시그널 고르기·중복 막기 자체 점검
    python alerts.py --ntfy      # .env의 NTFY_TOPIC으로 시험 알림 한 건 보내기
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from dataclasses import dataclass, field
from datetime import datetime, tzinfo
from datetime import time as dtime
from pathlib import Path
from typing import Callable

import requests

import chart
import russell
import volume
from daily_store import KST
from kb_client import KBClient
from signals import signals_of

HERE = Path(__file__).resolve().parent
DB = HERE / "alerts.db"
TOP = 30
EVERY_MINUTES = 5
RANK_MINUTES = 30  # 순위(전 종목 조회)는 30분마다만
KEEP = 200         # /api/alerts가 돌려주는 최근 알림 수
SCHEMA = """CREATE TABLE IF NOT EXISTS signal_alert (
  id     INTEGER PRIMARY KEY AUTOINCREMENT,
  market TEXT NOT NULL,   -- kosdaq | russell
  day    TEXT NOT NULL,   -- 그 시장의 현지 날짜 '20261016'
  at     TEXT NOT NULL,   -- 알린 시각 (한국 시간) '2026-10-16 10:35'
  code   TEXT NOT NULL,
  name   TEXT NOT NULL,
  text   TEXT NOT NULL,   -- '5·20일선 골든크로스'
  d      TEXT NOT NULL,   -- 교차가 일어난 날 (교차가 아니면 '')
  ratio  REAL,            -- 주간 평균 거래량 증가율 (%)
  price  REAL,
  change REAL,
  sig    TEXT NOT NULL,   -- 같은 시그널을 알아보는 열쇠 (숫자를 뺀 문구 + 날짜)
  chart  TEXT NOT NULL,   -- 차트 주소 쿼리 'market=kr&code=000250'
  UNIQUE (market, day, code, sig)
)"""


@dataclass
class Market:
    key: str
    name: str
    tz: tzinfo
    open: dtime
    close: dtime
    rank: Callable[[KBClient], dict]                        # 주간 평균 응답 (stocks에 ratio)
    daily: Callable[[KBClient, dict], list[dict] | None]    # 순위의 종목 -> 일봉 [{"d", "c"}] (오래된 날부터)
    chart: Callable[[dict], str]                            # 차트 주소 쿼리
    skip: Callable[[dict], bool] = lambda s: False           # 순위에서 뺄 종목
    top: list[dict] = field(default_factory=list)
    ranked_at: float = 0.0
    prev: dict[str, set[str]] = field(default_factory=dict)  # 종목 -> 직전 확인 때의 매수 쪽 시그널 열쇠
    status: str = "대기"
    checked_at: str = ""


def _kosdaq_daily(client: KBClient, s: dict) -> list[dict] | None:
    rows = volume.daily(client, s["code"])
    return chart._candles(rows, "p2", 10) if rows else None


def _russell_daily(client: KBClient, s: dict) -> list[dict] | None:
    rows = russell.daily(client, s["code"], s.get("exchange", ""))
    return chart._candles(rows, "p4", 1) if rows else None


MARKETS = {
    "kosdaq": Market("kosdaq", "코스닥", KST, dtime(9, 5), dtime(15, 35), volume.query, _kosdaq_daily,
                     lambda s: f"market=kr&code={s['code']}", skip=lambda s: "스팩" in s["name"]),
    "russell": Market("russell", "러셀 2000", russell.NEW_YORK, dtime(9, 50), dtime(16, 20), russell.query, _russell_daily,
                      lambda s: f"market=us&code={s['code']}&exchange={s.get('exchange', '')}"),
}


def sig_key(g: dict) -> str:
    """같은 시그널을 알아보는 열쇠. RSI 값처럼 바뀌는 숫자는 빼고, 교차는 일어난 날까지."""
    return re.sub(r" \d+$", "", g["text"]) + "|" + g.get("d", "")


def new_signals(buys: list[dict], prev: set[str] | None, today: str) -> list[dict]:
    """직전 확인(prev) 때 없던 매수 쪽 시그널. 처음 보는 종목(prev None)은 오늘 일어난 교차만."""
    if prev is None:
        return [g for g in buys if g.get("d") == today]
    return [g for g in buys if sig_key(g) not in prev]


def _connect():
    con = sqlite3.connect(DB, timeout=30)
    con.execute(SCHEMA)
    return con


def record(m: Market, day: str, s: dict, g: dict) -> dict | None:
    """알림을 저장하고 돌려줍니다. 같은 날 같은 종목·시그널을 이미 알렸으면 None (서버 재시작·두 서버 동시 실행에도 한 번만)."""
    row = {"market": m.key, "day": day, "at": datetime.now(KST).strftime("%Y-%m-%d %H:%M"), "code": s["code"], "name": s["name"],
           "text": g["text"], "d": g.get("d", ""), "ratio": s.get("ratio"), "price": s.get("price"), "change": s.get("change"),
           "sig": sig_key(g), "chart": m.chart(s)}
    with closing(_connect()) as con, con:
        cur = con.execute(f"INSERT OR IGNORE INTO signal_alert ({', '.join(row)}) VALUES ({', '.join('?' * len(row))})", tuple(row.values()))
        if cur.rowcount != 1:
            return None
        return {"id": cur.lastrowid, **row}


def notify(m: Market, a: dict) -> None:
    """ntfy로 보냅니다 (NTFY_TOPIC이 없으면 아무것도 안 함). 실패해도 알림 기록·브라우저 알림은 그대로."""
    topic = os.environ.get("NTFY_TOPIC", "").strip()
    if not topic:
        return
    when = f" {int(a['d'][4:6])}/{int(a['d'][6:])}" if a["d"] else ""
    body = {"topic": topic, "title": f"[{m.name}] {a['name']} ▲ {a['text']}",
            "message": f"{a['name']} ({a['code']}) {a['text']}{when}\n주간 거래량 {a['ratio']:+.1f}% · 현재가 {a['price']} ({a['change']:+.2f}%)",
            "tags": ["chart_with_upwards_trend"], "priority": 4}
    app = os.environ.get("APP_URL", "").strip().rstrip("/")
    if app:
        body["click"] = f"{app}/chart?{a['chart']}"
    try:
        requests.post(os.environ.get("NTFY_SERVER", "https://ntfy.sh").rstrip("/"), json=body, timeout=10).raise_for_status()
    except requests.RequestException as e:
        print(f"[시그널 알림] ntfy 보내기 실패: {type(e).__name__}: {e}", flush=True)


def check(m: Market, client: KBClient) -> list[dict]:
    """한 번 확인: (필요하면 순위를 다시 뽑고) top 30의 새 매수 쪽 시그널을 저장·알림. 새 알림 목록을 돌려줍니다."""
    today = datetime.now(m.tz).strftime("%Y%m%d")
    if m.top:  # 휴장일이면 전 종목 순위 조회 없이 끝 (top 1위 일봉 하나로 확인)
        first = m.daily(client, m.top[0])
        if first and first[-1]["d"] != today:
            m.checked_at, m.status = datetime.now(KST).strftime("%Y-%m-%d %H:%M"), "휴장 (오늘 일봉 없음)"
            return []
    if not m.top or time.time() - m.ranked_at > RANK_MINUTES * 60:
        stocks = [s for s in m.rank(client)["stocks"] if s.get("ratio") is not None and not m.skip(s)]
        m.top = sorted(stocks, key=lambda s: -s["ratio"])[:TOP]
        m.ranked_at = time.time()
    with ThreadPoolExecutor(8) as pool:
        charts = list(pool.map(lambda s: m.daily(client, s), m.top))
    m.checked_at = datetime.now(KST).strftime("%Y-%m-%d %H:%M")
    if not any(cs and cs[-1]["d"] == today for cs in charts):
        m.status = "휴장 (오늘 일봉 없음)"
        return []
    out = []
    for s, cs in zip(m.top, charts):
        if not cs:
            continue
        buys = [g for g in signals_of(cs) if g["dir"] > 0]
        for g in new_signals(buys, m.prev.get(s["code"]), today):
            a = record(m, today, s, g)
            if a:
                out.append(a)
                notify(m, a)
        m.prev[s["code"]] = {sig_key(g) for g in buys}
    m.status = f"top {len(m.top)} 확인"
    return out


def recent(since: int = 0) -> dict:
    """GET /api/alerts[?since=id] -> {"alerts": [최근 알림, 최신 먼저], "ntfy": NTFY_TOPIC 있음, "markets": {시장: 감시 상태}}"""
    with closing(_connect()) as con:
        con.row_factory = sqlite3.Row
        rows = [dict(r) for r in con.execute("SELECT * FROM signal_alert WHERE id > ? ORDER BY id DESC LIMIT ?", (since, KEEP))]
    markets = {k: {"name": m.name, "status": m.status, "checkedAt": m.checked_at,
                   "next": volume.next_slot(datetime.now(m.tz).replace(tzinfo=None), EVERY_MINUTES, m.open, m.close).strftime("%Y-%m-%dT%H:%M"),
                   "top": [{"code": s["code"], "name": s["name"], "ratio": s["ratio"]} for s in m.top]} for k, m in MARKETS.items()}
    return {"alerts": rows, "ntfy": bool(os.environ.get("NTFY_TOPIC", "").strip()), "every": EVERY_MINUTES, "top": TOP, "markets": markets}


def first_times(market: str) -> dict[str, dict[str, str]]:
    """시뮬레이션 '알림 종목만'용: {그 시장 현지 날짜 'YYYY-MM-DD': {종목: 첫 알림 현지 시각 'HH:MM'}}. at은 한국 시간이라 시장 시간대로 바꿈."""
    tz, out = MARKETS[market].tz, {}
    with closing(_connect()) as con:
        for at, code in con.execute("SELECT at, code FROM signal_alert WHERE market = ? ORDER BY at", (market,)):
            local = datetime.strptime(at, "%Y-%m-%d %H:%M").replace(tzinfo=KST).astimezone(tz)
            out.setdefault(local.strftime("%Y-%m-%d"), {}).setdefault(code, local.strftime("%H:%M"))
    return out


def start(client: KBClient) -> None:
    """시장마다 백그라운드 스레드로 장중 5분마다 check."""
    def loop(m: Market):
        while True:
            at = volume.next_slot(datetime.now(m.tz).replace(tzinfo=None), EVERY_MINUTES, m.open, m.close)
            while (wait := at.replace(tzinfo=m.tz).timestamp() - time.time()) > 0:
                time.sleep(min(wait, 600))  # 길게 한 번에 자지 않음 (PC 절전 후 시계가 건너뛰어도 늦지 않게)
            try:
                found = check(m, client)
                for a in found:
                    print(f"[시그널 알림] {m.name} {a['name']}({a['code']}) {a['text']}", flush=True)
            except Exception as e:  # 목록 파일 없음·KB 오류여도 다음 시각에 다시
                status = f"실패: {type(e).__name__}: {e}"
                if status != m.status:  # 같은 실패를 5분마다 찍지 않게
                    print(f"[시그널 알림] {m.name} {status}", flush=True)
                m.status = status

    for m in MARKETS.values():
        threading.Thread(target=loop, args=(m,), name=f"alerts-{m.key}", daemon=True).start()
    print(f"[시그널 알림] 코스닥·러셀 2000 증가율 top {TOP}을 장중 {EVERY_MINUTES}분마다 확인 "
          f"(ntfy {'켜짐' if os.environ.get('NTFY_TOPIC', '').strip() else '꺼짐: .env에 NTFY_TOPIC'})", flush=True)


if __name__ == "__main__":
    import tempfile

    import kb_client  # noqa: F401  .env를 환경변수로 읽어 둠

    if "--ntfy" in sys.argv:  # 시험 알림
        if not os.environ.get("NTFY_TOPIC", "").strip():
            sys.exit("NTFY_TOPIC이 없습니다. .env에 NTFY_TOPIC=길고-무작위한-주제를 넣고 휴대폰 ntfy 앱에서 같은 주제를 구독하세요.")
        notify(MARKETS["kosdaq"], {"name": "시험 알림", "code": "000000", "text": "5·20일선 골든크로스", "d": "",
                                   "ratio": 12.3, "price": 1000, "change": 1.5, "chart": "market=kr&code=005930"})
        print(f"보냈습니다: {os.environ.get('NTFY_SERVER', 'https://ntfy.sh')}/{os.environ['NTFY_TOPIC']}")
        sys.exit()
    # 자체 점검: 새 시그널 고르기와 같은 날 중복 막기
    gc = {"dir": 1, "text": "5·20일선 골든크로스", "d": "20261016"}
    old = {"dir": 1, "text": "MACD 시그널 상향 돌파", "d": "20261013"}
    rsi1, rsi2 = {"dir": 1, "text": "RSI 과매도 28"}, {"dir": 1, "text": "RSI 과매도 25"}
    assert sig_key(rsi1) == sig_key(rsi2) == "RSI 과매도|"
    assert new_signals([gc, old, rsi1], None, "20261016") == [gc]                      # 처음 보는 종목: 오늘 교차만
    assert new_signals([gc, old, rsi2], {sig_key(old), sig_key(rsi1)}, "20261016") == [gc]  # RSI 값만 바뀐 건 새것 아님
    assert new_signals([old], {sig_key(old)}, "20261016") == []
    DB = Path(tempfile.mkdtemp()) / "alerts.db"
    s = {"code": "000001", "name": "가", "ratio": 50.0, "price": 1000, "change": 1.5}
    a = record(MARKETS["kosdaq"], "20261016", s, gc)
    assert a and a["chart"] == "market=kr&code=000001" and record(MARKETS["kosdaq"], "20261016", s, gc) is None
    assert record(MARKETS["kosdaq"], "20261017", s, gc)                                 # 다음 날은 다시
    r = recent()
    assert [x["day"] for x in r["alerts"]] == ["20261017", "20261016"] and recent(a["id"])["alerts"][0]["day"] == "20261017"
    with closing(_connect()) as con, con:  # 러셀 알림 시각(한국 2026-10-20 23:20)은 뉴욕 10:20 (서머타임)
        con.execute("INSERT INTO signal_alert (market, day, at, code, name, text, d, sig, chart) VALUES "
                    "('russell', '20261020', '2026-10-20 23:20', 'ABC', 'abc', 't', '', 's', 'c')")
    assert first_times("russell") == {"2026-10-20": {"ABC": "10:20"}}
    assert [list(v) for v in first_times("kosdaq").values()] == [["000001"]]  # 같은 종목은 첫 알림 시각만
    print("ok")
