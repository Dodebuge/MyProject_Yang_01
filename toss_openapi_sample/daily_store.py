"""내정보 데이터를 하루 한 번만 토스증권에서 조회해 SQLite(toss.db)에 저장합니다.

같은 날(한국 시간) 다시 요청하면 토스를 부르지 않고 저장된 값을 돌려줍니다. 날짜가 바뀐 뒤 첫 요청에서 새로 조회합니다.
(kind, date)가 기본키라 같은 날 두 번 저장되지 않습니다. 동시에 두 요청이 처음 들어오면 토스를 두 번 부를 수 있지만,
먼저 저장된 값 하나만 남고 두 요청 모두 그 값을 돌려받습니다.
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import closing
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

DB = Path(__file__).resolve().parent / "toss.db"
KST = timezone(timedelta(hours=9))  # 한국은 서머타임이 없어 고정 오프셋으로 충분합니다

SCHEMA = """CREATE TABLE IF NOT EXISTS daily (
  kind       TEXT NOT NULL,   -- 'holdings'
  date       TEXT NOT NULL,   -- 한국 날짜 '2026-09-28'
  fetched_at TEXT NOT NULL,
  payload    TEXT NOT NULL,   -- API 응답 JSON 그대로
  PRIMARY KEY (kind, date)
)"""


def daily(kind: str, fetch: Callable[[], dict], refresh: bool = False) -> dict:
    """오늘 저장된 kind 값을 돌려주고, 없으면 fetch()로 조회해 저장한 뒤 돌려줍니다.
    refresh=True(화면의 '토스에서 다시 조회')면 저장된 값을 건너뛰고 다시 조회해 오늘 값을 덮어씁니다."""
    today = datetime.now(KST).date().isoformat()
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:  # con: 끝나면 commit, closing: 연결 닫기
        con.execute(SCHEMA)
        row = None if refresh else con.execute("SELECT payload FROM daily WHERE kind = ? AND date = ?", (kind, today)).fetchone()
    if row:
        return json.loads(row[0])

    payload = fetch()  # 토스 조회는 DB 연결 밖에서 (오래 걸려도 다른 요청이 DB를 쓸 수 있게)
    on_conflict = "DO UPDATE SET fetched_at = excluded.fetched_at, payload = excluded.payload" if refresh else "DO NOTHING"
    with closing(sqlite3.connect(DB, timeout=30)) as con, con:
        con.execute(f"INSERT INTO daily (kind, date, fetched_at, payload) VALUES (?, ?, ?, ?) ON CONFLICT (kind, date) {on_conflict}",
                    (kind, today, datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S"), json.dumps(payload, ensure_ascii=False)))
        row = con.execute("SELECT payload FROM daily WHERE kind = ? AND date = ?", (kind, today)).fetchone()
    return json.loads(row[0])


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
    print("ok")
