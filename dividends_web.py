"""배당금 확인 페이지 (로컬 웹서버).

페이지(dividends.html)에서 연도를 고르고 조회하면, 이 서버가 그때 KB OpenAPI로 거래내역을 조회해
배당 내역을 JSON으로 돌려줍니다. appKey/appSecret은 서버에만 있고 브라우저로 전달되지 않습니다.

실행:
    python dividends_web.py              # http://localhost:8000 을 열고 올해 배당을 조회
    python dividends_web.py --port 8080 --no-open

API:
    GET /api/dividends?year=2026   -> {"year", "start", "end", "fetchedAt", "entries": [...]}
    GET /heatmap                   -> 국내·해외 섹터 히트맵 페이지 (heatmap.html)
    GET /api/heatmap?market=kr|us  -> {"market", "currency", "fetchedAt", "stocks": [...]}
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
from heatmap import query_heatmap
from kb_client import KBApiError, KBClient

PAGE = Path(__file__).resolve().parent / "dividends.html"
HEATMAP_PAGE = PAGE.with_name("heatmap.html")


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
            if url.path == "/":
                self._send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/heatmap":
                self._send(200, HEATMAP_PAGE.read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/api/dividends":
                self._api(lambda q: query_dividends(client, int(q.get("year", [date.today().year])[0])), parse_qs(url.query))
            elif url.path == "/api/heatmap":
                self._api(lambda q: query_heatmap(client, q.get("market", ["kr"])[0]), parse_qs(url.query))
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
    parser = argparse.ArgumentParser(description="배당금 확인 페이지")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--no-open", action="store_true", help="브라우저를 자동으로 열지 않음")
    args = parser.parse_args()

    # 127.0.0.1에만 바인딩: 같은 네트워크의 다른 기기에서 내 계좌 내역을 볼 수 없게 합니다.
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(KBClient.from_env()))
    url = f"http://localhost:{args.port}"
    print(f"배당금 페이지 실행 중: {url}  (종료: Ctrl+C)", flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
