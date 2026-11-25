#!/usr/bin/env python3
"""SLO 모니터 서버: Spring(9080)과 따로 도는 작은 웹 서버 (README 'SLO 모니터 서버').

- 화면 /         : 이번 달 에러 예산과 SLI 표 (error_budget.py와 같은 계산), 60초마다 새로 고침
- JSON /api/slo  : 같은 내용
- 블랙박스 점검  : 30초마다 Spring 응답(--target)을 직접 불러 slo.db에 남기고 'Spring 응답' SLI로 셈 (목표 99.5%).
                   3번 연속(90초) 실패하면 ntfy로 알리고, 돌아오면 다시 알림 (.env의 NTFY_TOPIC, 없으면 알림 없음)
Spring이 죽어도 이 서버는 살아 있어야 해서 표준 라이브러리만 쓰고 Spring 데이터는 읽기만 합니다.

  python3 deploy/jetson/slo_server.py                  # http://0.0.0.0:9090
  python3 deploy/jetson/slo_server.py --port 9091 --target http://127.0.0.1:9080/api/settings
  python3 deploy/jetson/slo_server.py --offline        # 자체 점검
데이터 폴더: KB_DATA_DIR (기본 /home/jetson/yang/MyProject_Yang_01)
"""
import argparse
import html
import json
import os
import sqlite3
import ssl
import sys
import tempfile
import threading
import time
import urllib.request
from contextlib import closing
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import error_budget as eb  # noqa: E402  같은 폴더의 에러 예산 계산을 그대로 씀

PROBE_EVERY = 30      # 초
DOWN_AFTER = 3        # 연속 실패 몇 번이면 장애로 알림 (90초)
PROBE_SLO = 0.995
KEEP_DAYS = 40
CACHE_SECONDS = 60    # 화면을 자주 열어도 DB는 1분에 한 번만 읽음


def read_env(data: str) -> dict:
    """데이터 폴더 .env(KEY=값)에 환경변수를 덮어씀 (Spring 설정 우선순위와 같음)."""
    out = {}
    try:
        with open(os.path.join(data, ".env"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    out[k.strip()] = v.split(" #")[0].strip().strip('"').strip("'")
    except OSError:
        pass
    out.update({k: v for k, v in os.environ.items() if k.startswith("NTFY_")})
    return out


def next_state(down: bool, fails: int, ok: bool):
    """점검 한 번 뒤 (장애 중?, 연속 실패 수, 보낼 알림 또는 None)."""
    if ok:
        return False, 0, ("recovered" if down else None)
    fails += 1
    if not down and fails >= DOWN_AFTER:
        return True, fails, "down"
    return down, fails, None


class Probe:
    """30초마다 target을 부르고 slo.db probe(at KST, ok, ms)에 남김."""

    def __init__(self, data: str, target: str, env: dict):
        self.path = os.path.join(data, "slo.db")
        self.target = target
        self.topic = env.get("NTFY_TOPIC", "")
        self.server = env.get("NTFY_SERVER", "https://ntfy.sh")
        self.down, self.fails, self.last = False, 0, None
        with closing(sqlite3.connect(self.path)) as c, c:  # closing: 닫기, c: 커밋
            c.execute("CREATE TABLE IF NOT EXISTS probe (at TEXT PRIMARY KEY, ok INTEGER NOT NULL, ms INTEGER NOT NULL)")

    def once(self):
        t0 = time.monotonic()
        try:
            # 로컬 Spring이 자체 서명 https일 수 있어 인증서는 보지 않음 (127.0.0.1만 부름)
            ctx = ssl._create_unverified_context() if self.target.startswith("https") else None
            with urllib.request.urlopen(self.target, timeout=10, context=ctx) as r:
                r.read()
            ok, err = True, ""
        except Exception as e:  # 연결 거부·시간 초과·4xx·5xx 모두 실패
            ok, err = False, f"{type(e).__name__}: {e}"
        ms = int((time.monotonic() - t0) * 1000)
        now = datetime.now(eb.KST)
        with closing(sqlite3.connect(self.path)) as c, c:  # closing: 닫기, c: 커밋
            c.execute("INSERT OR REPLACE INTO probe VALUES (?, ?, ?)", (now.strftime("%Y-%m-%d %H:%M:%S"), int(ok), ms))
            c.execute("DELETE FROM probe WHERE at < ?", ((now - timedelta(days=KEEP_DAYS)).strftime("%Y-%m-%d"),))
        self.down, self.fails, alert = next_state(self.down, self.fails, ok)
        self.last = {"at": now.strftime("%Y-%m-%d %H:%M:%S"), "ok": ok, "ms": ms, "error": err, "down": self.down}
        if alert == "down":
            self.notify("Spring 응답 없음", f"{self.target} {self.fails}번 연속 실패 ({self.fails * PROBE_EVERY}초)\n{err}", 5)
        elif alert == "recovered":
            self.notify("Spring 복구", f"{self.target} 다시 응답 ({ms}ms)", 3)

    def notify(self, title: str, message: str, priority: int):
        print(f"[알림] {title}: {message}", flush=True)
        if not self.topic:
            return
        body = json.dumps({"topic": self.topic, "title": f"[SLO] {title}", "message": message,
                           "priority": priority, "tags": ["rotating_light" if priority >= 5 else "white_check_mark"]}).encode()
        try:
            req = urllib.request.Request(self.server, data=body, headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=10).read()
        except Exception as e:
            print(f"[알림] ntfy 보내기 실패: {type(e).__name__}: {e}", flush=True)

    def loop(self):
        while True:
            try:
                self.once()
            except Exception as e:  # slo.db 오류 등: 점검은 계속
                print(f"[점검] 오류: {type(e).__name__}: {e}", flush=True)
            time.sleep(PROBE_EVERY)

    def month_sli(self, month: str):
        try:
            with closing(sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)) as c:
                good, total = c.execute("SELECT coalesce(sum(ok), 0), count(*) FROM probe WHERE at LIKE ?", (month + "%",)).fetchone()
        except sqlite3.Error:
            good = total = 0
        return good, total


def snapshot(data: str, probe: Probe) -> dict:
    now = datetime.now(eb.KST)
    month = f"{now:%Y-%m}"
    b = eb.budget(eb.day_counts(data, "kosdaq.db", "kosdaq_tick", month, now.date()), now)
    slis = [("Spring 응답 (블랙박스, 30초)", *probe.month_sli(month), PROBE_SLO)] + eb.load_slis(data, now)
    return {"at": now.strftime("%Y-%m-%d %H:%M:%S"), "month": month, "budget": b, "probe": probe.last,
            "slis": [{"name": n, "good": g, "total": t, "slo": s, "value": (g / t if t else None),
                      "met": (None if not t else g / t >= s)} for n, g, t, s in slis]}


def render(s: dict) -> str:
    b, p = s["budget"], s["probe"] or {}
    used = b["used_pct"]
    bar = min(100.0, used)
    level = "bad" if used >= 100 else "warn" if used >= 50 else "good"
    state = ("확인 전" if not p else "장애" if p["down"] else "정상" if p["ok"] else "실패 (아직 장애 전)")
    rows = ""
    for r in s["slis"]:  # Python 3.8(Jetson)도 되게 f-string 안에 같은 따옴표를 다시 쓰지 않음
        value = "–" if r["value"] is None else format(r["value"], ".2%")
        cls, word = {None: ("mute", "셀 이벤트 없음"), True: ("good", "충족"), False: ("bad", "미달")}[r["met"]]
        rows += (f'<tr><td>{html.escape(r["name"])}</td><td class="num">{value}</td>'
                 f'<td class="num">{r["good"]:,} / {r["total"]:,}</td><td class="num">{r["slo"]:.1%}</td>'
                 f'<td class="{cls}">{word}</td></tr>')
    unknown = f'<p class="mute">기록 0인 평일 (휴장 또는 하루 전체 장애, 예산에서 뺌): {", ".join(b["unknown"])}</p>' if b["unknown"] else ""
    if b["short"]:
        unknown += '<p class="mute">81칸이 안 되는 날: ' + ", ".join(f"{d} ({n}/{eb.PER_DAY})" for d, n in b["short"]) + "</p>"
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="60">
<title>SLO 모니터</title><style>
:root{{--bg:#fff;--fg:#1f2328;--mute:#656d76;--line:#d0d7de;--card:#f6f8fa;--good:#1a7f37;--warn:#9a6700;--bad:#cf222e}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1117;--fg:#e6edf3;--mute:#8d96a0;--line:#30363d;--card:#161b22;--good:#3fb950;--warn:#d29922;--bad:#f85149}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,"Malgun Gothic",sans-serif}}
main{{max-width:960px;margin:0 auto;padding:16px}}h1{{font-size:22px;margin:4px 0 12px}}
.cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px 14px}}
.big{{font-size:28px;font-weight:600}}.track{{height:8px;background:var(--line);border-radius:4px;overflow:hidden;margin:8px 0}}
.fill{{height:100%}}.good{{color:var(--good)}}.warn{{color:var(--warn)}}.bad{{color:var(--bad)}}.mute{{color:var(--mute)}}
.fill.good{{background:var(--good)}}.fill.warn{{background:var(--warn)}}.fill.bad{{background:var(--bad)}}
.wrap{{overflow-x:auto;margin-top:16px}}table{{border-collapse:collapse;width:100%;min-width:560px}}
th,td{{border-bottom:1px solid var(--line);padding:8px;text-align:left}}th{{color:var(--mute);font-weight:500}}.num{{text-align:right;font-variant-numeric:tabular-nums}}
</style></head><body><main>
<h1>SLO 모니터 · {s["month"]}</h1>
<div class="cards">
<div class="card"><div class="mute">에러 예산 (코스닥 5분 기록, SLO {eb.SLO:.0%})</div>
<div class="big {level}">{used:.1f}% 사용</div><div class="track"><div class="fill {level}" style="width:{bar:.1f}%"></div></div>
<div class="mute">빠진 슬롯 {b["missing"]} / 허용 {b["allowed"]:.0f} · 50% 이상 장중 배포 차단 · 100% 자동매매 끔</div></div>
<div class="card"><div class="mute">Spring 응답 (마지막 점검)</div>
<div class="big {"bad" if p.get("down") else "good" if p.get("ok") else "warn"}">{state}</div>
<div class="mute">{html.escape(p.get("at", "–"))} · {p.get("ms", "–")}ms {html.escape(p.get("error", ""))}</div></div>
</div>{unknown}
<div class="wrap"><table><thead><tr><th>SLI</th><th class="num">값</th><th class="num">좋은 / 전체</th><th class="num">목표</th><th>상태</th></tr></thead>
<tbody>{rows}</tbody></table></div>
<p class="mute">{s["at"]} KST 계산 · 1분마다 다시 계산 · <a href="api/slo">JSON</a> · 매일 16:00 GitHub Actions 점검과 같은 계산 (deploy/jetson/error_budget.py)</p>
</main></body></html>"""


def serve(data: str, probe: Probe, host: str, port: int):
    cache = {"at": 0.0, "snap": None}
    lock = threading.Lock()

    def current():
        with lock:
            if time.time() - cache["at"] >= CACHE_SECONDS:
                cache["snap"], cache["at"] = snapshot(data, probe), time.time()
            return cache["snap"]

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split("?")[0]
            if path not in ("/", "/api/slo"):
                self.send_error(404)
                return
            try:
                s = current()
            except Exception as e:
                self.send_error(500, f"{type(e).__name__}: {e}")
                return
            body = (json.dumps(s, ensure_ascii=False) if path == "/api/slo" else render(s)).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8" if path == "/api/slo" else "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass  # 60초 새로 고침 요청까지 남기지 않음

    print(f"SLO 모니터: http://{host}:{port} (점검 대상 {probe.target}, 데이터 {data})", flush=True)
    ThreadingHTTPServer((host, port), Handler).serve_forever()


def self_check():
    assert next_state(False, 0, True) == (False, 0, None)
    assert next_state(False, 1, False) == (False, 2, None)
    assert next_state(False, 2, False) == (True, 3, "down")
    assert next_state(True, 3, False) == (True, 4, None), "장애 중에는 다시 알리지 않음"
    assert next_state(True, 4, True) == (False, 0, "recovered")
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, ".env"), "w", encoding="utf-8") as f:
            f.write("# 주석\nNTFY_TOPIC=kb-test   # 끝 주석\nAPP_URL='http://x'\n")
        env = read_env(d)
        assert env.get("NTFY_TOPIC") == os.environ.get("NTFY_TOPIC", "kb-test") and env["APP_URL"] == "http://x"
        probe = Probe(d, "http://127.0.0.1:9", {})  # 닫힌 포트: 실패
        for _ in range(DOWN_AFTER):
            probe.once()
        assert probe.down and probe.last["ok"] is False
        assert probe.month_sli(datetime.now(eb.KST).strftime("%Y-%m")) == (0, DOWN_AFTER)
        s = snapshot(d, probe)  # 다른 DB가 없어도 화면이 나와야 함
        assert s["slis"][0]["total"] == DOWN_AFTER and s["slis"][0]["met"] is False
        page = render(s)
        assert "SLO 모니터" in page and "장애" in page and "셀 이벤트 없음" in page
        json.dumps(s, ensure_ascii=False)
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=9090)
    ap.add_argument("--target", default="http://127.0.0.1:9080/api/settings", help="블랙박스 점검 주소 (Spring)")
    ap.add_argument("--offline", action="store_true", help="자체 점검")
    args = ap.parse_args()
    if args.offline:
        return self_check()
    data = os.environ.get("KB_DATA_DIR", "/home/jetson/yang/MyProject_Yang_01")
    probe = Probe(data, args.target, read_env(data))
    threading.Thread(target=probe.loop, daemon=True).start()
    serve(data, probe, args.host, args.port)


if __name__ == "__main__":
    main()
