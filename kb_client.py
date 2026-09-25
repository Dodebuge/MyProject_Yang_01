"""KB증권 OpenAPI(B2C) 클라이언트.

kbsecurities/kb-openapi 저장소의 example/python 패턴을 따르되, 아래를 보강했습니다.
- 토큰 캐싱: expires_in(기본 86400초)까지 재사용하고 만료 60초 전에 재발급
- 5xx 응답은 0.5초, 1초 쉬고 두 번까지 재시도
- 업무 오류 판별: HTTP 200이어도 dataHeader.processFlag가 "B"면 KBApiError 발생
  (all_kbstock_sample_V2 샘플 기준: 정상 "A", 주문수량 오류·자료없음 등 "B")
- 고정길이 전문 값 정리: "   368500" -> "368500", "0000000039024.00" -> 39024.0
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass, field
from typing import Any

import requests

try:
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

DEFAULT_BASE_URL = "https://developer.kbsec.com:32484"


class KBApiError(RuntimeError):
    """processFlag가 정상(A)이 아닌 응답."""

    def __init__(self, tr_code: str, code: str, message: str, response: dict):
        super().__init__(f"[{tr_code}] {code} {message}")
        self.tr_code = tr_code
        self.code = code
        self.message = message
        self.response = response


@dataclass
class KBClient:
    app_key: str
    app_secret: str
    base_url: str = DEFAULT_BASE_URL
    # TR 호출 시 dataHeader.ipAddr/macAddr가 비어 있으면 서버가 9999 오류를 냅니다.
    ip_addr: str = "127.0.0.1"
    mac_addr: str = "00:00:00:00:00:00"
    timeout: float = 10.0
    _token: str = field(default="", repr=False)
    _token_expires_at: float = field(default=0.0, repr=False)
    _session: requests.Session = field(default_factory=requests.Session, repr=False)

    @classmethod
    def from_env(cls) -> "KBClient":
        app_key = os.environ.get("KB_OPENAPI_APP_KEY", "")
        app_secret = os.environ.get("KB_OPENAPI_APP_SECRET", "")
        if not app_key or not app_secret:
            raise RuntimeError("KB_OPENAPI_APP_KEY / KB_OPENAPI_APP_SECRET 환경변수(.env)를 설정하세요.")
        base_url = os.environ.get("KB_OPENAPI_BASE_URL", DEFAULT_BASE_URL).rstrip("/")
        return cls(
            app_key=app_key,
            app_secret=app_secret,
            base_url=base_url,
            ip_addr=os.environ.get("KB_OPENAPI_IP_ADDR", "127.0.0.1"),
            mac_addr=os.environ.get("KB_OPENAPI_MAC_ADDR", "00:00:00:00:00:00"),
        )

    @property
    def _data_header(self) -> dict[str, str]:
        return {"ipAddr": self.ip_addr, "macAddr": self.mac_addr}

    # ------------------------------------------------------------------ 인증

    @property
    def access_token(self) -> str:
        if not self._token or time.time() >= self._token_expires_at - 60:
            self._issue_token()
        return self._token

    def _issue_token(self) -> None:
        body = {
            "dataHeader": self._data_header,
            "dataBody": {
                "appKey": self.app_key,
                "appSecret": self.app_secret,
                "grantType": "client_credentials",
            },
        }
        data = self._post("/oauth2/token", {"Content-Type": "application/json"}, body)
        data_body = data.get("dataBody", {})
        token = data_body.get("access_token")
        if not token:
            raise RuntimeError(f"토큰 발급 실패: {data.get('dataHeader')}")
        self._token = token
        self._token_expires_at = time.time() + int(data_body.get("expires_in", 86400))

    # ------------------------------------------------------------------ TR 호출

    def call(self, tr_code: str, data_body: dict[str, Any] | None = None, raw: bool = False) -> dict:
        """TR을 호출하고 정리된 dataBody를 돌려줍니다.

        엔드포인트는 /api/v1/{TR코드 소문자} 규칙을 따릅니다 (예: IVU10140 -> /api/v1/ivu10140).
        raw=True면 값 정리 없이 원본 응답 전체를 돌려줍니다.
        """
        headers = {
            "Content-Type": "application/json",
            "appKey": self.app_key,
            "Authorization": f"bearer {self.access_token}",
        }
        body = {"dataHeader": self._data_header, "dataBody": data_body or {}}
        data = self._post(f"/api/v1/{tr_code.lower()}", headers, body)
        return data if raw else parse_response(tr_code, data)

    def call_pages(self, tr_code: str, data_body: dict[str, Any], record: str = "Record1", max_pages: int = 1000):
        """연속조회 TR을 nxt_key로 끝까지 넘기며 레코드를 하나씩 돌려줍니다.

        응답의 nxt_key가 비어 있으면 마지막 페이지입니다.
        """
        key, seen = "", set()
        for _ in range(max_pages):
            out = self.call(tr_code, {**data_body, "nxt_key": key})
            yield from out.get(record, [])
            key = str(out.get("nxt_key", "")).strip()
            if not key or key in seen:
                return
            seen.add(key)
        raise RuntimeError(f"[{tr_code}] {max_pages}페이지를 넘었습니다. 조회 기간을 줄이세요.")

    def _post(self, path: str, headers: dict, body: dict) -> dict:
        # 호출이 몰리면 KB 서버가 가끔 500을 돌려줍니다. 5xx는 잠시 쉬었다가 두 번까지 다시 시도합니다.
        for attempt in range(3):
            response = self._session.post(f"{self.base_url}{path}", headers=headers, json=body, timeout=self.timeout)
            if response.status_code < 500 or attempt == 2:
                break
            time.sleep(0.5 * (attempt + 1))
        response.raise_for_status()
        return response.json()


# ---------------------------------------------------------------------- 응답 처리


def parse_response(tr_code: str, data: dict) -> dict:
    """processFlag를 확인하고 정리된 dataBody를 돌려줍니다. 정상(A)이 아니면 KBApiError."""
    header = data.get("dataHeader", {})
    out = data.get("dataBody") or {}
    if header.get("processFlag") != "A":
        message = out.get("o_msg") or out.get("msg") or header.get("processMessage", "")
        raise KBApiError(tr_code, header.get("processCode", ""), message.strip(), data)
    return clean(out)



# 코드·날짜·시각·구분값처럼 숫자 모양이어도 문자열로 남겨야 하는 필드
_KEEP_STR_KEYS = {"dt", "tm", "wd", "lngth", "clsfP", "o_clsf"}
_KEEP_STR_SUFFIXES = ("_cd", "_no", "_dt", "_dy", "_tm", "_ccd", "_clsf", "_f", "_id", "_sq", "RecordSize", "_lngth")


def clean(value: Any, key: str = "") -> Any:
    """고정길이 전문 값을 정리합니다.

    - 모든 문자열의 앞뒤 공백 제거
    - 금액·수량·가격("0000000039024.00", "-00000000000000050", "   368500")은 숫자로 변환
    - 코드·날짜·시각·구분 필드(is_cd, dt, ordr_no, bdy_cmpr_ccd 등)는 문자열 유지
    """
    if isinstance(value, dict):
        return {k: clean(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v, key) for v in value]
    if not isinstance(value, str):
        return value
    s = value.strip()
    if key in _KEEP_STR_KEYS or key.endswith(_KEEP_STR_SUFFIXES) or key.startswith("is_"):
        return s
    try:
        return float(s) if "." in s else int(s)
    except ValueError:
        return s
