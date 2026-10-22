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
    GET /chart    -> 종목 검색 + 일봉 차트 (chart.html)
    GET /volume   -> 코스닥·러셀 2000 거래량 증가 종목 (volume.html, ?market=russell)
    GET /monitor  -> 지수 히트맵(추종 ETF) + 시그널 알림 (monitor.html)
    GET /trader   -> 토스 자동매매 (trader.html). 엔진은 Spring·Java 서버에만 있어 이 서버의 /api/trader는 안내 오류
    GET /signals.js  -> 차트·거래량 화면이 함께 쓰는 보조지표·시그널 계산
    GET /sw.js       -> 브라우저 알림 서비스 워커 (안드로이드 크롬 등)
    GET /docs/*.html -> Archify 구조도·순서도 (첫 화면의 '구조 문서')

API:
    GET /api/heatmap?market=kr|us  -> {"market", "currency", "fetchedAt", "stocks": [...], "etf": {...}}
    GET /api/quarters?market=kr|us -> {"quarters", "asOf", "sectors": [{"name", "values"}], "stocks": [...], "failed"}
                                      히트맵 종목의 분기별 일평균 거래대금 (차트 TR, 10분 캐시)
    GET /api/search?q=삼성         -> {"query", "results": [{"market", "code", "name", "exchange"}]}  국내 이름·종목번호, 미국 티커
    GET /api/chart?market=kr|us&code=005930[&exchange=NAS][&recent=0][&minutes=1|3|5] -> {"market", "code", "name", "exchange", "currency", "bars", "candles": [{"d","o","h","l","c","v","amt"(,"t")}]}
    GET /api/recent                -> {"items": [{"market", "code", "exchange", "name"}]}  최근 검색 종목 (recent_searches.txt, 최대 10개)
    GET /api/settings              -> {"period", "ma5", "ma20", "ma60", "ma120", "bb", "rsi", "macd"}  차트 설정 (chart_settings.json)
    POST /api/settings  {...}      -> 바꾼 설정을 저장하고 저장된 값을 돌려줌 (모르는 키·잘못된 값은 버림)
    GET /api/monitor               -> {"indices": [...], "items": [{"market", "code", "name", "exchange", "etf"}]}  지수 칸 + 시장 모니터에 추가한 종목 (monitor_items.json)
    GET /api/monitor-quotes[?refresh=1] -> {"quotes": {"kr:069500": {"fetchedAt", "candles": [{"d", "c"}]} 또는 {"fetchedAt", "error"}}}  5분마다 kb.db에 저장한 시세 (refresh=1이면 지금 KB에서 다시 받아 저장)
    POST /api/monitor {"items": [...]} -> 목록을 통째로 저장하고 저장된 목록을 돌려줌 (잘못된 종목·중복은 버림, 최대 30개)
    GET /api/holdings             -> {"fetchedAt", "stocks": [...], "cash": {"krw", "fx_krw", "today", "toss"}}  KB 잔고 TR + 토스 /holdings
    GET /api/holdings-history      -> {"days": [{"date", "stock", "cash", "total", "kb", "toss", "kbStock", "tossStock"}]}  날짜별 총자산 (kb.db에 저장된 보유 현황, KB 조회 없음)
                                      MYSQL_HOST가 있으면 4시간마다(00·04·…·20시) 기록한 MySQL 값도 합침 (snapshots.py)
    GET /api/dividends?year=2026   -> {"year", "start", "end", "fetchedAt", "entries": [...]}
    GET /api/recurring             -> {"start", "end", "fetchedAt", "plans": [...]}  소수점 정기 구매 (거래내역 소수단위매수, 최근 반년)
    GET /api/kosdaq-volume         -> {"fetchedAt", "total", "stocks": [{"code", "name", "price", "change", "value", "avg", "prevAvg", "days", "prevDays", "ratio"}]}  코스닥 전 종목 이번 주·전주 하루 평균 거래량 (10분 캐시, ?refresh=1이면 다시 조회)
    GET /api/kosdaq-sim?market=kosdaq&min=100&amount=300000&tp=0&sl=0&until=15:00&cost=0.23&spac=1&hold=30&buy=volume-open-up&sell=tp-sl
                       &start=&maxHold=0&maxBuys=0&alerts=0
                                   -> {"from", "to", "total", "days", "trades"}  매수 조건 모듈(buy)이 hold분 이어진 종목을 amount씩 사고 매도 모듈(sell)로 판 시뮬레이션
                                   (market=russell이면 russell.db·뉴욕 시간·달러, alerts=1이면 그 시장 알림 종목만 알림 시각부터, 저장된 기록만 읽음)
    GET /api/kosdaq-sim-modules    -> {"buy": [{"id", "label"}], "sell": [...]}  시뮬레이션 모듈 목록 (sim_modules.py)
    GET /api/kosdaq-intraday[?date=2026-10-16&at=10:30] -> {"date", "at", "dates", "slots", "prev", "week", "nextTick", "stocks": [{"code", "name", "price", "change", "vlm", "value", "prevVlm", "prevRatio", "weekVlm", "weekRatio"}]}
                                   장중 5분마다 저장한 코스닥 누적 거래량(kosdaq.db)으로 같은 시각의 전일·전주 대비 (KB 조회 없음)
    GET /api/alerts[?since=id]     -> {"alerts": [{"id", "market", "day", "at", "code", "name", "text", "d", "ratio", "price", "change", "chart"}], "ntfy", "markets"}
                                   코스닥·러셀 2000 증가율 top 30의 새 상승 시그널 알림 (alerts.py, alerts.db)
    GET /api/russell-volume, /api/russell-intraday -> 러셀 2000 (russell2000.csv 종목) 같은 모양. 같은 시각은 뉴욕 시간 30분마다 (russell.db)
    GET /api/transactions          -> {"start", "end", "fetchedAt", "rows": [...]}  최근 1달 거래내역 (SWQA2301 전체)
    내정보 API(holdings, dividends, recurring, transactions)는 하루(한국 날짜) 한 번만 KB를 조회해 kb.db(SQLite)에 저장하고, 같은 날은 저장된 값을 돌려줍니다.
    ?refresh=1 을 붙이면 지금 다시 조회해 오늘 값을 덮어씁니다.
    heatmap, quarters, holdings, dividends, recurring, transactions, chart(종목별), kosdaq-volume, russell-volume은 성공한 마지막 응답을 last_api/*.txt에 저장하고,
    ?cached=1 을 붙이면 KB를 부르지 않고 그 값을 바로 돌려줍니다(없으면 404). 화면은 이 값을 먼저 그리고 실제 응답으로 교체합니다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import ssl
import webbrowser
from dataclasses import asdict
from datetime import date, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import alerts
import chart
import monitor
import russell
import sim_modules
import snapshots
import volume
from daily_store import daily, last_file, read_last, save_last
from dividends import fetch_entries, fetch_recurring, fetch_transactions
from heatmap import holdings_history, query_heatmap, query_holdings, query_quarters, summarize
from kb_client import KBApiError, KBClient

HERE = Path(__file__).resolve().parent
PAGES = {"/": "home.html", "/heatmap": "heatmap.html", "/me": "me.html", "/chart": "chart.html", "/volume": "volume.html", "/trader": "trader.html", "/monitor": "monitor.html"}
SETTINGS_MAX_BYTES = 4096  # POST /api/settings 본문 크기 제한 (설정은 수십 바이트)
MONITOR_MAX_BYTES = 16384  # POST /api/monitor 본문 크기 제한 (30종목이 수 KB)
DOC = re.compile(r"/docs/[\w.-]+\.html")  # 구조도 (docs/*.html). '/'가 더 들어갈 수 없어 docs 폴더 밖은 못 엶


def query_dividends(client: KBClient, year: int, refresh: bool = False) -> dict:
    today = date.today()
    if not 2000 <= year <= today.year:
        raise ValueError(f"조회할 수 없는 연도입니다: {year}")
    start = f"{year}0101"
    end = today.strftime("%Y%m%d") if year == today.year else f"{year}1231"

    def fetch() -> dict:
        entries = fetch_entries(client, start, end)
        return {
            "year": year,
            "start": start,
            "end": end,
            "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "entries": [{**asdict(e), "month": e.month, "net": e.net} for e in entries],
        }
    return daily(f"dividends:{year}", fetch, refresh)  # 연도별로 하루 한 번만 KB 조회 (kb.db)


RECURRING_DAYS = 180  # 정기 구매 내역 조회 기간 (거래내역이 많으면 조회가 오래 걸려 반년으로 제한)


def query_recurring(client: KBClient, refresh: bool = False) -> dict:
    """KB 소수점 정기 구매 내역 (거래내역 소수단위매수, 최근 반년). 하루 한 번만 조회해 kb.db에 저장합니다.
    토스 OpenAPI에는 정기 구매·거래내역 API가 없어 토스 계좌는 포함하지 않습니다."""
    def fetch() -> dict:
        end = date.today()
        start = end - timedelta(days=RECURRING_DAYS)
        return {"start": start.isoformat(), "end": end.isoformat(),
                "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "plans": fetch_recurring(client, start.strftime("%Y%m%d"), end.strftime("%Y%m%d"))}
    return daily("recurring", fetch, refresh)


TRANSACTION_DAYS = 30  # 최근 거래내역 조회 기간


def query_transactions(client: KBClient, refresh: bool = False) -> dict:
    """KB 계좌 최근 1달 거래내역. 하루 한 번만 조회해 kb.db에 저장합니다. 토스 OpenAPI에는 거래내역 API가 없어 KB만."""
    def fetch() -> dict:
        end = date.today()
        start = end - timedelta(days=TRANSACTION_DAYS)
        return {"start": start.isoformat(), "end": end.isoformat(),
                "fetchedAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                "rows": fetch_transactions(client, start.strftime("%Y%m%d"), end.strftime("%Y%m%d"))}
    return daily("transactions", fetch, refresh)


def _flag(q: dict, name: str) -> bool:
    return q.get(name, [""])[0] in ("1", "true")


def _refresh(q: dict) -> bool:
    """?refresh=1 : 저장된 오늘 값을 건너뛰고 KB에서 다시 조회 (화면의 'KB에서 다시 조회' 버튼)."""
    return _flag(q, "refresh")


def _save_monitor(d: dict) -> dict:
    """POST /api/monitor : 목록을 저장하고 시세 저장 스레드를 깨워 새 종목을 곧바로 받게 함."""
    out = chart.save_monitor_items(d)
    monitor.changed()
    return out


def _simulate(q: dict) -> dict:
    """GET /api/kosdaq-sim : market=kosdaq|russell의 저장된 기록으로 Ticks.simulate (alerts=1이면 그 시장 알림 종목만 알림 시각부터)."""
    g = lambda k, d: q.get(k, [d])[0]
    market = g("market", "kosdaq")
    ticks = {"kosdaq": volume.KOSDAQ, "russell": russell.RUSSELL}.get(market)
    if not ticks:
        raise ValueError(f"없는 시장입니다: {market}")
    return ticks.simulate(float(g("min", "100")), float(g("amount", "300000")), float(g("tp", "0")), float(g("sl", "0")),
                          g("until", "15:00"), float(g("cost", "0.23")), g("spac", "1") != "0", int(g("hold", "30")),
                          g("buy", "volume-open-up"), g("sell", "tp-sl"), g("start", ""), int(g("maxHold", "0") or 0),
                          int(g("maxBuys", "0") or 0), alerts.first_times(market) if g("alerts", "0") == "1" else None)


def _trader_only_java() -> dict:
    """토스 자동매매는 실제 주문 로직을 한 곳에만 두려고 Spring·Java 서버(Trader.java)에서만 동작합니다."""
    raise ValueError("토스 자동매매는 Spring·Java 서버에서만 동작합니다 (spring-app/run.sh). 실제 주문 로직을 한 곳(Trader.java)에만 둡니다.")


def make_handler(client: KBClient):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urlparse(self.path)
            if url.path in PAGES:
                self._send(200, (HERE / PAGES[url.path]).read_bytes(), "text/html; charset=utf-8")
            elif url.path == "/api/dividends":
                self._api(lambda q: query_dividends(client, int(q.get("year", [date.today().year])[0]), _refresh(q)), parse_qs(url.query))
            elif url.path == "/api/heatmap":
                self._api(lambda q: query_heatmap(client, q.get("market", ["kr"])[0]), parse_qs(url.query))
            elif url.path == "/api/quarters":
                self._api(lambda q: query_quarters(client, q.get("market", ["kr"])[0]), parse_qs(url.query))
            elif url.path == "/api/holdings":
                self._api(lambda q: query_holdings(client, _refresh(q)), parse_qs(url.query))
            elif url.path == "/api/holdings-history":
                self._api(lambda q: snapshots.history(holdings_history()), {})
            elif url.path == "/api/recurring":
                self._api(lambda q: query_recurring(client, _refresh(q)), parse_qs(url.query))
            elif url.path == "/api/search":
                self._api(lambda q: chart.search(client, q.get("q", [""])[0]), parse_qs(url.query))
            elif url.path == "/api/chart":
                self._api(lambda q: chart.chart(client, q.get("market", ["kr"])[0], q.get("code", [""])[0], q.get("exchange", [""])[0], q.get("recent", ["1"])[0] != "0",
                                                int(q.get("minutes", ["0"])[0] or 0)), parse_qs(url.query))
            elif url.path == "/api/settings":
                self._api(lambda q: chart.settings(), {})
            elif url.path == "/api/monitor":
                self._api(lambda q: monitor.listing(), {})
            elif url.path == "/api/monitor-quotes":
                self._api(lambda q: monitor.refresh(client) if _refresh(q) else monitor.quotes(), parse_qs(url.query))
            elif url.path == "/api/recent":
                self._api(lambda q: {"items": chart.recent()}, {})
            elif url.path == "/api/kosdaq-volume":
                self._api(lambda q: volume.query(client, _refresh(q)), parse_qs(url.query))
            elif url.path == "/api/kosdaq-intraday":
                self._api(lambda q: volume.KOSDAQ.intraday(q.get("date", [""])[0], q.get("at", [""])[0]), parse_qs(url.query))
            elif url.path == "/api/kosdaq-sim-modules":
                self._api(lambda q: sim_modules.catalog(), {})
            elif url.path == "/api/kosdaq-sim":  # 저장된 기록으로 매수·매도 시뮬레이션 (market=kosdaq|russell, buy·sell = sim_modules 모듈 이름)
                self._api(_simulate, parse_qs(url.query))
            elif url.path == "/api/russell-volume":
                self._api(lambda q: russell.query(client, _refresh(q)), parse_qs(url.query))
            elif url.path == "/api/russell-intraday":
                self._api(lambda q: russell.RUSSELL.intraday(q.get("date", [""])[0], q.get("at", [""])[0]), parse_qs(url.query))
            elif url.path == "/api/trader":
                self._api(lambda q: _trader_only_java(), {})
            elif url.path == "/api/alerts":
                self._api(lambda q: alerts.recent(int(q.get("since", ["0"])[0] or 0)), parse_qs(url.query))
            elif url.path == "/api/transactions":
                self._api(lambda q: query_transactions(client, _refresh(q)), parse_qs(url.query))
            elif url.path in ("/signals.js", "/sw.js"):  # 보조지표·시그널 계산, 알림 서비스 워커
                self._send(200, (HERE / url.path.lstrip("/")).read_bytes(), "text/javascript; charset=utf-8")
            elif DOC.fullmatch(url.path) and (HERE / url.path.lstrip("/")).is_file():
                self._send(200, (HERE / url.path.lstrip("/")).read_bytes(), "text/html; charset=utf-8")
            else:
                self.send_error(404)

        def do_POST(self):
            path = urlparse(self.path).path
            if path == "/api/trader":
                return self._api(lambda q: _trader_only_java(), {})
            save = {"/api/settings": (chart.save_settings, SETTINGS_MAX_BYTES), "/api/monitor": (_save_monitor, MONITOR_MAX_BYTES)}.get(path)
            if not save:
                return self.send_error(404)
            size = int(self.headers.get("Content-Length") or 0)
            if not 0 < size <= save[1]:
                return self._send(400, '{"error": "요청 크기가 올바르지 않습니다"}'.encode("utf-8"), "application/json; charset=utf-8")
            body = self.rfile.read(size)
            self._api(lambda q: save[0](json.loads(body.decode("utf-8"))), {})

        def _api(self, handler, query: dict) -> None:
            last = last_file(urlparse(self.path).path.removeprefix("/api/"), {k: v[0] for k, v in query.items()})
            if last and _flag(query, "cached"):  # ?cached=1 : 마지막으로 성공한 응답 (없으면 404)
                body = read_last(last)
                self._send(200 if body else 404, (body or '{"error": "저장된 응답이 없습니다"}').encode("utf-8"), "application/json; charset=utf-8")
                return
            try:
                status, payload = 200, handler(query)
            except (ValueError, KBApiError) as e:
                status, payload = 400, {"error": str(e)}
            except Exception as e:  # 네트워크 오류 등
                status, payload = 502, {"error": f"{type(e).__name__}: {e}"}
            body = json.dumps(payload, ensure_ascii=False)
            if last and status == 200:
                try:
                    save_last(last, body)
                except OSError as e:  # 첫 로딩용 저장이 안 돼도 응답은 그대로 보냄
                    print(f"[{datetime.now():%H:%M:%S}] 마지막 응답 저장 실패 ({last}): {e}", flush=True)
            self._send(status, body.encode("utf-8"), "application/json; charset=utf-8")

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
    client = KBClient.from_env()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(client))
    snapshots.start(lambda: query_holdings(client, refresh=True), summarize)  # 4시간마다 보유 금액 기록 (MYSQL_HOST 있을 때만)
    volume.KOSDAQ.start(client)  # 장중 5분마다 코스닥 거래량 기록 (kosdaq.db)
    monitor.start(client)  # 시장 모니터 지수·추가 종목 시세를 5분마다 kb.db에 저장 (화면 첫 로딩용)
    alerts.start(client)  # 코스닥·러셀 2000 증가율 top 30의 새 상승 시그널을 장중 5분마다 확인 (ntfy·브라우저 알림)
    russell.RUSSELL.start(client)  # 뉴욕 장중 30분마다 러셀 2000 거래량 기록 (russell.db, russell2000.csv가 있을 때)
    # HTTPS: .env(또는 환경변수)에 SSL_CERT·SSL_KEY(PEM, 상대 경로는 이 폴더 기준)가 있으면 https로 엽니다 (deploy/make-cert.sh).
    # 브라우저 알림은 https 주소나 localhost에서만 됩니다. 핸드셰이크는 요청 스레드에서 (느린 연결이 서버 전체를 막지 않게)
    cert, key = os.environ.get("SSL_CERT", "").strip(), os.environ.get("SSL_KEY", "").strip()
    if cert and key:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(HERE / cert, HERE / key)
        server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    url = f"{'https' if cert and key else 'http'}://localhost:{args.port}"
    print(f"배당금 페이지 실행 중: {url}  (종료: Ctrl+C)", flush=True)
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
