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

# 금리·원유 칸 (KB TR 값 그대로). 토스 API는 주식·ETF 시세만 있어 원유 선물·국채 금리는 KB에서 받음.
# 국고채 30년·미국채 금리는 KB·토스 모두 TR이 없어 빠짐 (ETF 가격으로 대신하려면 INDICES처럼 칸을 추가)
MACRO = [
    # (key, 이름, TR, 값 필드, 전일 대비 필드, 단위)
    ("macro:wti", "WTI 원유 (근월물)", "IVSA0070", "now_prc", "up_dwn_r_p2", "$"),
    ("macro:kr5y", "국고채 5년", "IVA10370", "ntnbnd_yr5_thng_yld_p5", "ntnbnd_yr5_thng_yld_cmpr_p5", "%"),
    ("macro:kr10y", "국고채 10년", "IVA10370", "ntnbnd_yr10_thng_yld_p5", "ntnbnd_yr10_thng_yld_cmpr_p5", "%"),
]

SCHEMA = """CREATE TABLE IF NOT EXISTS monitor_quotes (
  key        TEXT PRIMARY KEY,  -- 'kr:069500', 'us:SPY', 'macro:wti'
  fetched_at TEXT NOT NULL,
  payload    TEXT NOT NULL      -- {"candles": [{"d", "c"}] 최근 KEEP_CANDLES개}, 금리·원유는 {"name", "value", "change", "unit", "date"}, 실패는 {"error": "..."}
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


def _num(v) -> float | None:
    try:
        return float(str(v).strip())
    except ValueError:
        return None


def macro(client: KBClient) -> dict[str, dict]:
    """금리·원유: TR마다 한 번씩 불러 MACRO 칸으로. 원유 change는 전일 대비 %, 금리 change는 전일 대비 %p.
    한 TR이 실패해도 다른 칸은 저장."""
    got: dict[str, dict] = {}
    for tr in dict.fromkeys(m[2] for m in MACRO):
        try:
            got[tr] = {"ok": client.call(tr, {})}
        except Exception as e:
            got[tr] = {"error": str(e) or type(e).__name__}
    out = {}
    for key, name, tr, val, chg, unit in MACRO:
        if "error" in got[tr]:
            out[key] = {"name": name, "error": got[tr]["error"]}
            continue
        body = got[tr]["ok"]
        if tr == "IVSA0070":  # 해외선물·지수 목록에서 WTI 근월물 (NYM@CL)
            row = next((r for r in body.get("out4", []) if str(r.get("symbl_cd", "")).strip() == "NYM@CL"), None)
            date = str(row.get("dt_tm", "")).strip()[:8] if row else ""
        else:
            row, date = body, str(body.get("dt2") or body.get("dt") or "").strip()
        value = _num(row.get(val)) if row else None
        out[key] = ({"name": name, "value": value, "change": _num(row.get(chg)), "unit": unit, "date": date} if value is not None
                    else {"name": name, "error": f"{tr} 응답에 {name} 값이 없습니다"})
    return out


def refresh(client: KBClient) -> dict:
    """지수·추가 종목 일봉을 KB에서 받아 저장하고, 저장된 전체를 돌려줍니다. 목록에서 빠진 종목은 지웁니다."""
    items = INDICES + chart.monitor_items()["items"]
    with ThreadPoolExecutor(8) as pool:
        got = list(pool.map(lambda ix: _one(client, ix), items))
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S")
    keys = [f"{ix['market']}:{ix['code']}" for ix in items]
    m = macro(client)
    keys += list(m)
    got += list(m.values())
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

    class FakeKB:  # 금리·원유 TR (값은 KB가 정리해 준 숫자·문자열이 섞여 옴)
        def call(self, tr, body):
            if tr == "IVSA0070":
                return {"out4": [{"symbl_cd": "CME@ES", "now_prc": 7700}, {"symbl_cd": "NYM@CL", "symbl_nm": "WTI 근월", "now_prc": 101.41,
                                                                         "up_dwn_r_p2": " 3.51", "dt_tm": "20261016"}]}
            if tr == "IVA10370":
                raise ConnectionError("KB 끊김")
            raise AssertionError(tr)
    m = macro(FakeKB())
    assert m["macro:wti"] == {"name": "WTI 원유 (근월물)", "value": 101.41, "change": 3.51, "unit": "$", "date": "20261016"}, m
    assert m["macro:kr5y"] == {"name": "국고채 5년", "error": "KB 끊김"}, m  # 한 TR 실패는 그 칸만

    def fake_chart(client, market, code, exchange, save=True):
        assert save is False and (code != "SPY" or exchange == "AMX")
        if code == "QQQ":
            raise ValueError("조회 실패")
        return {"candles": [{"d": f"2026{i:04d}", "c": i, "o": 0} for i in range(50)]}
    chart.chart = fake_chart
    q = refresh(FakeKB())["quotes"]
    assert set(q) == {f"{ix['market']}:{ix['code']}" for ix in INDICES} | {"us:NVDA"} | {k for k, *_ in MACRO}, q.keys()
    assert q["macro:wti"]["value"] == 101.41 and "error" in q["macro:kr10y"]
    assert q["us:QQQ"]["error"] == "조회 실패" and "candles" not in q["us:QQQ"]
    assert len(q["us:SPY"]["candles"]) == KEEP_CANDLES and q["us:SPY"]["candles"][-1] == {"d": "20260049", "c": 49}
    chart.save_monitor_items({"items": []})
    assert "us:NVDA" not in refresh(FakeKB())["quotes"]  # 목록에서 뺀 종목은 지움
    assert listing()["indices"] == INDICES and listing()["items"] == []
    print("ok")
