"""Fetch a sample period and print a small summary."""

import argparse
import json
from decimal import Decimal
from pathlib import Path

from banksync.banksync_client import BankSyncClient

DATASET = Path(__file__).resolve().parents[1] / "mock_banksync/data/transactions.json"


def default_range() -> tuple[str, str]:
    """Span of the generated mock dataset, so stale dates are never hard-coded."""
    rows = json.loads(DATASET.read_text(encoding="utf-8"))
    dates = [row["date"] for row in rows]
    return min(dates), max(dates)


def main():
    first, last = default_range()
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="start", default=first,
                        help=f"Start date, YYYY-MM-DD (default: {first}, dataset start)")
    parser.add_argument("--to", dest="end", default=last,
                        help=f"End date, YYYY-MM-DD (default: {last}, dataset end)")
    args = parser.parse_args()

    client = BankSyncClient()
    try:
        records = client.transactions(args.start, args.end)
    finally:
        client.close()

    posted = [row for row in records if not row["pending"]]
    credits = sum((Decimal(str(row["creditAmount"])) for row in posted), Decimal("0"))
    debits = sum((Decimal(str(row["debitAmount"])) for row in posted), Decimal("0"))
    print(f"Fetched {len(records)} transactions ({len(records) - len(posted)} pending)")
    print(f"Posted credits: AUD {credits:,.2f}")
    print(f"Posted debits:  AUD {debits:,.2f}")
    print("First five records:")
    for row in records[:5]:
        print(f"  {row['date']}  {row['amount']:>9.2f}  {row['description']}")


if __name__ == "__main__":
    main()
