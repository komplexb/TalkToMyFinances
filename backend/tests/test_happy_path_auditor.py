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
    """Micro-Win 4: Seasonal utility bills drop this quarter ($105.83)."""
    spec = FinancialQuerySpec(
        target_metric="TOTAL_SUM",
        included_categories=["Utilities"],
        date_window=DateWindow(type="THIS_QUARTER"),
    )
    receipt = execute_spec_compute(spec, mock_transactions)
    assert receipt.value == 105.83
    assert "across 1 transaction" in receipt.summary


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
