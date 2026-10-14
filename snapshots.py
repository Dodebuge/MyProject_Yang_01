"""보유 현황을 4시간마다(한국 시간 00·04·08·12·16·20시) KB·토스에서 조회해 MySQL/MariaDB에 기록합니다.

.env(또는 환경변수)에 MYSQL_HOST가 있을 때만 켜집니다. 없으면 아무것도 하지 않고,
내정보 '보유 금액 변화' 그래프는 kb.db의 하루 기록만 씁니다.
테이블은 처음 접속할 때 만들고, 데이터베이스·계정은 미리 만들어 두세요 (deploy/README.md).
Java 버전(java-app/.../Snapshots.java)과 같은 테이블을 씁니다. 두 서버가 같은 DB를 쓰면 같은 시각 값은 나중 것으로 덮어씁니다.
"""

from __future__ import annotations

import os
import threading
import time
from datetime import datetime, timedelta
from typing import Callable

from daily_store import KST

INTERVAL_HOURS = 4
SCHEMA = """CREATE TABLE IF NOT EXISTS holdings_snapshot (
  at         DATETIME NOT NULL PRIMARY KEY,  -- 한국 시간 정각 (00·04·08·12·16·20시)
  total      BIGINT NOT NULL,                -- 총자산 = stock + cash (원)
  stock      BIGINT NOT NULL,
  cash       BIGINT NOT NULL,
  kb         BIGINT NOT NULL,                -- KB 계좌 총자산
  kb_stock   BIGINT NOT NULL,
  toss       BIGINT NULL,                    -- 토스 계좌 총자산 (토스 미연결이면 NULL)
  toss_stock BIGINT NULL
)"""
COLS = {"total": "total", "stock": "stock", "cash": "cash", "kb": "kb", "kbStock": "kb_stock", "toss": "toss", "tossStock": "toss_stock"}


def settings() -> dict | None:
    """MYSQL_HOST·PORT·DATABASE·USER·PASSWORD (환경변수 또는 루트 .env, kb_client가 읽어 둠). 호스트가 없으면 None (기록 안 함)."""
    if not os.environ.get("MYSQL_HOST"):
        return None
    return {"host": os.environ["MYSQL_HOST"], "port": int(os.environ.get("MYSQL_PORT") or 3306),
            "database": os.environ.get("MYSQL_DATABASE") or "kb_openapi",
            "user": os.environ.get("MYSQL_USER", ""), "password": os.environ.get("MYSQL_PASSWORD", "")}


def _connect(s: dict):
    import pymysql  # MySQL을 쓸 때만 필요 (requirements.txt)
    con = pymysql.connect(**s, charset="utf8mb4", autocommit=True, connect_timeout=10)
    with con.cursor() as cur:
        cur.execute(SCHEMA)
    return con


def save(s: dict, summary: dict) -> None:
    """summarize() 결과 하나를 저장. summary["date"]는 '2026-10-15T04:00'. 같은 시각이 있으면 덮어씁니다."""
    cols = ", ".join(COLS.values())
    sql = (f"INSERT INTO holdings_snapshot (at, {cols}) VALUES (%s{', %s' * len(COLS)}) "
           f"ON DUPLICATE KEY UPDATE " + ", ".join(f"{c} = VALUES({c})" for c in COLS.values()))
    with _connect(s) as con, con.cursor() as cur:
        cur.execute(sql, (summary["date"].replace("T", " "), *(summary[k] for k in COLS)))


def load(s: dict) -> list[dict]:
    with _connect(s) as con, con.cursor() as cur:
        cur.execute(f"SELECT at, {', '.join(COLS.values())} FROM holdings_snapshot ORDER BY at")
        return [{"date": r[0].strftime("%Y-%m-%dT%H:%M"), **{k: None if v is None else int(v) for k, v in zip(COLS, r[1:])}}
                for r in cur.fetchall()]


def merge(days: list[dict], snaps: list[dict]) -> list[dict]:
    """kb.db의 하루 기록 + 4시간 기록. 4시간 기록이 있는 날은 하루 기록을 빼고 4시간 기록만 씁니다."""
    covered = {s["date"][:10] for s in snaps}
    return sorted([d for d in days if d["date"] not in covered] + snaps, key=lambda d: d["date"])


def history(days_payload: dict) -> dict:
    """GET /api/holdings-history 응답에 MySQL 4시간 기록을 합칩니다. MySQL이 안 되면 하루 기록만 돌려주고 이유를 snapshotError에."""
    s = settings()
    if not s:
        return days_payload
    try:
        return {"days": merge(days_payload["days"], load(s)), "interval": INTERVAL_HOURS}
    except Exception as e:  # MySQL이 꺼져 있어도 그래프는 보이게
        return {**days_payload, "snapshotError": f"{type(e).__name__}: {e}"}


def next_run(now: datetime) -> datetime:
    """now 다음의 4시간 정각 (00·04·…·20시)."""
    return now.replace(hour=now.hour - now.hour % INTERVAL_HOURS, minute=0, second=0, microsecond=0) + timedelta(hours=INTERVAL_HOURS)


def start(take: Callable[[], dict], summarize: Callable[[str, dict], dict]) -> None:
    """백그라운드 스레드로 4시간마다 take()(KB·토스 보유 현황 새로 조회)를 불러 저장합니다. MySQL 설정이 없으면 아무것도 안 함."""
    s = settings()
    if not s:
        print("[기록] MYSQL_HOST 없음 -> 4시간 보유 금액 기록 안 함", flush=True)
        return

    def loop():
        while True:
            at = next_run(datetime.now(KST))
            while (wait := (at - datetime.now(KST)).total_seconds()) > 0:
                time.sleep(min(wait, 600))  # 길게 한 번에 자지 않음 (PC 절전 후 시계가 건너뛰어도 늦지 않게)
            try:
                summary = summarize(at.strftime("%Y-%m-%dT%H:%M"), take())
                save(s, summary)
                print(f"[기록] {summary['date']} 총자산 {summary['total']:,}원 저장", flush=True)
            except Exception as e:  # KB·MySQL 오류여도 다음 시각에 다시
                print(f"[기록] {at:%Y-%m-%d %H:%M} 실패: {type(e).__name__}: {e}", flush=True)

    threading.Thread(target=loop, name="snapshots", daemon=True).start()
    print(f"[기록] {s['user']}@{s['host']}:{s['port']}/{s['database']} 에 {INTERVAL_HOURS}시간마다 보유 금액 기록 "
          f"(다음 {next_run(datetime.now(KST)):%H:%M})", flush=True)


def check() -> bool:
    """DB 접속 점검 (python snapshots.py --check). .env의 MYSQL_* 로 접속해 테이블 생성, 쓰기·읽기(되돌림), 기록 현황을 확인합니다.
    시험 쓰기는 트랜잭션 안에서 하고 되돌리므로 기록이 남지 않습니다."""
    s = settings()
    if not s:
        print("[실패] MYSQL_HOST가 없습니다. .env에 MYSQL_HOST·MYSQL_USER·MYSQL_PASSWORD를 넣으세요 (deploy/install-mariadb.sh).")
        return False
    print(f"설정    {s['user']} @ {s['host']}:{s['port']}/{s['database']} (비밀번호 {'있음' if s['password'] else '없음'})")
    try:
        with _connect(s) as con:  # 접속 + CREATE TABLE IF NOT EXISTS
            print(f"[성공] 접속·테이블  {con.get_server_info()}")
            con.begin()
            try:
                with con.cursor() as cur:
                    cur.execute("INSERT INTO holdings_snapshot (at, total, stock, cash, kb, kb_stock) VALUES ('2000-01-01 00:00:00', 1, 1, 0, 1, 1)"
                                " ON DUPLICATE KEY UPDATE total = 1")
                    cur.execute("SELECT total FROM holdings_snapshot WHERE at = '2000-01-01 00:00:00'")
                    if cur.fetchone() != (1,):
                        raise RuntimeError("쓴 값을 다시 읽지 못했습니다")
            finally:
                con.rollback()  # 시험 쓰기는 남기지 않음
            print("[성공] 쓰기·읽기 (되돌림)")
            with con.cursor() as cur:
                cur.execute("SELECT COUNT(*), MAX(at) FROM holdings_snapshot")
                n, last = cur.fetchone()
            print(f"기록    {n}건{f', 마지막 {last:%Y-%m-%dT%H:%M}' if last else ''} · 다음 기록 {next_run(datetime.now(KST)):%Y-%m-%dT%H:%M}")
        return True
    except Exception as e:  # 접속 거부, 인증 실패, 권한 없음 등
        print(f"[실패] {type(e).__name__}: {e}")
        return False


if __name__ == "__main__":
    import sys
    if "--check" in sys.argv:  # DB 접속 점검: python snapshots.py --check
        import kb_client  # noqa: F401  .env를 환경변수로 읽어 둠
        sys.exit(0 if check() else 1)
    # 자체 점검: 다음 실행 시각과 하루/4시간 기록 합치기
    assert next_run(datetime(2026, 10, 15, 0, 0)) == datetime(2026, 10, 15, 4, 0)
    assert next_run(datetime(2026, 10, 15, 3, 59)) == datetime(2026, 10, 15, 4, 0)
    assert next_run(datetime(2026, 10, 15, 22, 10)) == datetime(2026, 10, 16, 0, 0)
    days = [{"date": "2026-10-14"}, {"date": "2026-10-15"}]
    snaps = [{"date": "2026-10-15T00:00"}, {"date": "2026-10-15T04:00"}]
    assert [d["date"] for d in merge(days, snaps)] == ["2026-10-14", "2026-10-15T00:00", "2026-10-15T04:00"]
    print("ok")
