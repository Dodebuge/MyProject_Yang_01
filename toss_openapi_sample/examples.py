"""토스증권 OpenAPI 시세 조회 예제. examples.py(KB)와 같은 항목을 토스 API로 조회합니다.

실행:
    python examples.py   # .env에 TOSS_OPENAPI_CLIENT_ID/SECRET 필요, 호출 IP를 토스 WTS 허용 IP에 등록해야 함

KB TR -> 토스 API
    IVU10140 주식현재가   -> /stocks(종목명) + /prices(현재가) + /candles 1d(시고저·거래량·전일종가)
    IVU10070 주식호가     -> /orderbook
    IVS11560 일봉         -> /candles?interval=1d
    IVU10280 거래량상위   -> /rankings?type=MARKET_TRADING_VOLUME (종목명은 /stocks로 채움)
    GSS10030 해외현재가   -> 국내와 같은 API에 티커(AAPL)만 넣으면 됨 (거래소 코드 불필요)
    IVA60190 환율종합     -> /exchange-rate (USD<->KRW만 제공)
    SSQM2952 등 잔고      -> /holdings (+ X-Tossinvest-Account)
"""

from __future__ import annotations

from toss_client import TossApiError, TossClient

# ---------------------------------------------------------------------- API 래퍼


def stock_names(client, symbols: list[str]) -> dict[str, str]:
    return {s["symbol"]: s["name"] for s in client.get("/api/v1/stocks", {"symbols": ",".join(symbols)})}


def daily_chart(client, symbol: str, count: int = 10) -> list[dict]:
    """일봉, 최신순. adjusted=True면 수정주가."""
    return client.get("/api/v1/candles", {"symbol": symbol, "interval": "1d", "count": count})["candles"]


def stock_price(client, symbol: str) -> dict:
    """현재가 + 오늘 시고저·거래량 + 전일 종가. 국내(005930)·미국(AAPL) 모두 같은 함수."""
    price = client.get("/api/v1/prices", {"symbols": symbol})[0]
    today, prev = daily_chart(client, symbol, count=2)
    return {**today, **price, "name": stock_names(client, [symbol])[symbol], "prevClose": prev["closePrice"]}


def stock_orderbook(client, symbol: str) -> dict:
    return client.get("/api/v1/orderbook", {"symbol": symbol})


def volume_top(client, market: str = "KR", count: int = 10) -> list[dict]:
    """거래량 상위. market: KR, US"""
    rows = client.get("/api/v1/rankings", {"type": "MARKET_TRADING_VOLUME", "marketCountry": market,
                                           "duration": "realtime", "count": count})["rankings"]
    names = stock_names(client, [r["symbol"] for r in rows])
    return [{**r, "name": names.get(r["symbol"], r["symbol"])} for r in rows]


def usd_krw(client) -> dict:
    return client.get("/api/v1/exchange-rate", {"baseCurrency": "USD", "quoteCurrency": "KRW"})


def holdings(client) -> dict:
    return client.get("/api/v1/holdings", account=True)


# ---------------------------------------------------------------------- 출력


def _money(value: str, currency: str) -> str:
    return f"${float(value):,.2f}" if currency == "USD" else f"{float(value):,.0f}원"


def show_price(p: dict, rate: dict | None = None) -> None:
    last, prev, cur = float(p["lastPrice"]), float(p["prevClose"]), p["currency"]
    arrow = "▲" if last > prev else "▼" if last < prev else " "
    print(f"\n■ {p['name']} ({p['symbol']}) 현재가")
    krw = f"  ≈ {last * float(rate['rate']):,.0f}원" if cur == "USD" and rate else ""
    print(f"  {_money(p['lastPrice'], cur)}  {arrow}{abs(last - prev):,.2f} ({(last / prev - 1) * 100:+.2f}%){krw}")
    print(f"  시가 {_money(p['openPrice'], cur)}  고가 {_money(p['highPrice'], cur)}  저가 {_money(p['lowPrice'], cur)}"
          f"  거래량 {float(p['volume']):,.0f}")


def show_orderbook(ob: dict, depth: int = 5) -> None:
    asks = sorted(ob["asks"], key=lambda r: float(r["price"]))[:depth]
    bids = sorted(ob["bids"], key=lambda r: -float(r["price"]))[:depth]
    print("\n■ 호가")
    print(f"  {'매도잔량':>10} {'호가':>10} {'매수잔량':>10}")
    for r in reversed(asks):
        print(f"  {float(r['volume']):>10,.0f} {float(r['price']):>10,g} {'':>10}")
    for r in bids:
        print(f"  {'':>10} {float(r['price']):>10,g} {float(r['volume']):>10,.0f}")


def show_chart(rows: list[dict]) -> None:
    print("\n■ 일봉")
    print(f"  {'일자':<10}{'시가':>10}{'고가':>10}{'저가':>10}{'종가':>10}{'거래량':>12}")
    for r in rows:
        print(f"  {r['timestamp'][:10]:<10}{float(r['openPrice']):>10,.0f}{float(r['highPrice']):>10,.0f}"
              f"{float(r['lowPrice']):>10,.0f}{float(r['closePrice']):>10,.0f}{float(r['volume']):>12,.0f}")


def show_ranking(rows: list[dict]) -> None:
    print("\n■ 거래량 상위")
    for r in rows:
        p = r["price"]
        print(f"  {r['rank']:>2}. {r['name']:<14} {_money(p['lastPrice'], r['currency']):>12} "
              f"{float(p['changeRate']) * 100:+6.2f}%  거래량 {float(r['tradingVolume']):>12,.0f}")


def show_rate(r: dict) -> None:
    print(f"\n■ 환율\n  USD/KRW {float(r['rate']):,.2f} (기준 {float(r['midRate']):,.2f}, {r['rateChangeType']})")


def show_holdings(h: dict) -> None:
    print("\n■ 보유 주식")
    for i in h["items"]:
        print(f"  {i['name']:<14} {float(i['quantity']):>8,.4g}주 {_money(i['marketValue']['amount'], i['currency']):>14}"
              f"  수익률 {float(i['profitLoss']['rate']) * 100:+.2f}%")
    mv = h["marketValue"]["amount"]  # 해당 통화 보유가 없으면 krw/usd가 null
    print(f"  평가금액 {float(mv['krw'] or 0):,.0f}원 + ${float(mv['usd'] or 0):,.2f}"
          f"  수익률 {float(h['profitLoss']['rate'] or 0) * 100:+.2f}%")


# ---------------------------------------------------------------------- 실행

if __name__ == "__main__":
    client = TossClient.from_env()
    try:
        rate = usd_krw(client)
        show_price(stock_price(client, "005930"))
        show_orderbook(stock_orderbook(client, "005930"))
        show_chart(daily_chart(client, "005930", count=5))
        show_ranking(volume_top(client))
        show_price(stock_price(client, "NVDA"), rate)
        show_rate(rate)
        show_holdings(holdings(client))
    except TossApiError as e:
        # 업무 오류는 HTTP 4xx + {"error": {...}} 로 옵니다 (예: 403 허용 IP 미등록, 400 invalid-request)
        print(f"\nAPI 오류: {e}")
