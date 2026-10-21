"""코스닥 매수 시뮬레이션 모듈. volume.py Ticks.simulate(엔진)가 이름(buy=, sell=)으로 골라 끼웁니다.
새 규칙은 BUY·SELL에 한 줄 추가하면 모니터 화면 선택 목록에 바로 나옵니다 (Java 서버는 SimModules.java에 같은 이름으로 추가).

엔진이 하는 일 (모듈과 상관없이 같음): 5분 기록을 날짜·시각 순서로 읽고, 시가 = 그날 첫 기록, 매수 조건이 hold분 이어지면
종목당 금액만큼 하루 한 번 사고, 매도 모듈이 이유를 안 주면 그날 마지막 기록에 팔고, 비용을 빼서 손익을 냅니다.
이미 산 종목·상한가 근처(+29.5% 이상)·스팩(옵션)·금액보다 비싼 종목·전일 기록이 없는 종목은 엔진이 먼저 뺍니다.
현재가와 등락률이 서로 안 맞는 기록(KB가 가끔 자릿수가 빠진 현재가를 줌)도 엔진이 그날 데이터에서 뺍니다.

매수 조건: (s, o) -> bool
    s = 그 시각 종목 {"price": 현재가, "change": 전일 대비 %, "ratio": 전일 같은 시각 대비 누적 거래량 증가 %, "open": 시가}
    o = 화면 옵션 {"min": 거래량 기준 %, "tp": 익절 %, "sl": 손절 %}
매도: (tr, px, o) -> 이유 문자열 또는 None (None이면 계속 들고 있음)
    tr = 그 거래 (buyPrice 등, 고점 같은 상태를 남겨도 됨), px = 그 시각 현재가 (0이 아님)
"""
from __future__ import annotations


def _tp_sl(tr: dict, px: float, o: dict) -> str | None:
    if o["tp"] and px >= tr["buyPrice"] * (1 + o["tp"] / 100):
        return "익절"
    if o["sl"] and px <= tr["buyPrice"] * (1 - o["sl"] / 100):
        return "손절"
    return None


def _trailing(tr: dict, px: float, o: dict) -> str | None:
    tr["peak"] = max(tr.get("peak", tr["buyPrice"]), px)  # 산 뒤 기록 중 최고 현재가
    if o["tp"] and px >= tr["buyPrice"] * (1 + o["tp"] / 100):
        return "익절"
    if o["sl"] and px <= tr["peak"] * (1 - o["sl"] / 100):
        return "고점 대비 손절"
    return None


BUY = {  # 이름: (화면 표시, 규칙)
    "volume-open-up": ("거래량 증가 + 시가 위 + 상승", lambda s, o: s["ratio"] >= o["min"] and s["price"] > s["open"] and s["change"] > 0),
    "volume-up": ("거래량 증가 + 상승 (시가 무시)", lambda s, o: s["ratio"] >= o["min"] and s["change"] > 0),
    # 급등 초입: 익절 5%를 노리는 규칙. 거래량 기준(min)은 무시하고 500% 고정. 10-20·10-21 기록(유지 30분, 익절 5·손절 5)에서 -0.86%·+1.43%
    "surge": ("급등 초입: 거래량 500%↑ + 등락 5~15%", lambda s, o: s["ratio"] >= 500 and 5 <= s["change"] < 15),
}
SELL = {
    "tp-sl": ("익절·손절, 아니면 마지막 기록", _tp_sl),
    "trailing": ("익절 · 고점 대비 손절(추적), 아니면 마지막 기록", _trailing),
}


def pick(modules: dict, name: str):
    if name not in modules:
        raise ValueError(f"없는 시뮬레이션 모듈입니다: {name}")
    return modules[name][1]


def catalog() -> dict:
    """GET /api/kosdaq-sim-modules -> {"buy": [{id, label}], "sell": [...]} (화면 선택 목록, 첫 항목이 기본)"""
    return {k: [{"id": i, "label": label} for i, (label, _) in m.items()] for k, m in (("buy", BUY), ("sell", SELL))}
