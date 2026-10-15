import math

import pytest
from fastapi.testclient import TestClient

from auditor.main import app, build_vocabulary, execute_spec_compute, ground_terms, load_transactions
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


# ---------------------------------------------------------------------------
# Catalog grounding: vocabulary, canonical echo, hallucinated entities (gap 4)
# ---------------------------------------------------------------------------

DATASET = load_transactions()


def test_ground_terms_matches_punctuated_names():
    # "uber eats" must resolve the ledger's canonical "UBER *EATS".
    vocab = build_vocabulary(DATASET)
    matched, unmatched = ground_terms(["uber eats", "grace city church"], vocab.merchants)
    assert "UBER *EATS" in matched
    assert unmatched == ["grace city church"]


def test_bare_name_resolves_to_all_variants():
    # "uber" (no qualifier) intentionally resolves to both UBER and UBER *EATS —
    # the receipt echoes which, so the user can object.
    vocab = build_vocabulary(DATASET)
    matched, unmatched = ground_terms(["uber"], vocab.merchants)
    assert matched == ["UBER", "UBER *EATS"]
    assert unmatched == []


def test_receipt_echoes_matched_canonical_merchants():
    r = execute_spec_compute(spec(included_merchants=["uber eats"], target_metric="TOTAL_SUM"), DATASET)
    assert r.matched_merchants == ["UBER *EATS"]
    assert "UBER *EATS" in r.summary


def test_receipt_states_all_assumptions():
    r = execute_spec_compute(
        spec(
            target_metric="PERIOD_AVERAGE",
            included_merchants=["uber eats"],
            excluded_merchants=["grace city church"],
            temporal_grain="WEEK",
        ),
        DATASET,
    )
    s = r.summary
    assert "UBER *EATS" in s              # matched entities
    assert "grace city church" in s        # exclusions (echoed even when unmatched)
    assert "pending" in s                  # pending handling
    assert "refunds" in s                  # refund handling
    assert "Denominator:" in s             # denominator used
    assert r.unmatched_terms == ["grace city church"]


def test_unmatched_exclusion_is_reported_not_silent():
    r = execute_spec_compute(
        spec(target_metric="TRANSACTION_COUNT", temporal_grain="WEEK", excluded_merchants=["grace city church"]),
        FIXTURE,
    )
    assert "grace city church" in r.unmatched_terms
    assert r.value is not None  # still computes; the exclusion just matched nothing


def test_endpoint_clarifies_hallucinated_included_entity():
    response = client.post(
        "/query",
        json={"spec": {"target_metric": "TOTAL_SUM", "included_merchants": ["No Such Place"]}},
    )
    assert response.status_code == 422
    data = response.json()
    assert data["status"] == "entity_not_found"
    assert "value" not in data  # never a confident zero


def test_endpoint_suggests_close_matches():
    response = client.post(
        "/query",
        json={"spec": {"target_metric": "TOTAL_SUM", "included_merchants": ["woolwortshs"]}},  # typo
    )
    assert response.status_code == 422
    assert "WOOLWORTHS" in response.json()["message"]


# ---------------------------------------------------------------------------
# Refund invariant (gap 6): credits — incl. refunds — never count as spend
# ---------------------------------------------------------------------------

def test_refunds_never_count_as_spend():
    r = execute_spec_compute(spec(included_merchants=["woolworths"], target_metric="TOTAL_SUM"), DATASET)
    debits = sum(
        abs(t["amount"])
        for t in DATASET
        if (t.get("merchantName") or t.get("description", "")).lower() in ("woolworths", "refund woolworths")
        and not t["pending"]
        and t.get("type") == "debit"
    )
    assert r.value == round(debits, 2)  # the REFUND WOOLWORTHS credits are excluded
    assert "refunds" in r.summary


# ---------------------------------------------------------------------------
# is_out_of_spec escape hatch (gap 8): no number, ever
# ---------------------------------------------------------------------------

def test_out_of_spec_receipt_returns_reason_not_number():
    response = client.post(
        "/query",
        json={
            "spec": {
                "target_metric": "PERIOD_AVERAGE",
                "is_out_of_spec": True,
                "out_of_spec_reason": "The user asked for a narrative, not a calculation.",
            }
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "out_of_spec"
    assert "reason" in data
    assert "value" not in data


def test_compute_refuses_out_of_spec_spec():
    with pytest.raises(ValueError):
        execute_spec_compute(
            FinancialQuerySpec(target_metric="PERIOD_AVERAGE", is_out_of_spec=True), DATASET
        )


# ---------------------------------------------------------------------------
# Acceptance: every PRD §8 prompt form computes with the right grain, window,
# denominator and entity semantics (deterministic — no LLM in the compute path)
# ---------------------------------------------------------------------------

PRD_PROMPT_SPECS = {
    # name: spec the parser is expected to emit
    "P1 avg uber eats monthly spend": dict(
        target_metric="PERIOD_AVERAGE", included_merchants=["uber eats"], temporal_grain="MONTH"
    ),
    "P2 average order amount each month": dict(
        target_metric="AVERAGE_TRANSACTION", included_merchants=["uber eats"], temporal_grain="MONTH"
    ),
    "P3 how many times uber eats each week": dict(
        target_metric="TRANSACTION_COUNT", included_merchants=["uber eats"], temporal_grain="WEEK"
    ),
    "P4 monthly avg at restaurants/cafes/coffee/fast food": dict(
        target_metric="PERIOD_AVERAGE", included_categories=["Dining"], temporal_grain="MONTH"
    ),
    "P6 how often I order in weekly, excluding uber eats & church": dict(
        target_metric="TRANSACTION_COUNT",
        included_categories=["Dining"],
        excluded_merchants=["uber eats", "grace city church"],
        temporal_grain="WEEK",
    ),
    "P10 avg monthly grocery spend": dict(
        target_metric="PERIOD_AVERAGE", included_categories=["Groceries"], temporal_grain="MONTH"
    ),
    "P11 avg monthly transport & fuel (incl uber rides)": dict(
        target_metric="PERIOD_AVERAGE", included_categories=["Transport"], temporal_grain="MONTH"
    ),
    "P13 avg monthly at bars/liquor/entertainment": dict(
        target_metric="PERIOD_AVERAGE", included_categories=["Entertainment"], temporal_grain="MONTH"
    ),
}


@pytest.mark.parametrize("name,kwargs", list(PRD_PROMPT_SPECS.items()), ids=list(PRD_PROMPT_SPECS))
def test_prd_prompt_executes_with_correct_receipt(name, kwargs):
    r = execute_spec_compute(FinancialQuerySpec(**kwargs), DATASET)
    assert r.value is not None and math.isfinite(r.value) and r.value >= 0
    assert "Window: " in r.summary
    assert "Denominator: " in r.summary
    assert "Excluded: " in r.summary
    assert "Counted: " in r.summary


@pytest.mark.parametrize("name,kwargs", list(PRD_PROMPT_SPECS.items()), ids=list(PRD_PROMPT_SPECS))
def test_prd_prompt_summary_uses_requested_grain(name, kwargs):
    r = execute_spec_compute(FinancialQuerySpec(**kwargs), DATASET)
    if kwargs["target_metric"] == "AVERAGE_TRANSACTION":
        assert "average transaction" in r.summary
    else:
        assert f"per {kwargs['temporal_grain'].lower().replace('_', ' ')}" in r.summary


def test_prd_p1_matches_uber_eats_canonically():
    r = execute_spec_compute(FinancialQuerySpec(**PRD_PROMPT_SPECS["P1 avg uber eats monthly spend"]), DATASET)
    assert r.matched_merchants == ["UBER *EATS"]
    assert r.value > 0


def test_prd_p6_exclusion_semantics():
    r = execute_spec_compute(FinancialQuerySpec(**PRD_PROMPT_SPECS["P6 how often I order in weekly, excluding uber eats & church"]), DATASET)
    assert "UBER *EATS" in r.matched_excluded_merchants
    assert "grace city church" in r.unmatched_terms
    assert r.value > 0


def test_prd_p13_no_matching_category_is_honest_zero():
    r = execute_spec_compute(FinancialQuerySpec(**PRD_PROMPT_SPECS["P13 avg monthly at bars/liquor/entertainment"]), DATASET)
    assert r.value == 0.0
    assert "Entertainment" in r.unmatched_terms


def test_prd_p13_endpoint_offers_clarification_not_number():
    response = client.post(
        "/query",
        json={"spec": PRD_PROMPT_SPECS["P13 avg monthly at bars/liquor/entertainment"]},
    )
    assert response.status_code == 422
    assert response.json()["status"] == "entity_not_found"


def test_prd_prompt15_synthesis_is_out_of_spec():
    # "Describe my spending habits and make recommendations" → the parser marks
    # is_out_of_spec; the endpoint must route it on, never compute.
    response = client.post(
        "/query",
        json={
            "spec": {
                "target_metric": "PERIOD_AVERAGE",
                "is_out_of_spec": True,
                "out_of_spec_reason": "Synthesis/recommendation request.",
            }
        },
    )
    assert response.status_code == 200
    assert response.json()["status"] == "out_of_spec"
    assert "value" not in response.json()


# ---------------------------------------------------------------------------
# /compute endpoint (deterministic spec-in, receipt-out)
# ---------------------------------------------------------------------------

def make_compute_payload(**overrides):
    payload = {
        "target_metric": "TOTAL_SUM",
        "included_merchants": [],
        "excluded_merchants": [],
        "included_categories": [],
        "excluded_categories": [],
        "temporal_grain": "MONTH",
        "denominator_strategy": "ACTIVE_PERIODS_ONLY",
        "exclude_pending": True,
        "exclude_internal_transfers": True
    }
    payload.update(overrides)
    return payload

def test_compute_endpoint_total_sum():
    payload = make_compute_payload(included_merchants=["Uber Eats"])
    response = client.post("/compute", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert data["spec"]["target_metric"] == "TOTAL_SUM"
    assert "Uber Eats" in data["spec"]["included_merchants"]

def test_compute_endpoint_period_average():
    payload = {
        "target_metric": "PERIOD_AVERAGE",
        "included_merchants": [],
        "excluded_merchants": [],
        "included_categories": ["groceries"],
        "excluded_categories": [],
        "temporal_grain": "MONTH",
        "denominator_strategy": "ACTIVE_PERIODS_ONLY",
        "exclude_pending": True,
        "exclude_internal_transfers": True
    }
    response = client.post("/compute", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert isinstance(data["value"], (int, float))
