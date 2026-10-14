"""종목 검색 + 일봉 차트 (/chart 화면).

검색: 국내는 전체 상장 종목(IVS10920, 약 4,300개)의 이름·종목번호로, 미국은 티커로 찾습니다.
     KB OpenAPI에 미국 종목 목록 TR이 없어, 티커 모양(영문)이면 heatmap.US_STOCKS에 없어도 후보로 내고 차트 조회 때 거래소를 찾습니다.
차트: 국내 IVS11560, 미국 GSC10060 일봉 최근 약 1년(250거래일).
최근 검색: 차트를 연 종목을 recent_searches.txt에 최신순으로 RECENT_MAX개까지 저장합니다.
     한 줄에 한 종목 "market<탭>code<탭>exchange<탭>name". 직접 고치거나 지워도 됩니다 (형식이 맞지 않는 줄은 무시).
차트 설정: 기간과 이평선·볼린저밴드 체크 상태를 chart_settings.json에 저장합니다 (GET/POST /api/settings).
"""

from __future__ import annotations

import json
import os
import re
import threading
import time
from pathlib import Path

from heatmap import US_STOCKS, _valid_day
from kb_client import KBApiError, KBClient

LIST_CACHE_SECONDS = 3600  # 상장 종목 목록은 하루에 거의 안 바뀜
CHART_DAYS = "250"
US_EXCHANGES = ("NAS", "NYS", "AMX")  # 나스닥, 뉴욕, 아멕스(NYSE Arca ETF 포함)
TICKER = re.compile(r"[A-Z][A-Z.]{0,5}")

RECENT_FILE = Path(__file__).resolve().parent / "recent_searches.txt"
RECENT_MAX = 10  # 최근 검색 종목 수

_kr: tuple[float, list[dict]] = (0.0, [])
_recent_lock = threading.Lock()

# 차트 설정 (기간, 이평선·볼린저밴드 표시): 화면에서 바꿀 때마다 chart_settings.json에 저장해 다음에도 그대로 엽니다.
SETTINGS_FILE = Path(__file__).resolve().parent / "chart_settings.json"
DEFAULT_SETTINGS = {"period": "6M", "ma5": True, "ma20": True, "ma60": True, "ma120": False, "bb": False, "rsi": False, "macd": False}
PERIODS = ("1M", "3M", "6M", "1Y")


def settings() -> dict:
    """저장된 설정. 파일이 없거나 깨졌거나 모르는 값은 기본값으로."""
    try:
        saved = json.loads(SETTINGS_FILE.read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError):
        saved = {}
    return _valid_settings(DEFAULT_SETTINGS, saved if isinstance(saved, dict) else {})


def _valid_settings(base: dict, d: dict) -> dict:
    """base에 d의 올바른 값만 덮어씁니다."""
    out = dict(base)
    if d.get("period") in PERIODS:
        out["period"] = d["period"]
    for k in ("ma5", "ma20", "ma60", "ma120", "bb", "rsi", "macd"):
        if isinstance(d.get(k), bool):
            out[k] = d[k]
    return out


def save_settings(d: dict) -> dict:
    """화면에서 보낸 설정을 검사해 저장하고, 저장된 값을 돌려줍니다 (모르는 키는 버림)."""
    if not isinstance(d, dict):
        raise ValueError("설정은 JSON 객체여야 합니다.")
    out = _valid_settings(settings(), d)
    tmp = SETTINGS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, SETTINGS_FILE)  # 반쯤 쓴 파일을 읽지 않게 통째로 교체
    return out


def recent() -> list[dict]:
    """최근 검색 종목 (최신순, 최대 RECENT_MAX개)."""
    try:
        lines = RECENT_FILE.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        return []
    out = []
    for line in lines:
        p = line.split("\t")
        if len(p) == 4 and p[0] in ("kr", "us") and p[1]:
            out.append({"market": p[0], "code": p[1], "exchange": p[2], "name": p[3]})
    return out[:RECENT_MAX]


def remember(s: dict) -> None:
    """차트를 연 종목을 맨 앞에 넣고(같은 종목은 앞으로 옮김) RECENT_MAX개만 남깁니다."""
    with _recent_lock:
        items = [s] + [r for r in recent() if (r["market"], r["code"]) != (s["market"], s["code"])]
        flat = lambda v: re.sub(r"[\t\r\n]", " ", str(v))
        RECENT_FILE.write_text("".join(
            "\t".join(flat(r[k]) for k in ("market", "code", "exchange", "name")) + "\n" for r in items[:RECENT_MAX]), encoding="utf-8")


def kr_stocks(client: KBClient) -> list[dict]:
    global _kr
    if time.time() - _kr[0] > LIST_CACHE_SECONDS:
        rows = client.call("IVS10920", {"inq_cnt": "9999"}).get("out", [])
        _kr = (time.time(), [{"market": "kr", "code": r["is_cd"], "name": r["is_nm"]} for r in rows])
    return _kr[1]


def search(client: KBClient, q: str, limit: int = 20) -> dict:
    q = q.strip()
    if not q:
        return {"query": q, "results": []}
    lo, up = q.lower(), q.upper()
    kr = [s for s in kr_stocks(client) if lo in s["name"].lower() or s["code"].startswith(q)]
    kr.sort(key=lambda s: (s["name"].lower() != lo and s["code"] != q, not s["name"].lower().startswith(lo), len(s["name"])))
    us = [{"market": "us", "code": t, "name": t, "exchange": x} for t, x, _ in US_STOCKS if t.startswith(up)]
    us.sort(key=lambda s: (s["code"] != up, len(s["code"])))
    exact = bool(us) and us[0]["code"] == up
    if TICKER.fullmatch(up) and not exact and not any(s["name"].lower() == lo for s in kr):
        us.insert(0, {"market": "us", "code": up, "name": up, "exchange": ""})  # 목록 밖 티커: 차트 조회 때 거래소 탐색
        exact = not kr
    results = us[:5] + kr if exact else kr + us  # 티커가 정확히 맞으면 미국 먼저
    return {"query": q, "results": results[:limit]}


def _f(v) -> float:
    return float(str(v).replace(",", "").strip() or 0)


def _candles(rows: list[dict], suffix: str, value_scale: float) -> list[dict]:
    out = []
    for r in rows:
        d = str(r.get("dt", ""))
        try:
            c = {"d": d, "o": _f(r[f"opn_prc_{suffix}"]), "h": _f(r[f"hgh_prc_{suffix}"]), "l": _f(r[f"lw_prc_{suffix}"]),
                 "c": _f(r[f"cls_prc_{suffix}"]), "v": _f(r.get("vlm")), "amt": _f(r.get("dl_tw_amt")) * value_scale}
        except (KeyError, ValueError):
            continue  # 필드가 밀린 행
        if _valid_day(d) and c["c"] > 0:
            out.append(c)
    return sorted({c["d"]: c for c in out}.values(), key=lambda c: c["d"])  # 같은 날 중복 제거, 오래된 날부터


def chart(client: KBClient, market: str, code: str, exchange: str = "") -> dict:
    code = code.strip()
    if market == "kr":
        if not re.fullmatch(r"[0-9A-Z]{6}", code):
            raise ValueError(f"국내 종목번호는 6자리입니다: {code}")
        m = client.call("IVM10050", {"is_cd": code})
        board = "KOSDAQ" if str(m.get("indx_nm") or "").startswith("코스닥") else "KOSPI"  # ETF는 indx_nm이 비어 KOSPI
        rows = client.call("IVS11560", {
            "info_ccd": "1", "mkt_clsf": "1" if board == "KOSDAQ" else "0", "chrt_clsf": "D", "minute_tck_indx": "",
            "is_cd": code, "inq_clsf": "2", "strt_dy": "", "inq_cnt": CHART_DAYS}).get("out2", [])
        name = next((s["name"] for s in kr_stocks(client) if s["code"] == code), code)
        out = {"market": "kr", "code": code, "name": name, "exchange": board, "currency": "KRW",
               "candles": _candles(rows, "p2", 10)}  # 국내 차트 거래대금은 10원 단위
        remember(out)
        return out
    if market != "us":
        raise ValueError(f"market은 kr 또는 us입니다: {market}")
    code = code.upper()
    if not TICKER.fullmatch(code):
        raise ValueError(f"티커가 올바르지 않습니다: {code}")
    known = {t: x for t, x, _ in US_STOCKS}
    exchanges = [exchange] if exchange in US_EXCHANGES else [known[code]] if code in known else list(US_EXCHANGES)
    for x in exchanges:
        try:
            out = client.call("GSC10060", {
                "chrt_clsf": "3", "mdfy_stk_prc_use_f": "", "is_cd": code, "clsf": "1",  # chrt_clsf 3 = 일봉
                "srch_strt_dy": time.strftime("%Y%m%d"), "rcrd_c": CHART_DAYS, "bndl": "", "krx_cd": x})
        except KBApiError:
            continue
        candles = _candles(out.get("out2", []), "p4", 1)
        if candles:
            name = str(out.get("hngl_is_nm") or "").strip() or str(out.get("eng_is_nm") or "").strip() or code
            out = {"market": "us", "code": code, "name": name, "exchange": x, "currency": "USD", "candles": candles}
            remember(out)
            return out
    raise ValueError(f"미국 거래소(나스닥·뉴욕·아멕스)에서 {code} 차트를 찾지 못했습니다.")


if __name__ == "__main__":
    # 자체 점검 (네트워크 불필요): 가짜 클라이언트로 검색 순서와 캔들 변환 확인
    class Fake:
        def call(self, tr, body):
            if tr == "IVS10920":
                return {"out": [{"is_cd": "005935", "is_nm": "삼성전자우"}, {"is_cd": "005930", "is_nm": "삼성전자"},
                                {"is_cd": "000660", "is_nm": "SK하이닉스"}]}
            raise AssertionError(tr)
    r = search(Fake(), "삼성전자")["results"]
    assert [s["code"] for s in r] == ["005930", "005935"], r  # 이름이 정확히 같은 종목이 먼저
    assert search(Fake(), "0006")["results"][0]["code"] == "000660"
    r = search(Fake(), "aap")["results"]
    assert r[0] == {"market": "us", "code": "AAP", "name": "AAP", "exchange": ""} and any(s["code"] == "AAPL" for s in r), r
    assert search(Fake(), "nvda")["results"][0]["exchange"] == "NAS"  # 목록에 있는 티커는 거래소 포함
    r = search(Fake(), "sk")["results"]
    assert r[0]["code"] == "000660" and r[1]["code"] == "SK", r  # 국내 이름이 맞으면 국내 먼저, 티커 추정은 뒤에
    cs = _candles([{"dt": "20260102", "opn_prc_p2": 10, "hgh_prc_p2": 12, "lw_prc_p2": 9, "cls_prc_p2": 11, "vlm": 5, "dl_tw_amt": 3},
                   {"dt": "20260101", "opn_prc_p2": 9, "hgh_prc_p2": 9, "lw_prc_p2": 9, "cls_prc_p2": 9, "vlm": 1, "dl_tw_amt": 1},
                   {"dt": "5803-3893", "opn_prc_p2": 1, "hgh_prc_p2": 1, "lw_prc_p2": 1, "cls_prc_p2": 1}], "p2", 10)
    assert [c["d"] for c in cs] == ["20260101", "20260102"] and cs[1]["amt"] == 30, cs
    # 최근 검색: 최신순, 중복은 앞으로, RECENT_MAX개 유지, 깨진 줄 무시
    import tempfile
    RECENT_FILE = Path(tempfile.mkdtemp()) / "recent.txt"
    assert recent() == []
    for i in range(RECENT_MAX + 3):
        remember({"market": "kr", "code": f"{i:06d}", "exchange": "KOSPI", "name": f"종목\t{i}"})
    remember({"market": "kr", "code": "000005", "exchange": "KOSPI", "name": "다시"})
    rs = recent()
    assert len(rs) == RECENT_MAX and rs[0] == {"market": "kr", "code": "000005", "exchange": "KOSPI", "name": "다시"}, rs
    assert [r["code"] for r in rs].count("000005") == 1 and rs[1]["name"] == "종목 12", rs
    RECENT_FILE.write_text("깨진 줄\nus\tAAPL\tNAS\t애플\n", encoding="utf-8")
    assert recent() == [{"market": "us", "code": "AAPL", "exchange": "NAS", "name": "애플"}]
    # 차트 설정: 없으면 기본값, 바꾼 키만 반영·유지, 모르는 키·잘못된 값은 버림, 깨진 파일은 기본값
    SETTINGS_FILE = RECENT_FILE.parent / "settings.json"
    assert settings() == DEFAULT_SETTINGS
    assert save_settings({"bb": True, "period": "1Y"})["bb"] is True
    assert save_settings({"rsi": True})["rsi"] is True and save_settings({"rsi": False})["rsi"] is False
    s = save_settings({"ma5": False, "period": "10Y", "evil": 1, "ma20": "yes"})
    assert s == {**DEFAULT_SETTINGS, "bb": True, "period": "1Y", "ma5": False} == settings(), s
    SETTINGS_FILE.write_text("{broken", encoding="utf-8")
    assert settings() == DEFAULT_SETTINGS
    print("ok")
