import pytest
from fastapi.testclient import TestClient

from auditor.main import app, execute_spec_compute, load_transactions
from auditor.models import DateWindow, FinancialQuerySpec

client = TestClient(app)

@pytest.fixture(scope="module")
def mock_transactions():
    return load_transactions()

# ===========================================================================
# 1. Micro-Win Suite (6 Recency Micro-Wins from generate.py)
# ===========================================================================

def test_happy_path_micro_win_1_cafe_this_week(mock_transactions):
    """Micro-Win 1: Cafe spend this week ($9.50 across 1 transaction, including recent pending)."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_merchants=["SQ *CAFE NEWTOWN", "CORNER CAFE", "BLUEBONIC COFFEE CO"],
        date_window=DateWindow(type="THIS_WEEK"),
        exclude_pending=False,
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 9.50
    assert "across 1 transaction" in receipt.summary
    assert "THIS_WEEK" in receipt.summary


def test_happy_path_micro_win_2_takeaway_buffer_this_cycle(mock_transactions):
    """Micro-Win 2: Takeaway spend this cycle ($0.00 since Tuesday)."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_merchants=["UBER *EATS", "DOORDASH", "ALDI DELIVERY"],
        date_window=DateWindow(type="THIS_WEEK"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 0.00
    assert "across 0 transactions" in receipt.summary


def test_happy_path_micro_win_3_groceries_last_30_days(mock_transactions):
    """Micro-Win 3: Groceries in the last 30 days returns non-zero verified total."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_categories=["Groceries"],
        date_window=DateWindow(type="LAST_30_DAYS"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0
    assert "across" in receipt.summary


def test_happy_path_micro_win_4_utilities_this_quarter(mock_transactions):
    """Micro-Win 4: Seasonal utility bills drop this quarter ($105.83 across 3 providers vs $245.82 prior quarter)."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_categories=["Utilities"],
        date_window=DateWindow(type="THIS_QUARTER"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 105.83
    assert "across 3 transactions" in receipt.summary
    assert "• Comparison: $139.99 lower than prior quarter ($245.82)" in receipt.summary
    assert "AGL ENERGY (-$68.61)" in receipt.summary
    assert "JEMEN ELECTRICITY (-$52.97)" in receipt.summary
    assert "TCP WATER (-$18.41)" in receipt.summary
    assert {b.category for b in receipt.breakdown} == {"AGL ENERGY", "JEMEN ELECTRICITY", "TCP WATER"}



def test_happy_path_micro_win_5_six_months_subscriptions(mock_transactions):
    """Micro-Win 5: Subscriptions over 6 months returns verified spend."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_categories=["Subscriptions"],
        date_window=DateWindow(type="SIX_MONTHS"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0


def test_happy_path_micro_win_6_ytd_net_savings_buffer(mock_transactions):
    """Micro-Win 6: YTD spend aggregation computes cleanly."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        date_window=DateWindow(type="YTD"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0


# ===========================================================================
# 2. PRD §8 Prompt Coverage Suite
# ===========================================================================

def test_happy_path_prd8_uber_eats_monthly_average(mock_transactions):
    """PRD §8 Prompt 1: What is my average uber eats monthly spend?"""
    spec = FinancialQuerySpec(
        target_metric="PERIOD_AVERAGE",
        included_merchants=["UBER *EATS"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0
    assert receipt.periods_counted > 0


def test_happy_path_prd8_uber_eats_average_order_amount(mock_transactions):
    """PRD §8 Prompt 2: What is the average order amount each month?"""
    spec = FinancialQuerySpec(
        target_metric="AVERAGE_TRANSACTION",
        included_merchants=["UBER *EATS"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0
    assert "transaction" in receipt.summary


def test_happy_path_prd8_uber_eats_weekly_order_count(mock_transactions):
    """PRD §8 Prompt 3: How many times do I order from uber eats each week?"""
    spec = FinancialQuerySpec(
        target_metric="TRANSACTION_COUNT",
        included_merchants=["UBER *EATS"],
        temporal_grain="WEEK",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0
    assert "per week" in receipt.summary


def test_happy_path_prd8_dining_cluster_monthly_average(mock_transactions):
    """PRD §8 Prompt 4: What is my monthly average spend at restaurants, cafes, coffee shops, and fast food?"""
    spec = FinancialQuerySpec(
        target_metric="PERIOD_AVERAGE",
        included_categories=["Dining"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0
    assert "Dining" in receipt.spec.included_categories


def test_happy_path_prd8_grocery_monthly_average(mock_transactions):
    """PRD §8 Prompt 5: Search for grocery stores and tell me my average monthly grocery spend?"""
    spec = FinancialQuerySpec(
        target_metric="PERIOD_AVERAGE",
        included_categories=["Groceries"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0


def test_happy_path_prd8_transport_and_fuel_costs(mock_transactions):
    """PRD §8 Prompt 6: What is the average monthly spend on transportation and fuel costs?"""
    spec = FinancialQuerySpec(
        target_metric="PERIOD_AVERAGE",
        included_categories=["Transport"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value > 0.0


def test_happy_path_prd8_entertainment_monthly_average(mock_transactions):
    """PRD §8 Prompt 7: What is the average monthly spend at bars, liquor stores, and entertainment venues?"""
    spec = FinancialQuerySpec(
        target_metric="PERIOD_AVERAGE",
        included_categories=["Entertainment"],
        temporal_grain="MONTH",
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value is not None
    assert receipt.value == 0.0
    assert "Entertainment" in receipt.unmatched_terms


def test_happy_path_prd8_open_ended_habit_description_routes_out_of_spec():
    """PRD §8 Prompt 8: Describe my spending habits and make recommendations -> out_of_spec cleanly without 500 error."""
    response = client.post(
        "/query",
        json={
            "spec": {
                "target_metric": "PERIOD_AVERAGE",
                "is_out_of_spec": True,
                "out_of_spec_reason": "Describe my spending habits and make recommendations/suggestions",
            }
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "out_of_spec"
    assert "value" not in data
    assert "Describe my spending habits" in data["reason"]


# ===========================================================================
# 3. Hierarchical Breakdown, TRANSACTION_LIST & Golden Demo Chips (#61)
# ===========================================================================

def test_happy_path_subscriptions_breakdown_groups_by_merchant(mock_transactions):
    """Single-category query ('Subscriptions', SIX_MONTHS) groups breakdown by canonical merchant."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_categories=["Subscriptions"],
        date_window=DateWindow(type="SIX_MONTHS"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 139.95
    assert len(receipt.breakdown) == 2
    assert receipt.breakdown[0].category == "STREAMY HD"
    assert receipt.breakdown[0].amount == 90.00
    assert receipt.breakdown[0].percentage == 64.3
    assert receipt.breakdown[0].count == 6
    assert receipt.breakdown[1].category == "NETFLIX"
    assert receipt.breakdown[1].amount == 49.95
    assert receipt.breakdown[1].percentage == 35.7
    assert receipt.breakdown[1].count == 5
    assert len(receipt.transactions) == 11
    assert receipt.transactions[0].date == "2026-10-09"
    assert receipt.transactions[0].merchant == "STREAMY HD"


def test_happy_path_unfiltered_query_groups_breakdown_by_category(mock_transactions):
    """Unfiltered spending query groups breakdown by category rather than merchant."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        date_window=DateWindow(type="LAST_30_DAYS"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert len(receipt.breakdown) > 1
    breakdown_names = {b.category for b in receipt.breakdown}
    assert "Groceries" in breakdown_names or "Dining" in breakdown_names


def test_happy_path_transaction_list_returns_dated_line_items(mock_transactions):
    """TRANSACTION_LIST target_metric formats dated line items newest-first in summary."""
    spec = FinancialQuerySpec(
        target_metric="TRANSACTION_LIST",
        included_categories=["Subscriptions"],
        date_window=DateWindow(type="SIX_MONTHS"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 139.95
    assert len(receipt.transactions) == 11
    assert "Here are the 11 transactions totaling $139.95" in receipt.summary
    assert "• 2026-10-09: STREAMY HD — $15.00" in receipt.summary
    assert "• 2026-09-15: NETFLIX — $9.99" in receipt.summary


def test_happy_path_audit_mindful_spending_returns_three_golden_chips(monkeypatch):
    """POST /audit with MINDFUL_SPENDING returns the 3 verified Golden Demo Chips when ENABLE_GOLDEN_DEMO_CHIPS is enabled."""
    from auditor import config as auditor_config
    monkeypatch.setattr(auditor_config, "ENABLE_GOLDEN_DEMO_CHIPS", True)
    response = client.post("/audit", json={"goal": "MINDFUL_SPENDING", "payFrequency": "FORTNIGHTLY"})
    assert response.status_code == 200
    chips = response.json()["chips"]
    assert len(chips) == 3
    assert chips[0]["recency"] == "this_week"
    assert chips[0]["drilldown_prompt"] == "How much did I spend on cafes this week?"
    assert chips[1]["recency"] == "this_pay_cycle"
    assert chips[1]["drilldown_prompt"] == "How much did I spend on takeaway this pay cycle?"
    assert chips[2]["recency"] == "this_quarter"
    assert chips[2]["isSerendipity"] is True
    assert chips[2]["drilldown_prompt"] == "How much did I spend on utility bills this quarter?"


def test_happy_path_compute_endpoint_returns_breakdown_and_transactions():
    """POST /compute HTTP endpoint serializes breakdown and transactions in AuditReceipt JSON."""
    response = client.post(
        "/compute",
        json={
            "target_metric": "TOTAL_SUM",
            "included_categories": ["Subscriptions"],
            "date_window": {"type": "SIX_MONTHS"},
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert data["value"] == 139.95
    assert len(data["breakdown"]) == 2
    assert data["breakdown"][0]["category"] == "STREAMY HD"
    assert data["breakdown"][0]["amount"] == 90.0
    assert data["breakdown"][1]["category"] == "NETFLIX"
    assert data["breakdown"][1]["amount"] == 49.95
    assert len(data["transactions"]) == 11
    assert data["transactions"][0]["date"] == "2026-10-09"


def test_happy_path_this_pay_cycle_takeaway_2_week_window_and_bullets(mock_transactions):
    """THIS_PAY_CYCLE resolves to a 2-week (14-day) window (2026-09-29 to 2026-10-12) with 3 takeaway merchants and bulleted summary."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_merchants=["UBER *EATS", "DOORDASH", "ALDI DELIVERY"],
        date_window=DateWindow(type="THIS_PAY_CYCLE"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 158.02
    assert receipt.income_total == 3200.0
    assert len(receipt.breakdown) == 3
    assert receipt.breakdown[0].category == "UBER *EATS"
    assert receipt.breakdown[0].amount == 94.07
    assert receipt.breakdown[0].count == 3
    assert receipt.breakdown[1].category == "ALDI DELIVERY"
    assert receipt.breakdown[1].amount == 38.45
    assert receipt.breakdown[1].count == 1
    assert receipt.breakdown[2].category == "DOORDASH"
    assert receipt.breakdown[2].amount == 25.50
    assert receipt.breakdown[2].count == 1
    assert "• Window: 2026-09-29 to 2026-10-12 (THIS_PAY_CYCLE)" in receipt.summary
    assert "• Counted: merchants: ALDI DELIVERY, DOORDASH, UBER *EATS" in receipt.summary
    assert "• Excluded: pending transactions; internal transfers; credits, income and refunds" in receipt.summary

    # Follow-up: "let me see the ubereats transactions" within THIS_PAY_CYCLE
    followup_spec = FinancialQuerySpec(
        target_metric="TRANSACTION_LIST",
        included_merchants=["UBER *EATS"],
        date_window=DateWindow(type="THIS_PAY_CYCLE"),
    )
    followup_receipt = execute_spec_compute(followup_spec, mock_transactions)
    assert followup_receipt.value == 94.07
    assert len(followup_receipt.transactions) == 3
    assert "• 2026-10-04: UBER *EATS — $33.77" in followup_receipt.summary
    assert "• 2026-10-01: UBER *EATS — $28.90" in followup_receipt.summary
    assert "• 2026-09-29: UBER *EATS — $31.40" in followup_receipt.summary

