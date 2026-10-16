"""내정보 데이터를 하루 한 번만 KB에서 조회해 SQLite(kb.db)에 저장합니다.

같은 날(한국 시간) 다시 요청하면 KB를 부르지 않고 저장된 값을 돌려줍니다. 날짜가 바뀐 뒤 첫 요청에서 새로 조회합니다.
(kind, date)가 기본키라 같은 날 두 번 저장되지 않습니다. 동시에 두 요청이 처음 들어오면 KB를 두 번 부를 수 있지만,
먼저 저장된 값 하나만 남고 두 요청 모두 그 값을 돌려받습니다.

Java 버전(java-app/.../DailyStore.java)과 같은 파일·같은 테이블을 씁니다.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

DB = Path(__file__).resolve().parent / "kb.db"
KST = timezone(timedelta(hours=9))  # 한국은 서머타임이 없어 고정 오프셋으로 충분합니다

SCHEMA = """CREATE TABLE IF NOT EXISTS daily (
  kind       TEXT NOT NULL,   -- 'holdings'(토스 합산이면 'holdings+toss'), 'dividends:2026', 'recurring', 'transactions'
  date       TEXT NOT NULL,   -- 한국 날짜 '2026-09-28'
  fetched_at TEXT NOT NULL,
  payload    TEXT NOT NULL,   -- API 응답 JSON 그대로
  PRIMARY KEY (kind, date)
)"""


def daily(kind: str, fetch: Callable[[], dict], refresh: bool = False) -> dict:
    """오늘 저장된 kind 값을 돌려주고, 없으면 fetch()로 조회해 저장한 뒤 돌려줍니다.
    refresh=True(화면의 'KB에서 다시 조회')면 저장된 값을 건너뛰고 다시 조회해 오늘 값을 덮어씁니다."""
    today = datetime.now(KST).date().isoformat()
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:  # con: 끝나면 commit, closing: 연결 닫기
        con.execute(SCHEMA)
        row = None if refresh else con.execute("SELECT payload FROM daily WHERE kind = ? AND date = ?", (kind, today)).fetchone()
    if row:
        return json.loads(row[0])

    payload = fetch()  # KB 조회는 DB 연결 밖에서 (오래 걸려도 다른 요청이 DB를 쓸 수 있게)
    on_conflict = "DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload" if refresh else "DO NOTHING"
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:
        con.execute(f"INSERT INTO daily (kind, date, fetched_at, payload) VALUES (?, ?, ?, ?) ON CONFLICT (kind, date) {on_conflict}",
                    (kind, today, datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S"), json.dumps(payload, ensure_ascii=False)))
        row = con.execute("SELECT payload FROM daily WHERE kind = ? AND date = ?", (kind, today)).fetchone()
    return json.loads(row[0])


def history(kinds: tuple[str, ...]) -> list[tuple[str, dict]]:
    """kinds로 저장된 날짜별 값 [(날짜, 값)]. 날짜순이고, 같은 날짜 안에서는 kind 이름순."""
    marks = ",".join("?" * len(kinds))
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:
        con.execute(SCHEMA)
        rows = con.execute(f"SELECT date, payload FROM daily WHERE kind IN ({marks}) ORDER BY date, kind", kinds).fetchall()
    return [(d, json.loads(p)) for d, p in rows]


# ---------------------------------------------------------------------- 마지막 응답 (화면 첫 로딩용)
# API가 성공할 때마다 응답 JSON을 last_api/<경로>[_<파라미터>].txt에 덮어써 둡니다.
# 화면은 먼저 ?cached=1로 이 값을 받아 바로 그리고, 실제 API 응답이 오면 교체합니다.
# 파일 이름에 들어가는 파라미터는 market·year처럼 값이 몇 개 안 되는 것뿐이고, chart만 연 종목마다 파일이 하나씩 생깁니다.

LAST_DIR = Path(__file__).resolve().parent / "last_api"
LAST_APIS = {"heatmap", "quarters", "holdings", "dividends", "recurring", "transactions", "chart", "kosdaq-volume"}  # 첫 로딩이 느린 API만
_IGNORED = {"refresh", "cached", "recent"}  # recent=0(최근 검색에 안 남김)도 같은 차트라 같은 파일


def last_file(api: str, params: dict[str, str]) -> Path | None:
    """저장할 API면 파일 경로, 아니면 None. 예: ("heatmap", {"market": "kr"}) -> last_api/heatmap_market=kr.txt"""
    if api not in LAST_APIS:
        return None
    kv = "&".join(f"{k}={v}" for k, v in sorted(params.items()) if k not in _IGNORED)
    name = re.sub(r"[^\w=&.-]", "_", api + ("_" + kv if kv else ""))
    return LAST_DIR / f"{name}.txt"


def save_last(file: Path, body: str) -> None:
    file.parent.mkdir(exist_ok=True)
    tmp = file.with_suffix(".tmp")
    tmp.write_text(body, encoding="utf-8")
    os.replace(tmp, file)  # 읽는 쪽이 반쯤 쓴 파일을 보지 않게 통째로 교체


def read_last(file: Path) -> str | None:
    try:
        return file.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None


if __name__ == "__main__":
    # 자체 점검: 같은 날 두 번째 호출은 fetch를 부르지 않고 첫 값을 돌려줘야 합니다.
    import tempfile
    DB = Path(tempfile.mkdtemp()) / "test.db"
    calls = []
    first = daily("t", lambda: calls.append(1) or {"n": 1, "name": "첫 값"})
    second = daily("t", lambda: calls.append(2) or {"n": 2, "name": "두 번째"})
    assert first == second == {"n": 1, "name": "첫 값"}, (first, second)
    assert calls == [1], calls
    assert daily("other", lambda: {"n": 3}) == {"n": 3}  # kind가 다르면 따로 저장
    # refresh: 저장된 값이 있어도 다시 조회해 덮어쓰고, 이후 일반 조회도 새 값을 돌려줘야 합니다.
    assert daily("t", lambda: calls.append(4) or {"n": 4}, refresh=True) == {"n": 4}
    assert daily("t", lambda: calls.append(5) or {"n": 5}) == {"n": 4}
    assert calls == [1, 4], calls
    assert history(("t", "other")) == [(first_day := datetime.now(KST).date().isoformat(), {"n": 3}), (first_day, {"n": 4})]
    # 마지막 응답: refresh·cached는 파일 이름에서 빠지고, 저장 대상이 아닌 API는 None
    LAST_DIR = DB.parent / "last_api"
    f = last_file("heatmap", {"market": "kr", "refresh": "1", "cached": "1"})
    assert f.name == "heatmap_market=kr.txt" and last_file("search", {"q": "a"}) is None, f
    assert last_file("dividends", {"year": "../x"}).parent == LAST_DIR  # 경로 문자는 _로 바뀜
    assert read_last(f) is None
    save_last(f, '{"a": "첫"}'); save_last(f, '{"a": "둘"}')
    assert read_last(f) == '{"a": "둘"}'
    print("ok")
