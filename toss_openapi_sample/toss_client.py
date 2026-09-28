"""토스증권 OpenAPI 클라이언트. kb_client.KBClient와 같은 모양으로 씁니다.

KB와 다른 점
- 인증: POST /oauth2/token (form-urlencoded, client_id/client_secret), 헤더는 "Authorization: Bearer <token>"
- 호출: TR 코드 대신 REST 경로 (GET /api/v1/prices?symbols=005930 등), 응답은 {"result": ...}
- 오류: 업무 오류도 HTTP 4xx로 오고 본문이 {"error": {"code", "message", "requestId"}} -> TossApiError
- 계좌·자산·주문 API는 X-Tossinvest-Account: {accountSeq} 헤더가 필요 (GET /api/v1/accounts로 확인)
- 숫자는 "72000", "0.0125" 같은 문자열로 옵니다 (KB처럼 고정길이 공백 정리는 필요 없음)
- 토큰은 클라이언트당 1개만 유효합니다. 다른 프로세스가 새로 발급하면 이전 토큰은 token-revoked가 되므로 한 번 재발급 후 재시도합니다.
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

DEFAULT_BASE_URL = "https://openapi.tossinvest.com"
_TOKEN_ERRORS = {"invalid-token", "expired-token", "token-revoked"}


class TossApiError(RuntimeError):
    """HTTP 4xx/5xx 응답의 error envelope."""

    def __init__(self, status: int, code: str, message: str, request_id: str, response: dict):
        super().__init__(f"[{status}] {code} {message} (requestId={request_id})")
        self.status = status
        self.code = code
        self.message = message
        self.request_id = request_id
        self.response = response


@dataclass
class TossClient:
    client_id: str
    client_secret: str
    base_url: str = DEFAULT_BASE_URL
    account_seq: str = ""  # 계좌·주문 API용. 비어 있으면 첫 계좌를 씁니다.
    timeout: float = 10.0
    _token: str = field(default="", repr=False)
    _token_expires_at: float = field(default=0.0, repr=False)
    _session: requests.Session = field(default_factory=requests.Session, repr=False)

    @classmethod
    def from_env(cls) -> "TossClient":
        client_id = os.environ.get("TOSS_OPENAPI_CLIENT_ID", "")
        client_secret = os.environ.get("TOSS_OPENAPI_CLIENT_SECRET", "")
        if not client_id or not client_secret:
            raise RuntimeError("TOSS_OPENAPI_CLIENT_ID / TOSS_OPENAPI_CLIENT_SECRET 환경변수(.env)를 설정하세요.")
        return cls(
            client_id=client_id,
            client_secret=client_secret,
            base_url=os.environ.get("TOSS_OPENAPI_BASE_URL", DEFAULT_BASE_URL).rstrip("/"),
            account_seq=os.environ.get("TOSS_OPENAPI_ACCOUNT_SEQ", ""),
        )

    # ------------------------------------------------------------------ 인증

    @property
    def access_token(self) -> str:
        if not self._token or time.time() >= self._token_expires_at - 60:
            self._issue_token()
        return self._token

    def _issue_token(self) -> None:
        data = self._request("POST", "/oauth2/token", data={
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self.client_secret,
        })
        self._token = data["access_token"]
        self._token_expires_at = time.time() + int(data.get("expires_in", 86400))

    # ------------------------------------------------------------------ API 호출

    def get(self, path: str, params: dict[str, Any] | None = None, account: bool = False) -> Any:
        """GET 호출 후 result를 돌려줍니다. account=True면 X-Tossinvest-Account 헤더를 붙입니다."""
        return self.call("GET", path, params=params, account=account)

    def call(self, method: str, path: str, params: dict | None = None, json: dict | None = None,
             account: bool = False) -> Any:
        for retry in (True, False):
            headers = {"Authorization": f"Bearer {self.access_token}"}
            if account:
                headers["X-Tossinvest-Account"] = str(self.account_seq or self._first_account_seq())
            try:
                return self._request(method, path, headers=headers, params=params, json=json).get("result")
            except TossApiError as e:
                if not (retry and e.code in _TOKEN_ERRORS):
                    raise
                self._token = ""  # 만료·무효화된 토큰: 새로 받아 한 번만 다시 시도

    def _first_account_seq(self) -> str:
        accounts = self.get("/api/v1/accounts")
        if not accounts:
            raise RuntimeError("조회된 계좌가 없습니다.")
        self.account_seq = str(accounts[0]["accountSeq"])
        return self.account_seq

    def _request(self, method: str, path: str, **kwargs) -> dict:
        # 429는 Retry-After만큼, 5xx는 0.5초, 1초, 2초 쉬고 세 번까지 다시 시도합니다.
        for attempt in range(4):
            response = self._session.request(method, f"{self.base_url}{path}", timeout=self.timeout, **kwargs)
            if attempt == 3 or (response.status_code != 429 and response.status_code < 500):
                break
            wait = response.headers.get("Retry-After")
            time.sleep(float(wait) if wait else 0.5 * 2 ** attempt)
        if response.ok:
            return response.json()
        try:
            body = response.json()
        except ValueError:
            response.raise_for_status()
        err = body.get("error") or {}
        if isinstance(err, str):  # /oauth2/token은 OAuth 표준 형식: {"error": "invalid_client", "error_description": ...}
            err = {"code": err, "message": body.get("error_description", "")}
        raise TossApiError(response.status_code, err.get("code", ""), err.get("message", ""),
                           err.get("requestId") or response.headers.get("X-Request-Id", ""), body)
