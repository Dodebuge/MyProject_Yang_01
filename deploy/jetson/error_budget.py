#!/usr/bin/env python3
"""이번 달 에러 예산: 코스닥 5분 기록(kosdaq.db의 kosdaq_tick) 슬롯 기준 (README 'SLO / SLI').

SLO 98%: 평일 09:00~15:40 5분마다 하루 81슬롯 중 저장된 비율. 허용 누락 = 이번 달 평일 수 × 81 × 2%.
빠진 슬롯 하나 = 그 5분 동안 서버·KB 조회·저장 중 하나가 실패. 장중 상태를 매일 남는 기록으로 재는 대리 지표입니다.

  python3 error_budget.py                # 출력만 (GitHub Actions면 Job Summary에도)
  python3 error_budget.py --max 50       # 예산을 50% 이상 썼으면 종료 코드 1
  python3 error_budget.py --offline      # 계산 자체 점검
데이터 폴더: KB_DATA_DIR (기본 /home/jetson/yang/MyProject_Yang_01)
"""
import argparse
import calendar
import os
import sqlite3
import sys
from datetime import date, datetime, timedelta, timezone

SLO = 0.98
PER_DAY = 81                       # 09:00~15:40, 5분 간격
KST = timezone(timedelta(hours=9))  # 서머타임 없음 (Python 3.8 Jetson에도 zoneinfo 없이)


def expected_today(now: datetime) -> int:
    """오늘 now까지 이미 기록됐어야 할 슬롯 수. 기록은 정각 30초 뒤라 2분 여유."""
    minutes = (now.hour * 60 + now.minute - 2) - 9 * 60
    return 0 if minutes < 0 else min(PER_DAY, minutes // 5 + 1)


def budget(counts: dict, now: datetime) -> dict:
    """counts: {'YYYY-MM-DD': 저장된 슬롯 수}. 이번 달 1일~오늘 평일만 셉니다."""
    today = now.date()
    weekdays = [date(today.year, today.month, d) for d in range(1, calendar.monthrange(today.year, today.month)[1] + 1)]
    weekdays = [d for d in weekdays if d.weekday() < 5]
    allowed = len(weekdays) * PER_DAY * (1 - SLO)
    missing, unknown = 0, []
    for d in weekdays:
        if d > today:
            break
        want = PER_DAY if d < today else expected_today(now)
        got = counts.get(d.isoformat(), 0)
        if got == 0 and want > 0:
            # ponytail: 기록 0인 평일은 휴장일과 하루 전체 장애를 구분 못 해 예산에서 뺌. 휴장일 목록이 생기면 그걸로 가르기
            unknown.append(d.isoformat())
            continue
        missing += max(0, want - got)
    return {"allowed": allowed, "missing": missing, "used_pct": 100 * missing / allowed, "unknown": unknown}


def load_counts(db_path: str, month: str) -> dict:
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        rows = con.execute("SELECT substr(at,1,10), count(*) FROM kosdaq_tick WHERE at LIKE ? GROUP BY 1", (month + "%",))
        return dict(rows.fetchall())
    finally:
        con.close()


def report(b: dict, now: datetime) -> str:
    lines = [
        f"### 에러 예산 {now:%Y-%m} (코스닥 5분 기록, SLO {SLO:.0%})",
        "",
        f"- 사용: **{b['used_pct']:.1f}%** (빠진 슬롯 {b['missing']} / 허용 {b['allowed']:.0f}, 1슬롯 = 5분)",
        f"- 남은 예산: {max(0.0, b['allowed'] - b['missing']):.0f}슬롯 (약 {max(0.0, b['allowed'] - b['missing']) * 5:.0f}분)",
    ]
    if b["unknown"]:
        lines.append(f"- 기록 0인 평일 (휴장일이 아니면 하루 전체 장애, 예산에서 뺌): {', '.join(b['unknown'])}")
    if b["used_pct"] >= 100:
        lines.append("- **예산 소진**: 자동매매를 끄고 원인부터 고치세요 (README '에러 예산 운용').")
    elif b["used_pct"] >= 50:
        lines.append("- **절반 넘게 사용**: 장중 수동 배포(now) 금지, 자동매매 새 기능 배포 보류.")
    return "\n".join(lines)


def self_check():
    now = datetime(2026, 11, 25, 16, 0, tzinfo=KST)  # 수요일, 11월 평일 21일 → 허용 21*81*2% = 34.02
    assert expected_today(now) == 81
    assert expected_today(datetime(2026, 11, 25, 9, 1)) == 0
    assert expected_today(datetime(2026, 11, 25, 9, 2)) == 1
    assert expected_today(datetime(2026, 11, 25, 9, 12)) == 3     # 09:00·09:05·09:10
    full = {f"2026-11-{d:02d}": PER_DAY for d in range(1, 26)}
    b = budget(full, now)
    assert b["missing"] == 0 and round(b["allowed"], 2) == 34.02 and b["unknown"] == []
    b = budget({**full, "2026-11-24": 64, "2026-11-20": 0}, now)
    assert b["missing"] == 17 and b["unknown"] == ["2026-11-20"] and 49 < b["used_pct"] < 50.1
    b = budget({**full, "2026-11-25": 10}, datetime(2026, 11, 25, 10, 0, tzinfo=KST))  # 오늘은 09:55까지 12슬롯
    assert b["missing"] == 2
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=float, help="사용률(%%)이 이 값 이상이면 종료 코드 1")
    ap.add_argument("--offline", action="store_true", help="계산 자체 점검")
    args = ap.parse_args()
    if args.offline:
        return self_check()

    now = datetime.now(KST)
    db = os.path.join(os.environ.get("KB_DATA_DIR", "/home/jetson/yang/MyProject_Yang_01"), "kosdaq.db")
    b = budget(load_counts(db, f"{now:%Y-%m}"), now)
    text = report(b, now)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text + "\n")
    if args.max is not None and b["used_pct"] >= args.max:
        print(f"에러 예산 {b['used_pct']:.1f}% 사용 (기준 {args.max:g}%)", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
