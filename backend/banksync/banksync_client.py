"""HTTP calls shared by the mock and a future BankSync connection."""

import os
from pathlib import Path

import httpx
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[1] / ".env")


class BankSyncClient:
    def __init__(self):
        self.bank_id = os.environ["BANKSYNC_BANK_ID"]
        self.account_id = os.environ["BANKSYNC_ACCOUNT_ID"]
        self.http = httpx.Client(
            base_url=os.environ["BANKSYNC_BASE_URL"].rstrip("/"),
            headers={"X-API-Key": os.environ["BANKSYNC_API_KEY"]},
            timeout=15,
            # Ignore corporate proxy variables when calling the local mock.
            # Set BANKSYNC_TRUST_ENV=true if your live connection needs a proxy.
            trust_env=os.getenv("BANKSYNC_TRUST_ENV", "false").lower() == "true",
        )

    def close(self):
        self.http.close()

    def transactions(self, start: str, end: str) -> list[dict]:
        path = f"/v1/banks/{self.bank_id}/accounts/{self.account_id}/transactions"
        params = {"from": start, "to": end}
        records = []
        seen_cursors = set()
        while True:
            response = self.http.get(path, params=params)
            response.raise_for_status()
            body = response.json()
            if body.get("success") is not True:
                raise RuntimeError("BankSync returned an unsuccessful response")
            if body.get("meta", {}).get("partial"):
                raise RuntimeError("BankSync returned partial data")
            records.extend(body["data"])
            meta = body["meta"]
            cursor = meta.get("cursor")
            if meta.get("hasMore") is False or (not cursor and not meta.get("hasMore")):
                return records
            if not cursor or cursor in seen_cursors:
                raise RuntimeError("Missing or repeated pagination cursor")
            seen_cursors.add(cursor)
            params["cursor"] = cursor
