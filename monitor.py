"""시장 모니터(/monitor) 시세 저장소.

화면이 열릴 때마다 지수·추가 종목의 일봉을 KB에서 하나씩 받으면 수 초 걸려, 서버가 5분마다 미리 받아
SQLite(kb.db)의 monitor_quotes 테이블에 저장해 두고 화면은 저장된 값을 바로 받습니다 (GET /api/monitor-quotes).
종목 목록이 바뀌면(POST /api/monitor) 다음 5분을 기다리지 않고 곧바로 다시 받습니다.
Java 버전(java-app/.../Monitor.java)과 같은 파일·같은 테이블을 씁니다.
"""

from __future__ import annotations

import json
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
from datetime import datetime

import chart
from daily_store import DB, KST
from kb_client import KBClient

EVERY_SECONDS = 300
KEEP_CANDLES = 40  # 월간 대비(1달 전 ≈ 22거래일)에 넉넉하게

# 지수 히트맵 칸. KB OpenAPI에 지수 시세가 없어 추종 ETF로 봄 (Trader.java INDEX와 같은 방식)
# ponytail: 지수 대신 추종 ETF. 정확한 지수 값이 필요하면 KB 지수 시세 TR로 바꿀 것
INDICES = [
    {"name": "코스피", "market": "kr", "code": "069500", "exchange": "", "etf": "KODEX 200"},
    {"name": "코스닥", "market": "kr", "code": "229200", "exchange": "", "etf": "KODEX 코스닥150"},
    {"name": "S&P 500", "market": "us", "code": "SPY", "exchange": "AMX", "etf": "SPY"},
    {"name": "나스닥", "market": "us", "code": "QQQ", "exchange": "NAS", "etf": "QQQ (나스닥 100)"},
    {"name": "다우", "market": "us", "code": "DIA", "exchange": "AMX", "etf": "DIA"},
    {"name": "러셀 2000", "market": "us", "code": "IWM", "exchange": "AMX", "etf": "IWM"},
]

SCHEMA = """CREATE TABLE IF NOT EXISTS monitor_quotes (
  key        TEXT PRIMARY KEY,  -- 'kr:069500', 'us:SPY'
  fetched_at TEXT NOT NULL,
  payload    TEXT NOT NULL      -- {"candles": [{"d", "c"}] 최근 KEEP_CANDLES개} 또는 {"error": "..."}
)"""

_wake = threading.Event()


def listing() -> dict:
    """GET /api/monitor : 지수 칸과 사용자가 추가한 종목."""
    return {"indices": INDICES, **chart.monitor_items()}


def _one(client: KBClient, ix: dict) -> dict:
    try:
        cs = chart.chart(client, ix["market"], ix["code"], ix["exchange"], save=False)["candles"]
        return {"candles": [{"d": c["d"], "c": c["c"]} for c in cs[-KEEP_CANDLES:]]}
    except Exception as e:  # 한 종목이 실패해도 나머지는 저장
        return {"error": str(e) or type(e).__name__}


def refresh(client: KBClient) -> dict:
    """지수·추가 종목 일봉을 KB에서 받아 저장하고, 저장된 전체를 돌려줍니다. 목록에서 빠진 종목은 지웁니다."""
    items = INDICES + chart.monitor_items()["items"]
    with ThreadPoolExecutor(8) as pool:
        got = list(pool.map(lambda ix: _one(client, ix), items))
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S")
    keys = [f"{ix['market']}:{ix['code']}" for ix in items]
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:
        con.execute(SCHEMA)
        con.executemany("INSERT INTO monitor_quotes (key, fetched_at, payload) VALUES (?, ?, ?) "
                        "ON CONFLICT (key) DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload",
                        [(k, now, json.dumps(p, ensure_ascii=False)) for k, p in zip(keys, got)])
        con.execute(f"DELETE FROM monitor_quotes WHERE key NOT IN ({','.join('?' * len(keys))})", keys)
    return quotes()


def quotes() -> dict:
    """GET /api/monitor-quotes : 저장된 시세 (KB 조회 없음). {"quotes": {key: {"fetchedAt", "candles" 또는 "error"}}}"""
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:
        con.execute(SCHEMA)
        rows = con.execute("SELECT key, fetched_at, payload FROM monitor_quotes").fetchall()
    return {"quotes": {k: {"fetchedAt": at, **json.loads(p)} for k, at, p in rows}}


def changed() -> None:
    """종목 목록이 바뀜: 저장 스레드를 깨워 곧바로 다시 받게 함."""
    _wake.set()


def start(client: KBClient) -> None:
    """백그라운드 스레드로 바로 한 번, 이후 5분마다(목록이 바뀌면 곧바로) refresh."""
    def loop():
        while True:
            _wake.clear()
            try:
                refresh(client)
            except Exception as e:  # DB·네트워크 오류여도 다음에 다시
                print(f"[모니터] 시세 저장 실패: {type(e).__name__}: {e}", flush=True)
            _wake.wait(EVERY_SECONDS)

    # ponytail: 장 시간과 상관없이 5분마다 (지수·종목 수십 개라 부담이 작음). 줄이려면 국내·미국 장중에만
    threading.Thread(target=loop, name="monitor", daemon=True).start()


if __name__ == "__main__":
    # 자체 점검 (네트워크 불필요): 가짜 차트로 저장·실패 기록·빠진 종목 삭제 확인
    import tempfile
    from pathlib import Path

    DB = Path(tempfile.mkdtemp()) / "test.db"
    chart.MONITOR_FILE = DB.parent / "monitor.json"
    chart.save_monitor_items({"items": [{"market": "us", "code": "NVDA", "exchange": "NAS"}]})

    def fake_chart(client, market, code, exchange, save=True):
        assert save is False and (code != "SPY" or exchange == "AMX")
        if code == "QQQ":
            raise ValueError("조회 실패")
        return {"candles": [{"d": f"2026{i:04d}", "c": i, "o": 0} for i in range(50)]}
    chart.chart = fake_chart
    q = refresh(None)["quotes"]
    assert set(q) == {f"{ix['market']}:{ix['code']}" for ix in INDICES} | {"us:NVDA"}, q.keys()
    assert q["us:QQQ"]["error"] == "조회 실패" and "candles" not in q["us:QQQ"]
    assert len(q["us:SPY"]["candles"]) == KEEP_CANDLES and q["us:SPY"]["candles"][-1] == {"d": "20260049", "c": 49}
    chart.save_monitor_items({"items": []})
    assert "us:NVDA" not in refresh(None)["quotes"]  # 목록에서 뺀 종목은 지움
    assert listing()["indices"] == INDICES and listing()["items"] == []
    print("ok")
