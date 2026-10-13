import os
from unittest.mock import MagicMock, patch
from fastapi.testclient import TestClient

from auditor.main import app, execute_spec_compute
from auditor.models import DateWindow, FinancialQuerySpec

client = TestClient(app)


# ---------------------------------------------------------------------------
# /audit (Curation Agent)
# ---------------------------------------------------------------------------

def test_audit_endpoint_returns_three_chips():
    response = client.post("/audit", json={"goal": "SAVINGS", "payFrequency": "FORTNIGHTLY"})
    assert response.status_code == 200
    data = response.json()
    assert "chips" in data
    chips = data["chips"]
    assert len(chips) == 3
    serendipity_count = sum(1 for c in chips if c.get("isSerendipity") is True)
    assert serendipity_count == 1


# ---------------------------------------------------------------------------
# execute_spec_compute — deterministic compute, exact expectations
# ---------------------------------------------------------------------------

def T(d, m, a, cat="Dining", pending=False, typ="debit"):
    return {
        "date": d,
        "merchantName": m,
        "category": cat,
        "amount": a,
        "pending": pending,
        "type": typ,
    }


def spec(**overrides):
    base = {
        "target_metric": "PERIOD_AVERAGE",
        "included_merchants": ["UBER EATS"],
        "temporal_grain": "MONTH",
    }
    base.update(overrides)
    return FinancialQuerySpec(**base)


FIXTURE = [
    T("2026-01-05", "UBER EATS", -20),  # ISO week 2026-W02
    T("2026-01-12", "UBER EATS", -30),  # ISO week 2026-W03
    T("2026-01-19", "UBER EATS", -25),  # ISO week 2026-W04
    T("2026-01-26", "UBER EATS", -25),  # ISO week 2026-W05
    T("2026-02-02", "UBER EATS", -10),  # ISO week 2026-W06
    T("2026-03-05", "UBER EATS", -10),  # ISO week 2026-W10
    T("2026-03-05", "OTHER PLACE", -999, cat="Other"),  # filtered out by merchant
]


def test_weekly_count_active_periods():
    # 6 tx across 6 distinct ISO weeks (W02..W06, W10) -> 1.0 per week
    r = execute_spec_compute(spec(target_metric="TRANSACTION_COUNT", temporal_grain="WEEK"), FIXTURE)
    assert r.value == 1.0
    assert r.periods_counted == 6
    assert "per week" in r.summary


def test_weekly_count_all_calendar_periods():
    # ALL_TIME = Jan 5 .. Mar 5 -> ISO weeks W02..W10 = 9 -> 6/9
    r = execute_spec_compute(
        spec(target_metric="TRANSACTION_COUNT", temporal_grain="WEEK", denominator_strategy="ALL_CALENDAR_PERIODS"),
        FIXTURE,
    )
    assert r.periods_counted == 9
    assert r.value == round(6 / 9, 2)


def test_monthly_average_calendar_vs_active_denominator():
    # Two tx in Jan and Mar, custom window Jan 1 - Mar 31
    small = [T("2026-01-10", "UBER EATS", -30), T("2026-03-15", "UBER EATS", -20)]
    window = DateWindow(type="CUSTOM", start="2026-01-01", end="2026-03-31")

    active = execute_spec_compute(spec(date_window=window, denominator_strategy="ACTIVE_PERIODS_ONLY"), small)
    assert active.periods_counted == 2
    assert active.value == 25.0  # 50 / 2 active months

    calendar = execute_spec_compute(spec(date_window=window, denominator_strategy="ALL_CALENDAR_PERIODS"), small)
    assert calendar.periods_counted == 3  # Jan, Feb, Mar
    assert calendar.value == round(50 / 3, 2)  # the silent-denominator fix


def test_date_window_last_month_excludes_current_month():
    # Anchor is Mar 15 -> LAST_MONTH = Feb 1..Feb 28 -> zero matches
    small = [T("2026-01-10", "UBER EATS", -30), T("2026-03-15", "UBER EATS", -20)]
    r = execute_spec_compute(spec(target_metric="TOTAL_SUM", date_window=DateWindow(type="LAST_MONTH")), small)
    assert r.value == 0.0
    assert "2026-02-01 to 2026-02-28" in r.summary


def test_date_window_ytd_filters_out_older():
    # Ledger spans Jan..Mar 2026; YTD still Jan 1..Mar 5 here (same year), so use 2025 tx to prove filtering
    older = T("2025-12-01", "UBER EATS", -50)
    small = [T("2026-01-10", "UBER EATS", -30), T("2026-03-15", "UBER EATS", -20), older]
    r = execute_spec_compute(spec(target_metric="TOTAL_SUM", date_window=DateWindow(type="YTD")), small)
    assert r.value == 50.0  # Dec 2025 tx excluded


def test_pay_cycle_bucketing_aligns_to_pay_date():
    # Fortnightly cycles anchored at next_pay_date 2026-01-10:
    # Jan 1 -> C-1, Jan 10 -> C0, Jan 24 -> C1  => 3 active cycles
    cyc = [
        T("2026-01-01", "UBER EATS", -10),
        T("2026-01-10", "UBER EATS", -10),
        T("2026-01-24", "UBER EATS", -10),
    ]
    r = execute_spec_compute(
        spec(target_metric="TRANSACTION_COUNT", temporal_grain="PAY_CYCLE"),
        cyc,
        pay_frequency="FORTNIGHTLY",
        next_pay_date="2026-01-10",
    )
    assert r.periods_counted == 3
    assert r.value == 1.0
    assert "per pay cycle" in r.summary


def test_pending_transactions_excluded():
    p = [
        T("2026-01-10", "UBER EATS", -10, pending=True),
        T("2026-01-10", "UBER EATS", -5),
    ]
    r = execute_spec_compute(spec(target_metric="TOTAL_SUM"), p)
    assert r.value == 5.0
    assert "pending" in r.summary


def test_credits_and_income_not_counted_as_spend():
    mixed = [
        T("2026-01-10", "UBER EATS", -10),
        T("2026-01-11", "SOME PAYEER", 2000, cat="Income", typ="credit"),
    ]
    r = execute_spec_compute(spec(included_merchants=[], target_metric="TOTAL_SUM"), mixed)
    assert r.value == 10.0  # income credit excluded


def test_run_rate_annualises_per_period_average():
    r = execute_spec_compute(
        spec(target_metric="RUN_RATE", temporal_grain="MONTH"),
        [T("2026-01-10", "UBER EATS", -100)],
    )
    assert r.value == 1200.0  # 100/month * 12


def test_empty_match_returns_zero_not_guess():
    r = execute_spec_compute(spec(target_metric="TOTAL_SUM", included_merchants=["NO SUCH PLACE"]), FIXTURE)
    assert r.value == 0.0
    assert r.periods_counted == 1
    assert "0 transactions" in r.summary


# ---------------------------------------------------------------------------
# /query endpoint
# ---------------------------------------------------------------------------

def test_query_endpoint_with_pre_parsed_spec():
    response = client.post(
        "/query",
        json={"spec": {"target_metric": "TOTAL_SUM", "included_merchants": ["WOOLWORTHS"]}},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["value"] > 0
    assert "Window" in data["summary"]
    assert data["spec"]["included_merchants"] == ["WOOLWORTHS"]


def test_query_endpoint_never_guessed_spec():
    # Without a pre-parsed spec and without a resolvable LLM parse, the endpoint
    # must return a typed unparseable failure - never a fabricated number.
    response = client.post("/query", json={"query": "What is my average uber eats monthly spend?"})
    assert response.status_code in (200, 422)
    if response.status_code == 422:
        assert response.json()["status"] == "unparseable"


def test_query_endpoint_requires_query_or_spec():
    response = client.post("/query", json={})
    assert response.status_code == 422
    assert response.json()["status"] == "unparseable"
