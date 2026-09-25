"""KB증권 OpenAPI 로컬 웹서버.

페이지에서 조회할 때마다 이 서버가 KB OpenAPI를 호출해 JSON으로 돌려줍니다.
appKey/appSecret은 서버에만 있고 브라우저로 전달되지 않습니다.

실행:
    python dividends_web.py              # http://localhost:8000 을 엽니다
    python dividends_web.py --port 8080 --no-open
    python dividends_web.py --no-open    # 리눅스 서버에서 Tomcat 뒤에 둘 때 (deploy/README.md)

페이지:
    GET /         -> 첫 화면: 히트맵 / 내정보 선택 (home.html)
    GET /heatmap  -> 국내·해외 섹터 히트맵 (heatmap.html)
    GET /me       -> 내정보: 보유 종목 현황 + 배당 내역 (me.html)

API:
    GET /api/heatmap?market=kr|us  -> {"market", "currency", "fetchedAt", "stocks": [...], "etf": {...}}
    GET /api/quarters?market=kr|us -> {"quarters", "asOf", "sectors": [{"name", "values"}], "stocks": [...], "failed"}
                                      히트맵 종목의 분기별 일평균 거래대금 (차트 TR, 10분 캐시)
    GET /api/holdings              -> {"fetchedAt", "stocks": [...], "cash": {"krw", "fx_krw", "today"}}  잔고 TR 실시간 조회, 파일 저장 없음
    GET /api/dividends?year=2026   -> {"year", "start", "end", "fetchedAt", "entries": [...]}
"""

from __future__ import annotations

import argparse
import json
import webbrowser
from dataclasses import asdict
from datetime import date, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from dividends import fetch_entries
from heatmap import query_heatmap, query_holdings, query_quarters
from kb_client import KBApiError, KBClient

HERE = Path(__file__).resolve().parent
PAGES = {"/": "home.html", "/heatmap": "heatmap.html", "/me": "me.html"}


def query_dividends(client: KBClient, year: int) -> dict:
    today = date.today()
    if not 2000 <= year <= today.year:
        raise ValueError(f"조회할 수 없는 연도입니다: {year}")
    start = f"{year}0101"
    end = today.strftime("%Y%m%d") if year == today.year else f"{year}1231"
    entries = fetch_entries(client, start, end)
    return {
        "year": year,
        "start": start,
        "end": end,
        "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "entries": [{**asdict(e), "month": e.month, "net": e.net} for e in entries],
    }


def make_handler(client: KBClient):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urlparse(self.path)
            if url.path in PAGES:
                self._send(200, (HERE / PAGES[url.path]).read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/api/dividends":
                self._api(lambda q: query_dividends(client, int(q.get("year", [date.today().year])[0])), parse_qs(url.query))
            elif url.path == "/api/heatmap":
                self._api(lambda q: query_heatmap(client, q.get("market", ["kr"])[0]), parse_qs(url.query))
            elif url.path == "/api/quarters":
                self._api(lambda q: query_quarters(client, q.get("market", ["kr"])[0]), parse_qs(url.query))
            elif url.path == "/api/holdings":
                self._api(lambda q: query_holdings(client), {})
            else:
                self.send_error(404)

        def _api(self, handler, query: dict) -> None:
            try:
                status, payload = 200, handler(query)
            except (ValueError, KBApiError) as e:
                status, payload = 400, {"error": str(e)}
            except Exception as e:  # 네트워크 오류 등
                status, payload = 502, {"error": f"{type(e).__name__}: {e}"}
            self._send(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def _send(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            print(f"[{datetime.now():%H:%M:%S}] {self.command} {self.path} -> {args[1] if len(args) > 1 else ''}")

    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="KB증권 OpenAPI 로컬 웹서버")
    parser.add_argument("--host", default="127.0.0.1",
                        help="바인딩 주소. Tomcat 뒤에서 쓸 때도 127.0.0.1로 두고 Tomcat만 외부에 엽니다.")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--no-open", action="store_true", help="브라우저를 자동으로 열지 않음")
    args = parser.parse_args()

    # 기본은 127.0.0.1에만 바인딩: 같은 네트워크의 다른 기기에서 계좌 내역을 직접 볼 수 없게 합니다.
    server = ThreadingHTTPServer((args.host, args.port), make_handler(KBClient.from_env()))
    url = f"http://localhost:{args.port}"
    print(f"배당금 페이지 실행 중: {url}  (종료: Ctrl+C)", flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
