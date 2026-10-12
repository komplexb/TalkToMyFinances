from fastapi.testclient import TestClient
from auditor.main import app, FACT_CATALOG

client = TestClient(app)

def test_audit_endpoint_returns_three_chips():
    response = client.post("/audit", json={"goal": "SAVINGS", "payFrequency": "FORTNIGHTLY"})
    assert response.status_code == 200
    data = response.json()
    assert "chips" in data
    chips = data["chips"]
    assert len(chips) == 3
    serendipity_count = sum(1 for c in chips if c.get("isSerendipity") is True)
    assert serendipity_count == 1

def test_query_endpoint_parser_spec_compute():
    response = client.post("/query", json={"query": "What is my total spend on Uber Eats?"})
    assert response.status_code == 200
    data = response.json()
    assert "summary" in data
    assert "value" in data
    assert "spec" in data
    spec = data["spec"]
    assert "target_metric" in spec
    assert "included_merchants" in spec

def test_query_endpoint_monthly_average():
    response = client.post("/query", json={"query": "What is my average monthly spend on Uber Eats?"})
    assert response.status_code == 200
    data = response.json()
    assert data["value"] is not None
    assert isinstance(data["value"], (int, float))

def test_compute_endpoint_total_sum():
    payload = {
        "target_metric": "TOTAL_SUM",
        "included_merchants": ["Uber Eats"],
        "excluded_merchants": [],
        "included_categories": [],
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
    assert data["spec"]["target_metric"] == "TOTAL_SUM"
    assert "Uber Eats" in data["spec"]["included_merchants"]

def test_compute_endpoint_monthly_average():
    payload = {
        "target_metric": "MONTHLY_AVERAGE",
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

