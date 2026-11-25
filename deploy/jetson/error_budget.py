#!/usr/bin/env python3
"""이번 달 에러 예산과 DB로 셀 수 있는 SLI (README 'SLO / SLI').

에러 예산: 코스닥 5분 기록(kosdaq.db의 kosdaq_tick) 슬롯 기준.

SLO 98%: 평일 09:00~15:40 5분마다 하루 81슬롯 중 저장된 비율. 허용 누락 = 이번 달 평일 수 × 81 × 2%.
빠진 슬롯 하나 = 그 5분 동안 서버·KB 조회·저장 중 하나가 실패. 장중 상태를 매일 남는 기록으로 재는 대리 지표입니다.

  python3 error_budget.py                # 출력만 (GitHub Actions면 Job Summary에도)
  python3 error_budget.py --max 50       # 예산을 50% 이상 썼으면 종료 코드 1
  python3 error_budget.py --fail-slo    # 아래 SLI 중 하나라도 목표 미달이면 종료 코드 1
  python3 error_budget.py --offline      # 계산 자체 점검

SLI (이번 달, 끝난 날만):
  러셀 2000 30분 기록   russell.db russell_tick 저장 슬롯 / 기록 있는 날 × 14       93%
  코스닥 1분봉 수집     minutes.db kosdaq_min1 종목 / 그날 마지막 5분 기록 종목 수   99%
  장마감 전 매도        trader.db trader_position 산 날(미국은 다음 날 07:30 KST) 안에 CLOSED / 산 종목   100%
  /api 응답 5xx 아님    access_log/access.*.log 평일 08:50~16:00 /api/* 중 5xx 아님      99%
  응답 시간             같은 로그, 목표 시간 안 비율 (= p95): 캐시 0.5초, 히트맵 10초, 배당 30초, 주간 평균 90초   95%
데이터 폴더: KB_DATA_DIR (기본 /home/jetson/yang/MyProject_Yang_01)
"""
import argparse
import calendar
import json
import os
import re
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


RUSSELL_PER_DAY = 14              # 10:00~16:30 뉴욕, 30분 간격
PAIR = "미장 연동"                 # Trader.PAIR: 장마감 매도를 끌 수 있어 장마감 SLI에서 뺌
# ponytail: 미장 연동은 closeSell 설정 이력이 없어 늘 뺌. 이력을 남기면 켠 날만 넣기


def ny_done(now: datetime) -> date:
    """이 날짜 전의 뉴욕 거래일은 끝났음 (16:30 ET 기록 = 다음 날 06:30·07:30 KST)."""
    return (now - timedelta(hours=7, minutes=30)).date()


def kr_done(now: datetime, hhmm: str) -> date:
    """이 날짜 전의 한국 날은 끝났음. 오늘도 hhmm(KST)이 지났으면 포함."""
    return now.date() + timedelta(days=1) if now.strftime("%H:%M") >= hhmm else now.date()


def russell_sli(counts: dict, now: datetime):
    """counts: {뉴욕 날짜: 슬롯 수}. 기록 0인 날(휴장·전체 장애)은 kosdaq 예산처럼 뺌."""
    days = [d for d in counts if d < ny_done(now).isoformat()]
    return sum(min(counts[d], RUSSELL_PER_DAY) for d in days), len(days) * RUSSELL_PER_DAY


def minutes_sli(got: dict, listed: dict, now: datetime):
    """got: {날짜: 1분봉 저장 종목 수}, listed: {날짜: 그날 마지막 5분 기록 종목 수}. 21:00 KST 전인 오늘은 뺌."""
    days = [d for d in listed if d < kr_done(now, "21:00").isoformat()]
    return sum(min(got.get(d, 0), listed[d]) for d in days), sum(listed[d] for d in days)


def close_sli(rows: list, now: datetime):
    """rows: [(market, day, source, status, closed KST)] 산 종목. 그날 장 안에 팔렸으면 좋은 이벤트."""
    good = total = 0
    for market, day, source, status, closed in rows:
        if source == PAIR:
            continue
        d = date.fromisoformat(day)
        if market == "KR":
            if d >= kr_done(now, "15:30"):
                continue
            deadline = f"{day} 15:30:00"
        else:
            if d >= ny_done(now):
                continue
            deadline = f"{d + timedelta(days=1)} 07:30:00"
        total += 1
        good += status == "CLOSED" and closed is not None and closed <= deadline
    return good, total


def minutes_between(start: str, end: str) -> list:
    """'yyyy-MM-dd HH:mm' start 이상 end 미만의 분 목록."""
    t, stop = datetime.strptime(start, "%Y-%m-%d %H:%M"), datetime.strptime(end, "%Y-%m-%d %H:%M")
    out = []
    while t < stop:
        out.append(t.strftime("%Y-%m-%d %H:%M"))
        t += timedelta(minutes=1)
    return out


def beat_sli(sessions: list, beats: set, kind: str, now: datetime):
    """trader.db: sessions [(market, open, close) KST], beats {(kind, market, minute)}. 정규장이 끝난 날만.
    tick: 정규장 전체. scalp: 그날 한 번이라도 처리한 날만, 첫 분(5분 기록 전)과 마감 10분 전부터는 뺌 (Trader.CUTOFF_MINUTES)."""
    good = total = 0
    stamp = now.strftime("%Y-%m-%d %H:%M")
    for market, open_, close in sessions:
        if close > stamp:
            continue
        window = minutes_between(open_, close)
        if kind == "scalp":
            if market != "KR" or not any((kind, market, m) in beats for m in window):
                continue
            # ponytail: 장중에 단타를 켜고 끈 시간도 놓친 분으로 셈. 켠 시간 기록이 생기면 그걸로 분모를 줄이기
            window = window[1:-10]
        total += len(window)
        good += sum((kind, market, m) in beats for m in window)
    return good, total


def query(path: str, sql: str, *args) -> list:
    """읽기 전용. 파일이 없으면 빈 목록 (그 기능을 안 쓰는 서버)."""
    if not os.path.exists(path):
        return []
    con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return con.execute(sql, args).fetchall()
    except sqlite3.OperationalError:  # 표가 아직 없음
        return []
    finally:
        con.close()


# 접근 로그 (application.properties server.tomcat.accesslog): 2026-11-25 13:01:02 200 12345 "GET /api/heatmap?market=kr HTTP/1.1"
ACCESS = re.compile(r'^(\d{4}-\d\d-\d\d) (\d\d:\d\d):\d\d (\d{3}) (\d+) "\S+ (\S+)')
# (이름, 경로 앞부분 (None이면 ?cached=1), 목표 ms) — 목표 안 비율 95% = p95가 목표보다 짧음
LATENCY = [("캐시 응답 0.5초 안", None, 500), ("히트맵 10초 안", "/api/heatmap", 10_000),
           ("배당 30초 안", "/api/dividends", 30_000), ("주간 평균 90초 안", "/api/kosdaq-volume", 90_000)]


def access_slis(lines) -> list:
    """평일 08:50~16:00 KST의 /api/* 요청만. [(이름, 좋은 이벤트, 전체, 목표)]"""
    ok = api = 0
    lat = {name: [0, 0] for name, _, _ in LATENCY}
    for line in lines:
        m = ACCESS.match(line)
        if not m:
            continue
        day, hhmm, status, micros, url = m.groups()
        if not url.startswith("/api/") or date.fromisoformat(day).weekday() >= 5 or not "08:50" <= hhmm < "16:00":
            continue
        api += 1
        ok += int(status) < 500
        cached = "cached=1" in url
        for name, prefix, limit in LATENCY:
            if (prefix is None and cached) or (prefix and not cached and url.split("?")[0] == prefix):
                lat[name][1] += 1
                lat[name][0] += int(micros) / 1000 < limit
    return [("/api 응답 5xx 아님 (T3)", ok, api, 0.99)] + [(name, *lat[name], 0.95) for name, _, _ in LATENCY]


def read_access(data: str, month: str):
    """이번 달 access.yyyy-MM-dd.log 줄 (오늘 것까지). 없으면 빈 목록."""
    folder = os.path.join(data, "access_log")
    if not os.path.isdir(folder):
        return
    for name in sorted(os.listdir(folder)):
        if name.startswith(f"access.{month}"):
            with open(os.path.join(folder, name), encoding="utf-8", errors="replace") as f:
                yield from f


def load_slis(data: str, now: datetime) -> list:
    """[(이름, 좋은 이벤트, 전체, 목표)]"""
    month = f"{now:%Y-%m}%"
    russell = dict(query(os.path.join(data, "russell.db"),
                         "SELECT substr(at,1,10), count(*) FROM russell_tick WHERE at LIKE ? GROUP BY 1", month))
    listed = {at[:10]: len(json.loads(p)) for at, p in query(os.path.join(data, "kosdaq.db"),
              "SELECT at, payload FROM kosdaq_tick WHERE at IN (SELECT max(at) FROM kosdaq_tick WHERE at LIKE ? GROUP BY substr(at,1,10))", month)}
    got = dict(query(os.path.join(data, "minutes.db"),
                     "SELECT day, count(DISTINCT code) FROM kosdaq_min1 WHERE day LIKE ? GROUP BY day", month))
    trades = query(os.path.join(data, "trader.db"),
                   "SELECT market, day, source, status, closed FROM trader_position WHERE bought IS NOT NULL AND day LIKE ?", month)
    trader_db = os.path.join(data, "trader.db")
    sessions = query(trader_db, "SELECT market, open, close FROM trader_session WHERE open LIKE ?", month)
    beats = set(query(trader_db, "SELECT kind, market, minute FROM trader_beat WHERE minute LIKE ?", month))
    return [("자동매매 5초 확인 (T0)", *beat_sli(sessions, beats, "tick", now), 0.995),
            ("단타 1분 신호 처리 (T0)", *beat_sli(sessions, beats, "scalp", now), 0.99),
            ("러셀 2000 30분 기록", *russell_sli(russell, now), 0.93),
            ("코스닥 1분봉 수집", *minutes_sli(got, listed, now), 0.99),
            ("장마감 전 매도 (T0)", *close_sli(trades, now), 1.0)] + access_slis(read_access(data, f"{now:%Y-%m}"))


def sli_report(slis: list) -> str:
    lines = ["", "| SLI | 값 | 목표 | |", "|---|---|---|---|"]
    for name, good, total, slo in slis:
        if total == 0:
            lines.append(f"| {name} | 셀 이벤트 없음 | {slo:.0%} | – |")
        else:
            lines.append(f"| {name} | {good / total:.2%} ({good}/{total}) | {slo:.0%} | {'✅' if good / total >= slo else '❌'} |")
    return "\n".join(lines)


def missed(slis: list) -> list:
    return [name for name, good, total, slo in slis if total and good / total < slo]


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

    # 러셀: 오늘(뉴욕 11-25, 아직 장 전)은 빼고, 11-24는 13/14
    assert russell_sli({"2026-11-24": 13, "2026-11-23": 14, "2026-11-25": 3}, now) == (27, 28)
    # 1분봉: 21:00 전이라 오늘(11-25) 빼고, 11-24는 1,690/1,700
    assert minutes_sli({"2026-11-24": 1690, "2026-11-25": 5}, {"2026-11-24": 1700, "2026-11-25": 1700}, now) == (1690, 1700)
    assert minutes_sli({}, {"2026-11-25": 1700}, datetime(2026, 11, 25, 21, 0, tzinfo=KST)) == (0, 1700)
    rows = [("KR", "2026-11-25", "단타", "CLOSED", "2026-11-25 15:20:05"),         # 좋음
            ("KR", "2026-11-24", "직접", "CLOSED", "2026-11-25 09:00:10"),         # 다음 날 팜: 나쁨
            ("KR", "2026-11-24", "직접", "OPEN", None),                            # 안 팜: 나쁨
            ("KR", "2026-11-24", PAIR, "OPEN", None),                              # 미장 연동: 뺌
            ("US", "2026-11-24", "시뮬레이션", "CLOSED", "2026-11-25 05:50:00"),   # 뉴욕 장 안: 좋음
            ("US", "2026-11-25", "직접", "OPEN", None)]                            # 뉴욕 오늘 장 전: 뺌
    assert close_sli(rows, now) == (2, 4)
    assert close_sli(rows[:1], datetime(2026, 11, 25, 15, 0, tzinfo=KST)) == (0, 0)  # 장 마감 전이면 오늘은 아직
    assert missed([("a", 2, 4, 1.0), ("b", 0, 0, 0.99), ("c", 99, 100, 0.99)]) == ["a"]

    # 5초 확인: 국내 09:00~15:30 = 390분 중 389분, 미국은 아직 장 중이라 뺌. 단타: 09:01~15:19 = 379분 중 378분
    sessions = [("KR", "2026-11-25 09:00", "2026-11-25 15:30"), ("KR", "2026-11-24 09:00", "2026-11-24 15:30"),
                ("US", "2026-11-25 23:30", "2026-11-26 06:00")]
    kr = minutes_between("2026-11-25 09:00", "2026-11-25 15:30")
    assert len(kr) == 390 and kr[-1] == "2026-11-25 15:29"
    beats = {("tick", "KR", m) for m in kr if m != "2026-11-25 10:00"} | {("scalp", "KR", m) for m in kr if m != "2026-11-25 10:00"}
    assert beat_sli(sessions, beats, "tick", now) == (389, 780)  # 11-24는 기록 0 → 하루 전체 놓침
    assert beat_sli(sessions, beats, "scalp", now) == (378, 379)  # 11-24는 단타 안 켬 → 뺌
    assert beat_sli(sessions, beats, "tick", datetime(2026, 11, 25, 15, 0, tzinfo=KST)) == (0, 390)  # 오늘은 아직

    log = ['2026-11-25 13:01:02 200 120000 "GET /api/monitor-quotes?cached=1 HTTP/1.1"',   # 캐시 0.12초: 좋음
           '2026-11-25 13:01:03 200 900000 "GET /api/heatmap?cached=1&market=kr HTTP/1.1"',  # 캐시 0.9초: 나쁨
           '2026-11-25 13:01:09 200 6000000 "GET /api/heatmap?market=kr HTTP/1.1"',         # 히트맵 6초: 좋음
           '2026-11-25 13:02:00 502 31000000 "GET /api/dividends?year=2026 HTTP/1.1"',      # 5xx, 31초: 나쁨
           '2026-11-25 13:02:00 404 100 "GET /api/kosdaq-volume?cached=1 HTTP/1.1"',        # 캐시 404: 5xx 아님
           '2026-11-25 13:03:00 200 1000 "GET /trader HTTP/1.1"',                           # /api 아님: 뺌
           '2026-11-25 07:00:00 500 1000 "GET /api/heatmap HTTP/1.1"',                      # 장 전: 뺌
           '2026-11-28 13:00:00 500 1000 "GET /api/heatmap HTTP/1.1"',                      # 토요일: 뺌
           'garbage']
    got = {name: (good, total) for name, good, total, _ in access_slis(log)}
    assert got["/api 응답 5xx 아님 (T3)"] == (4, 5)
    assert got["캐시 응답 0.5초 안"] == (2, 3)
    assert got["히트맵 10초 안"] == (1, 1) and got["배당 30초 안"] == (0, 1) and got["주간 평균 90초 안"] == (0, 0)
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=float, help="사용률(%%)이 이 값 이상이면 종료 코드 1")
    ap.add_argument("--fail-slo", action="store_true", help="SLI 하나라도 목표 미달이면 종료 코드 1")
    ap.add_argument("--offline", action="store_true", help="계산 자체 점검")
    args = ap.parse_args()
    if args.offline:
        return self_check()

    now = datetime.now(KST)
    data = os.environ.get("KB_DATA_DIR", "/home/jetson/yang/MyProject_Yang_01")
    b = budget(load_counts(os.path.join(data, "kosdaq.db"), f"{now:%Y-%m}"), now)
    slis = load_slis(data, now)
    text = report(b, now) + "\n" + sli_report(slis)
    print(text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(text + "\n")
    if args.max is not None and b["used_pct"] >= args.max:
        print(f"에러 예산 {b['used_pct']:.1f}% 사용 (기준 {args.max:g}%)", file=sys.stderr)
        sys.exit(1)
    if args.fail_slo and missed(slis):
        print(f"SLO 미달: {', '.join(missed(slis))}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
