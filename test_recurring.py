"""정기 구매 요약 점검: python test_recurring.py  (네트워크 불필요)"""

from datetime import date

from dividends import summarize_recurring


def buy(day, name, usd):
    return {"smry_typ_cd": "A23", "dl_dt": day, "is_nm": name, "stnd_is_cd": name, "fcrncy_amt": usd}


rows = [
    {"smry_typ_cd": "A69", "dl_dt": "20260901", "exch_r": "1400"},
    {"smry_typ_cd": "A69", "dl_dt": "20260903", "exch_r": "1500"},
    {"smry_typ_cd": "A69", "dl_dt": "20260903", "exch_r": "1600"},  # 같은 날 두 번 환전 -> 평균 1550
    buy("20260901", "AAPL", 7), buy("20260902", "AAPL", 7), buy("20260903", "AAPL", 7),  # 매일
    buy("20260601", "DIS", 10), buy("20260608", "DIS", 10),                             # 매주
    {"smry_typ_cd": "314", "dl_dt": "20260902", "is_nm": "삼성전자"},                    # 소수점 매수 아님
]
aapl, dis = summarize_recurring(rows, date(2026, 9, 4))
assert (aapl["name"], aapl["freq"], aapl["daysAgo"], aapl["count"]) == ("AAPL", "매일", 1, 3), aapl
assert aapl["krw"] == 7 * 1400 * 2 + 7 * 1550, aapl  # 9/2는 환전이 없어 9/1 환율
assert aapl["usd30"] == 21
assert (dis["freq"], dis["usd30"]) == ("매주", 0), dis
print("ok")

# 최근 거래내역: 샘플 응답(../all_kbstock_sample_V2/SWQA2301.json) 행 변환
import json
from pathlib import Path

from dividends import to_transaction
from kb_client import clean

sample = Path(__file__).resolve().parent.parent / "all_kbstock_sample_V2" / "SWQA2301.json"
if sample.exists():
    rows = clean(json.loads(sample.read_text(encoding="utf-8"))["output"]["dataBody"])["Record1"]
    t = to_transaction(rows[1])
    assert (t["date"], t["kind"], t["amount"], t["balance"], t["currency"], t["qty"]) == ("20260622", "입금", 100_000_000, 101_000_000, "KRW", 0.0), t
    print("ok transactions")
