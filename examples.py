"""KB증권 OpenAPI 시세 조회 예제.

실행:
    python examples.py            # 운영 서버 호출 (.env에 appKey/appSecret 필요)
    python examples.py --offline  # all_kbstock_sample_V2 샘플 응답으로 실행 (인증 불필요)

TR별 입력 필드와 코드값은 kbsecurities/kb-openapi의 samples.generated.json(inputSpec) 기준입니다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from kb_client import KBApiError, KBClient, parse_response

SAMPLE_DIR = Path(__file__).resolve().parent.parent / "all_kbstock_sample_V2"


class OfflineClient:
    """네트워크 없이 샘플 JSON의 output을 응답으로 돌려주는 대체 클라이언트."""

    def call(self, tr_code: str, data_body: dict | None = None) -> dict:
        sample = json.loads((SAMPLE_DIR / f"{tr_code}.json").read_text(encoding="utf-8-sig"))
        return parse_response(tr_code, sample["output"])


# ---------------------------------------------------------------------- TR 래퍼


def stock_price(client, code: str, exchange: str = "1") -> dict:
    """IVU10140 주식현재가. exchange: 0=통합, 1=KRX, 2=NXT"""
    return client.call("IVU10140", {"excg_clsf": exchange, "shrt_cd": code})


def stock_orderbook(client, code: str, after_hours: bool = False) -> dict:
    """IVU10070 주식호가 (10단계)."""
    return client.call("IVU10070", {"is_cd": code, "ovtm_mkt_clsf": "1" if after_hours else "0"})


def daily_chart(client, code: str, market: str = "0", count: int = 10) -> list[dict]:
    """IVS11560 통합차트 일봉. market: 0=KOSPI, 1=KOSDAQ"""
    out = client.call("IVS11560", {
        "info_ccd": "1",        # 1=원주가, 2=수정주가
        "mkt_clsf": market,
        "chrt_clsf": "D",       # D=일 W=주 M=월 Y=년 B=분 T=틱
        "minute_tck_indx": "",
        "is_cd": code,
        "inq_clsf": "2",        # 1=날짜로 조회, 2=데이터수로 조회
        "strt_dy": "",
        "inq_cnt": str(count),
    })
    return out.get("out2", [])


def volume_top(client, segment: str = "1", exchange: str = "1") -> list[dict]:
    """IVU10280 거래량상위. segment: 1=전체, 2=KOSPI, 3=KOSDAQ"""
    return client.call("IVU10280", {"excg_clsf": exchange, "mkt_clsf": segment}).get("out2", [])


def global_price(client, symbol: str, exchange: str = "NAS") -> dict:
    """GSS10030 해외주식 현재가. exchange: NAS, NYS, AMX, HKS, SHS, SZS, TSE, HSX, HNX"""
    return client.call("GSS10030", {"krx_cd": exchange, "is_cd": symbol})


def exchange_rates(client) -> list[dict]:
    """IVA60190 환율종합."""
    return client.call("IVA60190", {}).get("out2", [])


# ---------------------------------------------------------------------- 출력

SIGN = {"1": "↑", "2": "▲", "3": " ", "4": "↓", "5": "▼"}  # 전일대비구분: 1상한 2상승 3보합 4하한 5하락


def show_price(p: dict) -> None:
    print(f"\n■ {p['is_nm']} 현재가")
    print(f"  현재가 {p['now_prc']:>10,}원  {SIGN.get(p['bdy_cmpr_ccd'], '')}{p['bdy_cmpr']:,} ({p['up_dwn_r_p2']:+.2f}%)")
    print(f"  시가 {p['opn_prc']:,}  고가 {p['hgh_prc']:,}  저가 {p['lw_prc']:,}  거래량 {p['acml_vlm']:,}")
    print(f"  250일 최고 {p['dy250_max_prc']:,} ({p['dy250_max_prc_dt']})  최저 {p['dy250_min_prc']:,} ({p['dy250_min_prc_dt']})")


def show_orderbook(ob: dict, depth: int = 5) -> None:
    print(f"\n■ 호가 (현재가 {ob['now_prc']:,})")
    print(f"  {'매도잔량':>10} {'호가':>10} {'매수잔량':>10}")
    for n in range(depth, 0, -1):
        print(f"  {ob[f's_pstn_s{n}_aprc_q']:>10,} {ob[f's{n}_aprc']:>10,} {'':>10}")
    for n in range(1, depth + 1):
        print(f"  {'':>10} {ob[f'b{n}_aprc']:>10,} {ob[f'b_pstn_b{n}_aprc_q']:>10,}")
    print(f"  총잔량 매도 {ob['s_askprc_tl_q']:,} / 매수 {ob['b_askprc_tl_q']:,}")


def show_chart(rows: list[dict]) -> None:
    print("\n■ 일봉")
    print(f"  {'일자':<10}{'시가':>10}{'고가':>10}{'저가':>10}{'종가':>10}{'거래량':>12}")
    for r in rows:
        print(f"  {r['dt']:<10}{r['opn_prc_p2']:>10,.0f}{r['hgh_prc_p2']:>10,.0f}"
              f"{r['lw_prc_p2']:>10,.0f}{r['cls_prc_p2']:>10,.0f}{r['vlm']:>12,}")


def show_ranking(rows: list[dict], n: int = 10) -> None:
    print("\n■ 거래량 상위")
    for r in rows[:n]:
        print(f"  {r['rnk']:>2}. {r['is_nm']:<14} {r['now_prc']:>9,}원 {r['up_dwn_r_p2']:+6.2f}%  거래량 {r['acml_vlm']:>12,}")


def show_global(g: dict) -> None:
    print(f"\n■ {g['is_cd']} ({g['krx_cd']}) 현재가")
    print(f"  ${g['now_prc_p4']:,.2f}  {SIGN.get(g['bdy_cmpr_ccd'], '')}{g['bdy_cmpr_p4']:,.2f} ({g['up_dwn_r_p2']:+.2f}%)"
          f"  ≈ {g['now_prc_krw_p2']:,.0f}원")
    print(f"  52주 최고 {g['wk52_max_prc_p4']:,.2f}  최저 {g['wk52_min_prc_p4']:,.2f}  PER {g['per_p4']:.2f}")


def show_rates(rows: list[dict], n: int = 5) -> None:
    print("\n■ 환율")
    for r in rows[:n]:
        print(f"  {r['crncy_cd_nm']:<10} {r['cls_prc_p4']:>12,.2f}  {r['bdy_cmpr_r_p2']:+.2f}%")


# ---------------------------------------------------------------------- 실행

if __name__ == "__main__":
    client = OfflineClient() if "--offline" in sys.argv else KBClient.from_env()

    try:
        show_price(stock_price(client, "005930"))
        show_orderbook(stock_orderbook(client, "005930"))
        show_chart(daily_chart(client, "005930", count=5))
        show_ranking(volume_top(client))
        show_global(global_price(client, "NVDA"))
        show_rates(exchange_rates(client))
    except KBApiError as e:
        # HTTP 200이어도 processFlag가 "B"면 업무 오류입니다 (예: 조회할 자료 없음, 입력값 오류)
        print(f"\nAPI 오류: {e}")
