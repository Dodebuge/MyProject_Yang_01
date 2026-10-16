"""기술적 시그널 (signals.js와 같은 규칙·같은 결과). 서버가 시그널 알림(alerts.py)을 계산할 때 씁니다.

화면(차트·거래량)은 signals.js로 계산하고, 브라우저 없이 서버가 계산해야 하는 알림만 이 파일을 씁니다.
규칙을 바꾸면 signals.js, Signals.java도 같이 바꾸세요. 아래 자체 점검은 signals.js 점검과 같은 입력·같은 결과입니다.

마지막 날 기준. 교차는 최근 SIG_DAYS 거래일 안에 일어난 것만. dir: 1 매수 쪽(상승), -1 매도 쪽
"""

from __future__ import annotations

import math

MAS = (5, 20, 60, 120)
BB_N, BB_K = 20, 2
RSI_N = 14
MACD_FAST, MACD_SLOW, MACD_SIGNAL = 12, 26, 9
SIG_DAYS = 5


def sma(xs: list, n: int) -> list:
    """n일 단순이동평균. 앞쪽 n-1일은 None"""
    out, s = [], 0.0
    for i, v in enumerate(xs):
        s += v - (xs[i - n] if i >= n else 0)
        out.append(s / n if i >= n - 1 else None)
    return out


def ema(xs: list, n: int) -> list:
    """n일 지수이동평균. 앞쪽 None은 건너뛰고, 첫 값은 처음 n개의 단순평균으로 시작"""
    out = [None] * len(xs)
    start = next((i for i, v in enumerate(xs) if v is not None), -1)
    if start < 0 or start + n > len(xs):
        return out
    k = 2 / (n + 1)
    e = sum(xs[start:start + n]) / n
    out[start + n - 1] = e
    for i in range(start + n, len(xs)):
        e = xs[i] * k + e * (1 - k)
        out[i] = e
    return out


def rsi(xs: list, n: int) -> list:
    """RSI(n): 상승폭 평균 / (상승폭 평균 + 하락폭 평균) × 100. 와일더 방식 평균"""
    out = [None] * len(xs)
    up = down = 0.0
    for i in range(1, len(xs)):
        d = xs[i] - xs[i - 1]
        u, w = max(d, 0), max(-d, 0)
        if i <= n:
            up += u / n
            down += w / n
        else:
            up = (up * (n - 1) + u) / n
            down = (down * (n - 1) + w) / n
        if i >= n:
            out[i] = up / (up + down) * 100 if up + down else 50
    return out


def macd(xs: list, f: int, sl: int, sg: int) -> list:
    """[(macd, signal)] (앞쪽은 None). MACD = 빠른 EMA - 느린 EMA, 시그널 = MACD의 EMA"""
    a, b = ema(xs, f), ema(xs, sl)
    m = [None if v is None or w is None else v - w for v, w in zip(a, b)]
    s = ema(m, sg)
    return [None if v is None else (v, s[i]) for i, v in enumerate(m)]


def bollinger(xs: list, n: int, k: float) -> list:
    """[(중심, 상단, 하단)]. 표준편차는 모집단"""
    out = []
    for i, m in enumerate(sma(xs, n)):
        if m is None:
            out.append(None)
            continue
        sd = math.sqrt(sum((v - m) ** 2 for v in xs[i - n + 1:i + 1]) / n)
        out.append((m, m + k * sd, m - k * sd))
    return out


def signals_of(cs: list[dict]) -> list[dict]:
    """일봉 [{"d", "c"}](오래된 날부터) -> [{"dir", "text", "d"?}] (signals.js signalsOf와 같은 순서·문구)"""
    xs = [c["c"] for c in cs]
    last, out = len(xs) - 1, []
    if len(xs) < 30:
        return out

    def cross(a: list, b: list) -> dict | None:  # a가 b를 위로 뚫으면 1, 아래로 뚫으면 -1 (가장 최근 교차)
        for i in range(last, max(0, last - SIG_DAYS), -1):
            if None in (a[i], b[i], a[i - 1], b[i - 1]):
                return None
            if a[i - 1] <= b[i - 1] and a[i] > b[i]:
                return {"dir": 1, "d": cs[i]["d"]}
            if a[i - 1] >= b[i - 1] and a[i] < b[i]:
                return {"dir": -1, "d": cs[i]["d"]}
        return None

    ma = {n: sma(xs, n) for n in MAS}
    for f, sl in ((5, 20), (20, 60)):
        c = cross(ma[f], ma[sl])
        if c:
            out.append({**c, "text": f"{f}·{sl}일선 {'골든' if c['dir'] > 0 else '데드'}크로스"})
    m = macd(xs, MACD_FAST, MACD_SLOW, MACD_SIGNAL)
    mc = cross([v[0] if v else None for v in m], [v[1] if v else None for v in m])
    if mc:
        out.append({**mc, "text": f"MACD 시그널 {'상향' if mc['dir'] > 0 else '하향'} 돌파"})
    r = rsi(xs, RSI_N)[last]
    if r is not None and r >= 70:
        out.append({"dir": -1, "text": f"RSI 과매수 {_round(r)}"})
    elif r is not None and r <= 30:
        out.append({"dir": 1, "text": f"RSI 과매도 {_round(r)}"})
    b = bollinger(xs, BB_N, BB_K)[last]
    if b and xs[last] > b[1]:
        out.append({"dir": -1, "text": "볼린저 상단 돌파 (과열)"})
    elif b and xs[last] < b[2]:
        out.append({"dir": 1, "text": "볼린저 하단 이탈 (침체)"})
    a5, a20, a60, a120 = (ma[n][last] for n in MAS)
    if a120 is not None and a5 > a20 > a60 > a120:
        out.append({"dir": 1, "text": "이평선 정배열"})
    elif a120 is not None and a5 < a20 < a60 < a120:
        out.append({"dir": -1, "text": "이평선 역배열"})
    return out


def _round(x: float) -> int:
    """JS Math.round와 같게 (.5는 올림). Python round는 짝수 쪽으로 반올림해서 다름"""
    return math.floor(x + 0.5)


if __name__ == "__main__":
    # 자체 점검: signals.js 점검과 같은 입력·같은 결과
    mk = lambda closes: [{"d": str(20260101 + i), "c": c} for i, c in enumerate(closes)]
    texts = lambda closes: [("+" if g["dir"] > 0 else "-") + g["text"] for g in signals_of(mk(closes))]
    up = [200 - i * 0.5 for i in range(150)] + [140, 150, 165]
    assert texts(up) == ["+5·20일선 골든크로스", "-RSI 과매수 87", "-볼린저 상단 돌파 (과열)"], texts(up)
    rise = [100 + i for i in range(200)]
    assert "+이평선 정배열" in texts(rise) and any(t.startswith("-RSI 과매수") for t in texts(rise))
    assert not any("크로스" in t for t in texts(rise))
    fall = [300 - i for i in range(200)]
    assert "-이평선 역배열" in texts(fall) and any(t.startswith("+RSI 과매도") for t in texts(fall))
    down = [100 + i * 0.5 for i in range(150)] + [160, 150, 135]
    assert any(t.startswith("-5·20일선 데드크로스") for t in texts(down))
    assert signals_of(mk([1, 2, 3])) == []
    # RSI: StockCharts 예시 (원본 4자리 데이터)
    xs = [44.3389, 44.0902, 44.1497, 43.6124, 44.3278, 44.8264, 45.0955, 45.4245, 45.8433, 46.0826, 45.8931, 46.0328, 45.6140,
          46.2820, 46.2820, 46.0028, 46.0328, 46.4116, 46.2222]
    assert [f"{v:.2f}" for v in rsi(xs, 14)[14:]] == ["70.53", "66.32", "66.55", "69.41", "66.36"]
    print("ok")
