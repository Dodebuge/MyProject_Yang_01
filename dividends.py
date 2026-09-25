"""올해 받은 배당금 월별 조회.

계좌 거래내역(SWQA2301)에서 배당 관련 거래만 골라 월별로 합산합니다.
국내 배당(ETF 분배금 포함)과 해외 배당, 세금이 모두 거래내역에 남기 때문에 이 TR 하나로 집계합니다.

    적요(smry_nm)              처리
    배당금 입금                 배당 (국내: 원화, 원천징수 세금 포함 / 해외: 외화 세전 금액)
    해외원천세 출금             해외에서 뗀 세금 (외화)
    배당세금추징 출금           국내에서 추가로 뗀 세금 (원화, 예: 미국 외 해외배당 15.4%)
    해외원천세 환급 입금        이미 뗀 해외 세금의 환급 (외화, 세금에서 차감)

해외 금액은 거래 당일 환율(exch_r)로 원화 환산합니다.

실행:
    python dividends.py                    # 올해 1월 1일 ~ 오늘, 월별 합계 + 종목별 합계
    python dividends.py --month 7          # 7월 배당 상세 내역
    python dividends.py --year 2025        # 다른 연도
    python dividends.py --csv dividends.csv  # 상세 내역을 CSV로 저장 (엑셀에서 열림)
"""

from __future__ import annotations

import argparse
import csv
import sys
import unicodedata
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import date

from kb_client import KBClient

DIVIDEND = "배당금 입금"
FOREIGN_TAX = "해외원천세 출금"
DOMESTIC_TAX = "배당세금추징 출금"
TAX_REFUND = "해외원천세 환급 입금"
RELEVANT = {DIVIDEND, FOREIGN_TAX, DOMESTIC_TAX, TAX_REFUND}


@dataclass
class Entry:
    """배당 관련 거래 1건. 금액은 원화 기준이며, 해외는 외화 금액도 함께 둡니다."""

    date: str          # YYYYMMDD
    name: str
    code: str          # 표준종목코드 (예: US2546871060, KR7472150008)
    kind: str          # 적요
    currency: str      # "KRW" 또는 "USD" 등
    gross: int         # 세전 배당 (원)
    tax: int           # 세금 (원). 환급은 음수
    fx_amount: float   # 외화 금액 (해외만)
    fx_rate: float     # 적용 환율 (해외만)

    @property
    def month(self) -> int:
        return int(self.date[4:6])

    @property
    def net(self) -> int:
        return self.gross - self.tax


def _won(value) -> int:
    """'1,073,000', 1073000, '' 모두 원 단위 정수로."""
    if isinstance(value, (int, float)):
        return round(value)
    s = str(value).replace(",", "").strip()
    return round(float(s)) if s else 0


def to_entry(t: dict) -> Entry | None:
    kind = t["smry_nm"]
    if kind not in RELEVANT:
        return None
    currency = t.get("crncy_clsf_nm") or "KRW"
    fx_amount = float(t.get("fcrncy_amt") or 0)
    fx_rate = float(t.get("exch_r") or 0)
    krw_of_fx = round(fx_amount * fx_rate)
    gross = tax = 0

    if kind == DIVIDEND:
        if currency == "KRW":
            gross, tax = _won(t.get("dl_amt")), _won(t.get("tx"))
        else:
            gross = krw_of_fx
    elif kind == FOREIGN_TAX:
        tax = krw_of_fx
    elif kind == TAX_REFUND:
        tax = -krw_of_fx
    elif kind == DOMESTIC_TAX:
        currency = "KRW"
        tax = _won(t.get("tx")) or _won(t.get("dl_amt"))

    return Entry(
        date=t["dl_dt"],
        name=t.get("is_nm", ""),
        code=t.get("stnd_is_cd", ""),
        kind=kind,
        currency=currency,
        gross=gross,
        tax=tax,
        fx_amount=fx_amount if currency != "KRW" else 0.0,
        fx_rate=fx_rate if currency != "KRW" else 0.0,
    )


def fetch_entries(client: KBClient, start: str, end: str) -> list[Entry]:
    """거래내역 전체를 넘기며 배당 관련 거래만 모읍니다. (거래가 많으면 수십 초 걸립니다)"""
    entries, count = [], 0
    for t in client.call_pages("SWQA2301", {"strt_dt": start, "end_dt": end, "is_no": "", "srt_clsf": ""}):
        count += 1
        if count % 60 == 0 and sys.stderr.isatty():
            print(f"\r거래내역 조회 중... {count}건", end="", file=sys.stderr, flush=True)
        if (e := to_entry(t)) is not None:
            entries.append(e)
    print(f"\r거래내역 {count}건 중 배당 관련 {len(entries)}건    ", file=sys.stderr)
    return sorted(entries, key=lambda e: e.date)


# ---------------------------------------------------------------------- 출력


def _width(s: str) -> int:
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in s)


def _pad(s: str, n: int, right: bool = False) -> str:
    """한글 폭(2칸)을 고려한 정렬."""
    if _width(s) > n:
        while _width(s) > n - 1:
            s = s[:-1]
        s += "…"
    gap = " " * (n - _width(s))
    return gap + s if right else s + gap


def _table(headers: list[str], rows: list[list[str]], widths: list[int], left: int = 1) -> None:
    """첫 left개 열은 왼쪽, 나머지는 오른쪽 정렬."""
    line = lambda cells: "  ".join(_pad(c, w, i >= left) for i, (c, w) in enumerate(zip(cells, widths)))
    print(line(headers))
    print("  ".join("─" * w for w in widths))
    for r in rows:
        print(line(r))


def print_monthly(entries: list[Entry], year: int) -> None:
    by_month: dict[int, list[Entry]] = defaultdict(list)
    for e in entries:
        by_month[e.month].append(e)
    last = max(by_month) if by_month else 0

    rows = []
    for m in range(1, last + 1):
        es = by_month.get(m, [])
        gross, tax = sum(e.gross for e in es), sum(e.tax for e in es)
        kr = sum(e.gross for e in es if e.kind == DIVIDEND and e.currency == "KRW")
        usd = sum(e.fx_amount for e in es if e.kind == DIVIDEND and e.currency == "USD")
        count = sum(e.kind == DIVIDEND for e in es)
        rows.append([f"{m}월", f"{count}", f"{kr:,}", f"${usd:,.2f}", f"{gross:,}", f"{tax:,}", f"{gross - tax:,}"])

    gross, tax = sum(e.gross for e in entries), sum(e.tax for e in entries)
    kr = sum(e.gross for e in entries if e.kind == DIVIDEND and e.currency == "KRW")
    usd = sum(e.fx_amount for e in entries if e.kind == DIVIDEND and e.currency == "USD")
    count = sum(e.kind == DIVIDEND for e in entries)
    rows.append(["합계", f"{count}", f"{kr:,}", f"${usd:,.2f}", f"{gross:,}", f"{tax:,}", f"{gross - tax:,}"])

    print(f"\n■ {year}년 월별 배당 (원)")
    _table(["월", "건수", "국내 세전", "해외 세전", "세전 합계", "세금", "실수령"], rows, [6, 4, 12, 11, 12, 10, 12])
    print("  · 해외 세전은 외화 금액, 세전 합계·세금·실수령은 거래일 환율로 원화 환산한 값입니다.")
    print("  · 세금에는 해외 원천세, 국내 원천징수·추징세가 포함되고, 환급은 차감됩니다.")


def print_by_stock(entries: list[Entry], top: int = 15) -> None:
    by_stock: dict[str, list[Entry]] = defaultdict(list)
    for e in entries:
        if e.kind == DIVIDEND:
            by_stock[e.name].append(e)
    rows = sorted(by_stock.items(), key=lambda kv: -sum(e.gross for e in kv[1]))
    print("\n■ 종목별 배당 (세전, 원)")
    _table(
        ["종목", "횟수", "세전", "지급월"],
        [[n, f"{len(es)}", f"{sum(e.gross for e in es):,}", ",".join(sorted({str(e.month) for e in es}, key=int))]
         for n, es in rows[:top]],
        [34, 4, 12, 22],
    )
    if len(rows) > top:
        print(f"  … 외 {len(rows) - top}종목")


def print_detail(entries: list[Entry], month: int) -> None:
    es = [e for e in entries if e.month == month]
    print(f"\n■ {month}월 배당 상세")
    if not es:
        print("  내역이 없습니다.")
        return
    _table(
        ["일자", "종목", "구분", "외화", "환율", "세전(원)", "세금(원)"],
        [[f"{e.date[4:6]}/{e.date[6:]}", e.name, e.kind.replace(" 입금", "").replace(" 출금", ""),
          f"{e.fx_amount:,.2f} {e.currency}" if e.currency != "KRW" else "",
          f"{e.fx_rate:,.1f}" if e.fx_rate else "", f"{e.gross:,}" if e.gross else "", f"{e.tax:,}" if e.tax else ""]
         for e in es],
        [5, 30, 14, 12, 8, 10, 9],
        left=3,
    )
    gross, tax = sum(e.gross for e in es), sum(e.tax for e in es)
    print(f"  세전 {gross:,}원 − 세금 {tax:,}원 = 실수령 {gross - tax:,}원")


def save_csv(entries: list[Entry], path: str) -> None:
    fields = ["date", "name", "code", "kind", "currency", "fx_amount", "fx_rate", "gross", "tax", "net"]
    # utf-8-sig: 엑셀에서 한글이 깨지지 않도록 BOM 포함
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for e in entries:
            w.writerow({**asdict(e), "net": e.net})
    print(f"\nCSV 저장: {path}")


if __name__ == "__main__":
    today = date.today()
    parser = argparse.ArgumentParser(description="올해 받은 배당금 월별 조회")
    parser.add_argument("--year", type=int, default=today.year)
    parser.add_argument("--month", type=int, help="해당 월의 상세 내역 표시 (1-12)")
    parser.add_argument("--csv", help="상세 내역 CSV 저장 경로")
    args = parser.parse_args()

    start = f"{args.year}0101"
    end = today.strftime("%Y%m%d") if args.year == today.year else f"{args.year}1231"
    entries = fetch_entries(KBClient.from_env(), start, end)

    print_monthly(entries, args.year)
    if args.month:
        print_detail(entries, args.month)
    else:
        print_by_stock(entries)
    if args.csv:
        save_csv(entries, args.csv)
