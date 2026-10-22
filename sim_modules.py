"""코스닥 매수 시뮬레이션 모듈. volume.py Ticks.simulate(엔진)가 이름(buy=, sell=)으로 골라 끼웁니다.
매수 조건은 설정(sim_buy_rules.json, 없으면 DEFAULT_BUY)의 조건 목록이라 모니터 화면에서 추가·수정합니다 (Java 서버 SimModules.java와 같은 파일·같은 규칙).
매도는 코드 모듈(SELL)이라 새 매도는 SELL에 한 줄 추가합니다 (Java는 SimModules.java에 같은 이름으로).

엔진이 하는 일 (모듈과 상관없이 같음): 5분 기록을 날짜·시각 순서로 읽고, 시가 = 그날 첫 기록, 매수 조건이 hold분 이어지면
종목당 금액만큼 하루 한 번 사고, 매도 모듈이 이유를 안 주면 그날 마지막 기록에 팔고, 비용을 빼서 손익을 냅니다.
이미 산 종목·상한가 근처(+29.5% 이상)·스팩(옵션)·금액보다 비싼 종목·전일 기록이 없는 종목은 엔진이 먼저 뺍니다.
현재가와 등락률이 서로 안 맞는 기록(KB가 가끔 자릿수가 빠진 현재가를 줌)도 엔진이 그날 데이터에서 뺍니다.

매수 조건 규칙: {"id", "label", "all": [{"left": 값, "op": ">"|">="|"<"|"<=", "right": 숫자 | 값 | "min"}, ...]} (모두 만족하면 매수 조건)
    값(FIELDS) = 그 시각 종목 price(현재가), change(전일 대비 %), ratio(전일 같은 시각 대비 누적 거래량 증가 %), open(시가),
                 value(누적 거래대금), vwap(거래량가중평균가 = 누적 거래대금 ÷ 누적 거래량). "min" = 화면의 거래량 기준 %
매도: (tr, px, o) -> 이유 문자열 또는 None (None이면 계속 들고 있음)
    tr = 그 거래 (buyPrice 등, 고점 같은 상태를 남겨도 됨), px = 그 시각 현재가 (0이 아님), o = {"min", "tp", "sl"}
"""
from __future__ import annotations

import json
import math
import operator
import os
import re
from pathlib import Path

RULES_FILE = Path(__file__).resolve().parent / "sim_buy_rules.json"
FIELDS = {"price": "현재가", "change": "등락률 %", "ratio": "거래량 증가율 %", "open": "시가", "vwap": "VWAP", "value": "누적 거래대금"}
OPS = {">": operator.gt, ">=": operator.ge, "<": operator.lt, "<=": operator.le}
MAX_RULES, MAX_CONDS = 30, 10
_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,39}")

c = lambda left, op, right: {"left": left, "op": op, "right": right}
DEFAULT_BUY = [  # 첫 항목이 화면 기본이 아님 (화면은 surge가 있으면 surge)
    {"id": "volume-open-up", "label": "거래량 증가 + 시가 위 + 상승", "all": [c("ratio", ">=", "min"), c("price", ">", "open"), c("change", ">", 0)]},
    {"id": "volume-up", "label": "거래량 증가 + 상승 (시가 무시)", "all": [c("ratio", ">=", "min"), c("change", ">", 0)]},
    # 급등 초입: 익절 5%를 노리는 규칙. 거래량 기준(min)은 무시하고 500% 고정. 10-20·10-21 기록(유지 30분, 익절 5·손절 5)에서 -0.86%·+1.43%
    {"id": "surge", "label": "급등 초입: 거래량 500%↑ + 등락 5~15%", "all": [c("ratio", ">=", 500), c("change", ">=", 5), c("change", "<", 15)]},
    # VWAP(당일 평균 단가) 위 = 매수 우위로 보는 규칙. 10-20~10-22 알림 종목(유지 30분, 익절 5·손절 5)에서 surge-vwap +1.12%(3건), vwap-up -3.65%
    {"id": "surge-vwap", "label": "급등 초입 + VWAP 위",
     "all": [c("ratio", ">=", 500), c("change", ">=", 5), c("change", "<", 15), c("price", ">", "vwap")]},
    {"id": "vwap-up", "label": "거래량 증가 + VWAP 위 + 상승", "all": [c("ratio", ">=", "min"), c("price", ">", "vwap"), c("change", ">", 0)]},
]


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


SELL = {  # 이름: (화면 표시, 규칙)
    "tp-sl": ("익절·손절, 아니면 마지막 기록", _tp_sl),
    "trailing": ("익절 · 고점 대비 손절(추적), 아니면 마지막 기록", _trailing),
}


def valid_rules(rules) -> list[dict]:
    """매수 조건 규칙 목록 검사. 잘못되면 ValueError (화면에 그대로 보임)."""
    if not isinstance(rules, list) or not 1 <= len(rules) <= MAX_RULES:
        raise ValueError(f"매수 조건은 1~{MAX_RULES}개입니다.")
    out, ids = [], set()
    for r in rules:
        rid = str(r.get("id") or "") if isinstance(r, dict) else ""
        if not _ID.fullmatch(rid) or rid in ids:
            raise ValueError(f"매수 조건 이름(id)은 영문 소문자·숫자·-로 40자까지, 서로 달라야 합니다: {rid}")
        ids.add(rid)
        label = " ".join(str(r.get("label") or "").split())[:60] or rid
        conds = r.get("all")
        if not isinstance(conds, list) or not 1 <= len(conds) <= MAX_CONDS:
            raise ValueError(f"{label}: 조건은 1~{MAX_CONDS}개입니다.")
        cs = []
        for x in conds:
            left, op, right = (x.get("left"), x.get("op"), x.get("right")) if isinstance(x, dict) else (None, None, None)
            num = isinstance(right, (int, float)) and not isinstance(right, bool) and math.isfinite(right)
            if left not in FIELDS or op not in OPS or not (num or right in FIELDS or right == "min"):
                raise ValueError(f"{label}: 조건이 올바르지 않습니다: {left} {op} {right}")
            cs.append({"left": left, "op": op, "right": right})
        out.append({"id": rid, "label": label, "all": cs})
    return out


def buy_rules() -> list[dict]:
    """저장된 매수 조건 (파일이 없거나 깨졌으면 DEFAULT_BUY)."""
    try:
        return valid_rules(json.loads(RULES_FILE.read_text(encoding="utf-8")))
    except (FileNotFoundError, ValueError, AttributeError):
        return [dict(r) for r in DEFAULT_BUY]


def save_buy_rules(d: dict) -> dict:
    """POST /api/kosdaq-sim-modules {"buy": [...]} : 검사해 통째로 저장하고 catalog()를 돌려줍니다."""
    if not isinstance(d, dict):
        raise ValueError('매수 조건은 {"buy": [...]} 이어야 합니다.')
    rules = valid_rules(d.get("buy"))
    tmp = RULES_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(rules, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, RULES_FILE)  # 반쯤 쓴 파일을 읽지 않게 통째로 교체
    return catalog()


def buy(name: str):
    """이름의 매수 조건 -> (s, o) -> bool. s = 그 시각 종목 값(FIELDS), o = {"min", "tp", "sl"}."""
    rule = next((r for r in buy_rules() if r["id"] == name), None)
    if rule is None:
        raise ValueError(f"없는 매수 조건입니다: {name}")
    conds = [(x["left"], OPS[x["op"]], x["right"]) for x in rule["all"]]
    val = lambda s, o, v: o["min"] if v == "min" else s[v] if isinstance(v, str) else v
    return lambda s, o: all(op(s[left], val(s, o, right)) for left, op, right in conds)


def sell(name: str):
    if name not in SELL:
        raise ValueError(f"없는 매도 모듈입니다: {name}")
    return SELL[name][1]


def catalog() -> dict:
    """GET /api/kosdaq-sim-modules -> {"buy": [{id, label, all}], "sell": [{id, label}], "fields": {값: 이름}, "ops": [...]} (화면 선택 목록·편집)"""
    return {"buy": buy_rules(), "sell": [{"id": i, "label": label} for i, (label, _) in SELL.items()],
            "fields": FIELDS, "ops": list(OPS)}


if __name__ == "__main__":
    import tempfile
    RULES_FILE = Path(tempfile.mkdtemp()) / "rules.json"
    assert [r["id"] for r in catalog()["buy"]] == [r["id"] for r in DEFAULT_BUY]
    s = {"price": 1100, "change": 6.0, "ratio": 600, "open": 1000, "value": 1, "vwap": 1050}
    assert buy("surge")(s, {"min": 100}) and buy("surge-vwap")(s, {"min": 100}) and not buy("surge")({**s, "change": 15}, {"min": 100})
    assert buy("volume-up")(s, {"min": 600}) and not buy("volume-up")(s, {"min": 601})  # min = 화면 거래량 기준
    out = save_buy_rules({"buy": [{"id": "my-1", "label": " 내  조건 ", "all": [{"left": "price", "op": "<=", "right": "vwap"}]}]})
    assert out["buy"] == [{"id": "my-1", "label": "내 조건", "all": [{"left": "price", "op": "<=", "right": "vwap"}]}] == buy_rules()
    assert not buy("my-1")(s, {"min": 0}) and buy("my-1")({**s, "price": 1050}, {"min": 0})
    for bad in [{"buy": []}, {"buy": [{"id": "A", "all": [c("price", ">", 1)]}]}, {"buy": [{"id": "a", "all": []}]},
                {"buy": [{"id": "a", "all": [c("price", "==", 1)]}]}, {"buy": [{"id": "a", "all": [c("evil", ">", 1)]}]},
                {"buy": [{"id": "a", "all": [c("price", ">", "x")]}]}, {"buy": [{"id": "a", "all": [c("price", ">", True)]}]},
                {"buy": [{"id": "a", "all": [c("price", ">", 1)]}, {"id": "a", "all": [c("price", ">", 1)]}]}]:
        try:
            save_buy_rules(bad)
            raise AssertionError(bad)
        except ValueError:
            pass
    RULES_FILE.write_text("{broken", encoding="utf-8")
    assert [r["id"] for r in buy_rules()] == [r["id"] for r in DEFAULT_BUY]
    print("ok")
