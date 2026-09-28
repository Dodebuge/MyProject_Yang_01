"""토스증권 OpenAPI 로컬 웹서버. kb_openapi_sample/dividends_web.py와 같은 구조입니다.

페이지에서 조회할 때마다 이 서버가 토스 OpenAPI를 호출해 JSON으로 돌려줍니다.
client_id/client_secret은 서버에만 있고 브라우저로 전달되지 않습니다.

실행:
    python web.py              # http://localhost:8000 을 엽니다
    python web.py --port 8080 --no-open

페이지:
    GET /         -> 첫 화면: 히트맵 / 내정보 선택 (home.html)
    GET /heatmap  -> 국내·해외 섹터 히트맵 (heatmap.html)
    GET /me       -> 내정보: 보유 종목 현황 (me.html)

API:
    GET /api/heatmap?market=kr|us  -> {"market", "currency", "fetchedAt", "stocks": [...], "etf": {...}}
    GET /api/quarters?market=kr|us -> {"quarters", "asOf", "sectors": [{"name", "values"}], "stocks": [...], "failed"}
    GET /api/holdings              -> {"fetchedAt", "stocks": [...], "cash": {"krw", "fx_krw", "usd", "rate"}}
                                      하루(한국 날짜) 한 번만 토스를 조회해 toss.db(SQLite)에 저장, ?refresh=1 이면 다시 조회
"""

from __future__ import annotations

import argparse
import json
import webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from heatmap import query_heatmap, query_holdings, query_quarters
from toss_client import TossApiError, TossClient

HERE = Path(__file__).resolve().parent
PAGES = {"/": "home.html", "/heatmap": "heatmap.html", "/me": "me.html"}


def _refresh(q: dict) -> bool:
    """?refresh=1 : 저장된 오늘 값을 건너뛰고 토스에서 다시 조회 (화면의 '토스에서 다시 조회' 버튼)."""
    return q.get("refresh", [""])[0] in ("1", "true")


def make_handler(client: TossClient):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urlparse(self.path)
            q = parse_qs(url.query)
            if url.path in PAGES:
                self._send(200, (HERE / PAGES[url.path]).read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/api/heatmap":
                self._api(lambda: query_heatmap(client, q.get("market", ["kr"])[0]))
            elif url.path == "/api/quarters":
                self._api(lambda: query_quarters(client, q.get("market", ["kr"])[0]))
            elif url.path == "/api/holdings":
                self._api(lambda: query_holdings(client, _refresh(q)))
            else:
                self.send_error(404)

        def _api(self, handler) -> None:
            try:
                status, payload = 200, handler()
            except (ValueError, TossApiError) as e:
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
    parser = argparse.ArgumentParser(description="토스증권 OpenAPI 로컬 웹서버")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--no-open", action="store_true", help="브라우저를 자동으로 열지 않음")
    args = parser.parse_args()

    # 기본은 127.0.0.1에만 바인딩: 같은 네트워크의 다른 기기에서 계좌 내역을 직접 볼 수 없게 합니다.
    server = ThreadingHTTPServer((args.host, args.port), make_handler(TossClient.from_env()))
    url = f"http://localhost:{args.port}"
    print(f"토스 OpenAPI 샘플 실행 중: {url}  (종료: Ctrl+C)", flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
