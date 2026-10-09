"""Fetch the fixed sample period and print a small summary."""

import argparse
from decimal import Decimal

from banksync.banksync_client import BankSyncClient


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--from", dest="start", default="2026-07-01")
    parser.add_argument("--to", dest="end", default="2026-09-30")
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
