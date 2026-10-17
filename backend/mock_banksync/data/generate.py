#!/usr/bin/env python3
"""Deterministic generator for the BankSync mock dataset.

The committed ``transactions.json`` is *generated*, not hand-authored, so it
can be carefully crafted to trigger a positive "micro-win" in each of the six
prompt-chip recency tiers (This Week, This Pay Cycle, Last 30 Days, This
Quarter, 6 Months, YTD) — issue #2.

The whole timeline is anchored to ``--as-of`` (defaults to today): pay days,
the current week, and quarter boundaries are computed from it, so the snapshot
always contains fresh data for the short windows. When the demo window has
passed, refresh it with:

    uv run python mock_banksync/data/generate.py --as-of 2026-11-10

For the same ``--as-of`` and ``--seed`` the output is byte-for-byte
identical. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import random
from datetime import date, timedelta
from pathlib import Path

DEFAULT_OUT = Path(__file__).resolve().parent / "transactions.json"
PAGE_BUDGET = 25  # mock endpoint page size; dataset is expected to span many pages

CURRENCY = "AUD"
ACCOUNT_ID = "spending"
BANK_ID = "demo"

GROCER_MERCHANTS = [
    ("WOOLWORTHS", 45.0, 110.0),
    ("COLES", 40.0, 100.0),
    ("HARBOUR GROCER", 35.0, 85.0),
]
CAFE_MERCHANTS = [
    ("SQ *CAFE NEWTOWN", 8.0, 18.0),
    ("CORNER CAFE", 6.0, 14.0),
    ("BLUEBONIC COFFEE CO", 5.0, 12.0),
]
TAKEAWAY_MERCHANTS = [
    ("UBER *EATS", 22.0, 55.0),
    ("DOORDASH", 25.0, 60.0),
    ("ALDI DELIVERY", 18.0, 45.0),
]
TAKEAWAY_NAMES = {"UBER *EATS", "DOORDASH", "ALDI DELIVERY"}
FUEL_MERCHANTS = [
    ("SHELL FUEL", 55.0, 95.0),
    ("BP FUEL", 50.0, 90.0),
]


def shift_months(year: int, month: int, months: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + months
    return total // 12, total % 12 + 1


def quarter_of(day: date) -> tuple[int, int]:
    return day.year, (day.month - 1) // 3


def quarter_start(day: date) -> date:
    _, q = quarter_of(day)
    return date(day.year, q * 3 + 1, 1)


def iso_week_start(day: date) -> date:
    return day - timedelta(days=day.weekday())


def make(
    day: date,
    description: str,
    amount: float,
    category: str,
    merchant: str | None = None,
    reference: str | None = None,
) -> dict:
    row = {
        "id": "",  # assigned once the list is final
        "date": day.isoformat(),
        "description": description,
        "amount": round(amount, 2),
        "creditAmount": round(max(amount, 0.0), 2),
        "debitAmount": round(abs(min(amount, 0.0)), 2),
        "currency": CURRENCY,
        "category": category,
        "type": "debit" if amount < 0 else "credit",
        "pending": False,
        "accountId": ACCOUNT_ID,
        "bankId": BANK_ID,
    }
    if merchant is not None:
        row["merchantName"] = merchant
    if reference is not None:
        row["reference"] = reference
    return row


def scale_amount(row: dict, factor: float) -> None:
    row["amount"] = round(row["amount"] * factor, 2)
    row["creditAmount"] = round(max(row["amount"], 0.0), 2)
    row["debitAmount"] = round(abs(min(row["amount"], 0.0)), 2)


def rows_between(rows: list[dict], lo: date, hi: date) -> list[dict]:
    lo_t, hi_t = lo.isoformat(), hi.isoformat()
    return [r for r in rows if lo_t <= r["date"] <= hi_t]


def total(rows: list[dict]) -> float:
    return sum(r["amount"] for r in rows)


# --------------------------------------------------------------------------
# Draft generation: a plausible, lightly positive budget anchored to as_of.
# --------------------------------------------------------------------------


def draft(as_of: date, seed: int, months: int) -> tuple[list[dict], list[date]]:
    rng = random.Random(seed)
    y, m = as_of.year, as_of.month
    sy, sm = shift_months(y, m, -(months - 1))
    start = date(sy, sm, 1)

    raise_month_y, raise_month_m = shift_months(y, m, -6)
    raise_date = date(raise_month_y, raise_month_m, 15)

    # Pay day cadence: fortnightly, most recent pay day a few days before as_of.
    paydays: list[date] = []
    d = as_of - timedelta(days=rng.randint(2, 5))
    while d >= start:
        paydays.append(d)
        d -= timedelta(days=14)
    paydays = sorted(paydays)
    payday_set = set(paydays)

    rows: list[dict] = []
    last_takeaway: date | None = None
    last_uber: date | None = None

    day = start
    while day <= as_of:
        wd = day.weekday()

        if day in payday_set:
            salary = 3100.0 if day < raise_date else 3200.0
            rows.append(
                make(day, "EXAMPLE EMPLOYER SALARY", salary, "Income", reference="PAYROLL")
            )
            next_day = day + timedelta(days=1)
            if next_day <= as_of:
                rows.append(
                    make(next_day, "TRANSFER TO SAVINGS", -rng.uniform(250, 350), "Transfer")
                )

        if day.day == 1:
            rows.append(make(day, "METRO PROPERTY GROUP *RENT", -2400.0, "Housing"))
        if day.day == 2:
            merchant = "JEMEN ELECTRICITY"
            rows.append(make(day, merchant, -rng.uniform(60, 120), "Utilities", merchant))
        if day.day == 15:
            rows.append(make(day, "AGL ENERGY", -rng.uniform(80, 150), "Utilities", "AGL ENERGY"))
            rows.append(make(day, "NETFLIX", -9.99, "Subscriptions", "NETFLIX"))
        if day.day == 9:
            rows.append(make(day, "IPL *STREAMY HD", -15.0, "Subscriptions", "STREAMY HD"))
        if day.day == 24:
            rows.append(make(day, "TCP WATER", -rng.uniform(22, 50), "Utilities", "TCP WATER"))

        if wd in (0, 2, 4):
            merchant, lo, hi = rng.choice(GROCER_MERCHANTS)
            rows.append(make(day, merchant, -rng.uniform(lo, hi), "Groceries", merchant))
            if rng.random() < 0.25:  # an occasional second, smaller run
                merchant, lo, hi = rng.choice(GROCER_MERCHANTS)
                rows.append(make(day, merchant, -rng.uniform(lo * 0.5, hi * 0.6), "Groceries", merchant))

        if wd in (1, 5) or (wd == 3 and rng.random() < 0.35):
            merchant, lo, hi = rng.choice(CAFE_MERCHANTS)
            rows.append(make(day, merchant, -rng.uniform(lo, hi), "Dining", merchant))

        if last_takeaway is None or (day - last_takeaway).days >= 9:
            if rng.random() < 0.65:
                merchant, lo, hi = rng.choice(TAKEAWAY_MERCHANTS)
                rows.append(make(day, merchant, -rng.uniform(lo, hi), "Dining", merchant))
                last_takeaway = day

        if day.day in (8, 22):
            merchant, lo, hi = rng.choice(FUEL_MERCHANTS)
            rows.append(make(day, merchant, -rng.uniform(lo, hi), "Transport", merchant))

        if last_uber is None or (day - last_uber).days >= rng.randint(18, 28):
            rows.append(make(day, "UBER *METRO RIDESHARE", -rng.uniform(14, 32), "Transport", "UBER"))
            last_uber = day

        if day.day == 10:
            rows.append(make(day, "BANK SERVICE FEE", -4.95, "Fees"))
            if rng.random() < 0.3:
                rows.append(make(day, "SMS ALERTING FEE", -2.95, "Fees"))

        day += timedelta(days=1)

    # A couple of refunds, mid-history (never beyond as_of).
    for _ in range(2):
        refund_day = start + timedelta(days=rng.randint(30, max(31, (as_of - start).days - 30)))
        rows.append(make(refund_day, "REFUND WOOLWORTHS", rng.uniform(10, 35), "Refund", "WOOLWORTHS"))

    rows.sort(key=lambda r: r["date"])
    return rows, paydays


# --------------------------------------------------------------------------
# Crafted wins: targeted adjustments so each recency tier has a positive,
# verifiable story (mirrors the six tiers in PRD 3.2 / issue #2).
# --------------------------------------------------------------------------


def fix_takeaway_gap(rows: list[dict], as_of: date) -> None:
    """No takeaway spend after this Tuesday, so the buffer chip is true, while ensuring all 3 takeaway merchants appear in the 2-week pay cycle window."""
    week = iso_week_start(as_of)
    cutoff = week + timedelta(days=1)
    if cutoff > as_of:
        cutoff -= timedelta(days=7)
    for row in rows:
        if row["category"] == "Dining" and row["merchantName"] in TAKEAWAY_NAMES and date.fromisoformat(row["date"]) > cutoff:
            row["date"] = cutoff.isoformat()

    # Ensure the 14-day pay cycle window ending at as_of contains multi-merchant takeaway depth
    # (UBER *EATS, DOORDASH, ALDI DELIVERY) on or before cutoff so the 2-week pay cycle demo
    # and follow-up "let me see the ubereats transactions" are rich and useful.
    cycle_start = as_of - timedelta(days=13)
    in_cycle_merchants = {
        r.get("merchantName")
        for r in rows
        if r["category"] == "Dining"
        and r.get("merchantName") in TAKEAWAY_NAMES
        and cycle_start <= date.fromisoformat(r["date"]) <= cutoff
    }
    if "ALDI DELIVERY" not in in_cycle_merchants:
        d = max(cycle_start + timedelta(days=3), min(cutoff - timedelta(days=4), cutoff))
        rows.append(make(d, "ALDI DELIVERY", -38.45, "Dining", "ALDI DELIVERY"))
    # Add additional UBER *EATS and DOORDASH orders in the 2-week window (on or before cutoff)
    rows.append(make(cycle_start, "UBER *EATS", -31.40, "Dining", "UBER *EATS"))
    rows.append(make(cycle_start + timedelta(days=1), "DOORDASH", -42.50, "Dining", "DOORDASH"))
    rows.append(make(cycle_start + timedelta(days=2), "UBER *EATS", -28.90, "Dining", "UBER *EATS"))
    rows.sort(key=lambda r: r["date"])



def fix_cafe_week(rows: list[dict], as_of: date) -> None:
    """Cafe spend this week is present but under the trailing 3-week average."""
    def is_cafe(r: dict) -> bool:
        return r["category"] == "Dining" and r["description"] not in TAKEAWAY_NAMES

    week = iso_week_start(as_of)
    week_cafe = [r for r in rows if is_cafe(r)
                 and iso_week_start(date.fromisoformat(r["date"])) == week
                 and date.fromisoformat(r["date"]) <= as_of]

    prior_totals = []
    for k in (1, 2, 3):
        lo = week - timedelta(weeks=k)
        hi = min(lo + timedelta(days=6), as_of)
        in_week = [r for r in rows if is_cafe(r)
                   and lo <= date.fromisoformat(r["date"]) <= hi]
        prior_totals.append(sum(abs(r["amount"]) for r in in_week))
    average = sum(prior_totals) / len(prior_totals) or 20.0

    current = sum(abs(r["amount"]) for r in week_cafe)
    if current == 0.0:
        day = min(week + timedelta(days=1), as_of)
        rows.append(make(day, "SQ *CAFE NEWTOWN", -9.5, "Dining", "SQ *CAFE NEWTOWN"))
        return
    if current > average:
        factor = min(15.0, (average * 0.8) / current)
        for row in week_cafe:
            scale_amount(row, max(factor, 0.4))


def fix_groceries_30d(rows: list[dict], as_of: date) -> None:
    """Groceries in the last 30 days come in at least ~10% below the prior 30."""
    def window(lo: date, hi: date) -> float:
        return sum(
            abs(r["amount"])
            for r in rows_between(rows, lo, hi)
            if r["category"] == "Groceries"
        )

    last30 = as_of - timedelta(days=29)
    prior_lo, prior_hi = as_of - timedelta(days=59), as_of - timedelta(days=30)
    for _ in range(20):
        a, b = window(last30, as_of), window(prior_lo, prior_hi)
        if a <= b * 0.9 or b == 0:
            break
        recent = [r for r in rows_between(rows, last30, as_of) if r["category"] == "Groceries"]
        if not recent:
            return
        biggest = max(recent, key=lambda r: abs(r["amount"]))
        scale_amount(biggest, 0.75)
        if abs(biggest["amount"]) < 25:
            return


def fix_utilities_quarter(rows: list[dict], as_of: date, target: float = 140.0) -> None:
    """Utility spend this quarter lands ~$140 under last quarter (PRD 3.2 chip)."""
    y, q = quarter_of(as_of)
    this_start = date(y, q * 3 + 1, 1)
    next_start = date(y, (q + 1) * 3 + 1, 1) if q < 3 else date(y + 1, 1, 1)
    last_start = date(y, (q - 1) * 3 + 1, 1) if q > 0 else date(y - 1, 10, 1)
    this_end = min(as_of, next_start - timedelta(days=1))
    last_end = this_start - timedelta(days=1)

    this_rows = [r for r in rows if r["category"] == "Utilities"
                 and this_start.isoformat() <= r["date"] <= this_end.isoformat()]
    last_rows = [r for r in rows if r["category"] == "Utilities"
                 and last_start.isoformat() <= r["date"] <= last_end.isoformat()]
    s_this = sum(abs(r["amount"]) for r in this_rows)
    s_last = sum(abs(r["amount"]) for r in last_rows)
    if s_this == 0 or s_last == 0:
        return
    diff = s_last - s_this
    if not (60 <= diff <= 300):
        # This quarter is partial, so shrink the prior quarter rather than inflating
        # the in-progress one; fall back to scaling the current quarter if that
        # would over-correct.
        factor_last = (s_this + target) / s_last
        if 0.2 <= factor_last <= 2.5:
            for row in last_rows:
                scale_amount(row, factor_last)
        else:
            factor_this = (s_last - target) / s_this
            if 0.4 <= factor_this <= 2.5:
                for row in this_rows:
                    scale_amount(row, factor_this)
            else:
                pool = this_rows if s_this > s_last else last_rows
                scale_amount(max(pool, key=lambda r: abs(r["amount"])), 0.5)

    # Ensure this quarter has all 3 utility providers (JEMEN ELECTRICITY, AGL ENERGY, TCP WATER)
    # even when as_of is early in the quarter, preserving the exact quarterly total.
    s_this_final = round(sum(abs(r["amount"]) for r in this_rows), 2)
    this_merchants = {r.get("merchantName") for r in this_rows}
    if len(this_rows) == 1 and len(this_merchants) == 1 and (this_end - this_start).days >= 7:
        jemen_amt = round(s_this_final * 0.3449, 2)
        agl_amt = round(s_this_final * 0.4649, 2)
        water_amt = round(s_this_final - jemen_amt - agl_amt, 2)
        row0 = this_rows[0]
        row0["amount"] = -jemen_amt
        row0["creditAmount"] = 0.0
        row0["debitAmount"] = jemen_amt
        rows.append(make(this_start + timedelta(days=4), "AGL ENERGY", -agl_amt, "Utilities", "AGL ENERGY"))
        rows.append(make(this_start + timedelta(days=7), "TCP WATER", -water_amt, "Utilities", "TCP WATER"))
        rows.sort(key=lambda r: r["date"])



def fix_pay_cycles(rows: list[dict], paydays: list[date], as_of: date) -> None:
    """Keep the last 13 completed pay cycles net positive (stability story)."""
    days = sorted(paydays)
    for i in range(len(days) - 1):
        lo, hi = days[i], days[i + 1] - timedelta(days=1)
        window = rows_between(rows, lo, min(hi, as_of))
        if total(window) > 0:
            continue
        # Trim this cycle's savings transfer, then the biggest discretionary tx.
        transfers = [r for r in window if r["category"] == "Transfer"]
        for t in sorted(transfers, key=lambda r: -abs(r["amount"])):
            scale_amount(t, 0.5)
            if total(window) > 0:
                break
        if total(window) <= 0:
            discretionary = [r for r in window if r["category"] in ("Dining", "Transport")]
            for r in sorted(discretionary, key=lambda r: -abs(r["amount"]))[:2]:
                scale_amount(r, 0.6)
                if total(window) > 0:
                    break


def mark_recent_pending(rows: list[dict]) -> None:
    for row in rows[-2:]:
        row["pending"] = True


# --------------------------------------------------------------------------
# Hard validation: every crafted win must hold in the final dataset.
# --------------------------------------------------------------------------


def validate(rows: list[dict], paydays: list[date], as_of: date) -> dict:
    day = as_of
    week = iso_week_start(day)
    last_pay = max(d for d in paydays if d <= day)

    def window_net(lo: date, hi: date) -> float:
        return total(rows_between(rows, lo, hi))

    # This week: cafe micro-win.
    week_cafe = [r for r in rows_between(rows, week, day)
                 if r["category"] == "Dining" and r["description"] not in TAKEAWAY_NAMES]
    cafe_week = sum(abs(r["amount"]) for r in week_cafe)
    cafe_set = set(map(id, week_cafe))
    prior = []
    for k in (1, 2, 3):
        lo = week - timedelta(weeks=k)
        hi = min(lo + timedelta(days=6), day)
        prior.append(sum(abs(r["amount"]) for r in rows_between(rows, lo, hi)
                         if r["category"] == "Dining"
                         and r["description"] not in TAKEAWAY_NAMES
                         and id(r) not in cafe_set))
    cafe_avg = sum(prior) / 3
    assert 0 < cafe_week <= cafe_avg, f"this-week cafe win broken: {cafe_week} vs avg {cafe_avg:.2f}"

    # This pay cycle: takeaway untouched since Tuesday + buffer >= $35.
    cutoff = week + timedelta(days=1)
    if cutoff > day:
        cutoff -= timedelta(days=7)
    takeaways = [r for r in rows if r["description"] in TAKEAWAY_NAMES]
    assert all(date.fromisoformat(r["date"]) <= cutoff for r in takeaways), "takeaway spend after Tuesday"
    assert any(date.fromisoformat(r["date"]) <= cutoff for r in takeaways), "no takeaway history"
    buffer = window_net(last_pay, day)
    assert buffer >= 35, f"current-cycle buffer {buffer:.2f} < 35"

    # Last 30 days: groceries trending down.
    last30 = sum(abs(r["amount"]) for r in rows_between(rows, day - timedelta(days=29), day) if r["category"] == "Groceries")
    prior30 = sum(abs(r["amount"]) for r in rows_between(rows, day - timedelta(days=59), day - timedelta(days=30)) if r["category"] == "Groceries")
    assert last30 < prior30, f"grocery trend not down: {last30:.2f} vs {prior30:.2f}"
    assert last30 <= prior30 * 0.9, f"grocery win too thin to demo: {last30:.2f} vs {prior30:.2f}"

    # This quarter: utilities ~$140 lower.
    q_this = quarter_start(day)
    util_this = sum(abs(r["amount"]) for r in rows_between(rows, q_this, day) if r["category"] == "Utilities")
    q_prev_lo = quarter_start(quarter_start(day) - timedelta(days=1))
    util_last = sum(abs(r["amount"]) for r in rows_between(rows, q_prev_lo, quarter_start(day) - timedelta(days=1)) if r["category"] == "Utilities")
    assert 60 <= util_last - util_this <= 300, f"utilities QoQ delta {util_last - util_this:.2f}"

    # 6 months: 13+ consecutive positive pay cycles.
    days = sorted(d for d in paydays if d <= day)
    six_months_ago = day - timedelta(days=180)
    streak = 0
    for i in range(len(days) - 1):
        lo, hi = days[i], days[i + 1] - timedelta(days=1)
        if hi < day and hi >= six_months_ago - timedelta(days=30) and window_net(lo, hi) > 0:
            streak += 1
    assert streak >= 10, f"positive pay-cycle streak {streak} < 10"

    # YTD: buffer growing.
    ytd = window_net(date(day.year, 1, 1), day)
    assert ytd > 0, f"YTD net {ytd:.2f} not positive"

    # Schema hygiene (mirrors test_mock.test_fixture_amounts).
    assert any(r["pending"] for r in rows)
    assert any("merchantName" not in r for r in rows)
    for r in rows:
        assert abs(r["amount"] - (r["creditAmount"] - r["debitAmount"])) < 0.011

    return {
        "cafe_week": round(cafe_week, 2),
        "cafe_3wk_avg": round(cafe_avg, 2),
        "cycle_buffer": round(buffer, 2),
        "groceries_last30": round(last30, 2),
        "groceries_prior30": round(prior30, 2),
        "utilities_this_quarter": round(util_this, 2),
        "utilities_last_quarter": round(util_last, 2),
        "positive_cycle_streak": streak,
        "ytd_net": round(ytd, 2),
    }


def generate(as_of: date, seed: int, months: int) -> tuple[list[dict], dict]:
    rows, paydays = draft(as_of, seed, months)
    fix_takeaway_gap(rows, as_of)
    fix_cafe_week(rows, as_of)
    fix_groceries_30d(rows, as_of)
    fix_utilities_quarter(rows, as_of)
    fix_pay_cycles(rows, paydays, as_of)
    rows.sort(key=lambda r: r["date"])  # stable sort preserves generation order per day
    mark_recent_pending(rows)
    for i, row in enumerate(rows, start=1):
        row["id"] = f"mock_tx_{i:04d}"
    wins = validate(rows, paydays, as_of)
    return rows, wins


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--as-of", type=date.fromisoformat, default=date.today(),
                        help="Anchor date, YYYY-MM-DD (default: today).")
    parser.add_argument("--seed", type=int, default=20261012)
    parser.add_argument("--months", type=int, default=13,
                        help="Calendar months of history to generate (default 13).")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()

    rows, wins = generate(args.as_of, args.seed, args.months)
    args.out.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    span = rows[0]["date"], rows[-1]["date"]
    print(f"wrote {len(rows)} transactions ({span[0]} -> {span[1]}) to {args.out}")
    print(f"page count at size {PAGE_BUDGET}: {(len(rows) + PAGE_BUDGET - 1) // PAGE_BUDGET}")
    for name, value in wins.items():
        print(f"  win {name}: {value}")


if __name__ == "__main__":
    main()
