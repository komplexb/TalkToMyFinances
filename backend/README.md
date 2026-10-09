# backend/ — Python data layer

The data/ingestion half of **Talk to My Finances** (hackathon Domain B).
Currently contains the local BankSync mock and its client; the deterministic
auditor engine (PII scrubbing → 10 verified facts) lands here next.

## Layout

```text
backend/
  banksync/
    main.py                 Example CLI consumer (prints credit/debit totals)
    banksync_client.py      HTTP calls + cursor pagination (env-configured base URL)
  mock_banksync/
    main.py                 FastAPI mock: key check, date filter, cursor paging
    data/transactions.json  100 fixed synthetic AUD records (Jul–Sep 2026)
  tests/
    test_mock.py            Behaviour + schema tests
    transaction-response.schema.json  BankSync's published 200-response schema
  Dockerfile                Cloud Run image for the mock
  cloudbuild.yaml           Build → push → deploy pipeline
  .env.example              Copy to .env (gitignored); dummy values only
```

## Local development

```powershell
cp .env.example .env
uv sync
```

The `change-me` values in `.env` are local placeholders — the two key lines
must simply match each other. If you will ever share your endpoint, replace
them with a random value: `openssl rand -hex 16`.

Terminal 1 — start the mock (note `0.0.0.0`: the Android emulator reaches the
host machine via `10.0.2.2`):

```powershell
uv run -m uvicorn mock_banksync.main:app --host 0.0.0.0 --port 8000
```

Terminal 2 — run the example client and the tests:

```powershell
uv run python -m banksync.main --from 2026-07-01 --to 2026-09-30
uv run -m pytest -q
```

Swagger UI: http://127.0.0.1:8000/docs

## Deployment target (Cloud Run)

`Dockerfile` + `cloudbuild.yaml` build the mock into an Artifact Registry image
and deploy it to Cloud Run, yielding a public HTTPS URL like
`https://ttmf-banksync-mock-abc123.a.run.app`.

Once deployed, the auditor engine points its `BANKSYNC_BASE_URL` at that URL —
the Android app itself only talks to Firebase, never to the mock directly.

```powershell
# 1. Generate a one-off key and keep it out of git/shell history where possible:
gcloud builds submit backend --config backend/cloudbuild.yaml `
  --substitutions _MOCK_KEY=<paste-key-here>   # e.g. the output of: openssl rand -hex 16
# _AR_REPO defaults to "ttmf"; override if your Artifact Registry repo has a different name
```

### Cost & abuse

- **`min-instances 0`** — the service scales to zero when nobody is calling it, so an idle prototype costs **$0**. (Trade-off: ~1s cold start on the first request after idle — invisible in a demo.)
- **`max-instances 10`** — hard ceiling on concurrency, so a malicious or runaway caller can burn a few dollars an hour at most, not unbounded.
- The data behind the endpoint is synthetic; there is nothing worth stealing. The deploy-time key (never stored in this repo) is the only gate — if this ever serves real bank data, switch to IAP + a KMS secret (see the note in `cloudbuild.yaml`).
- Before your first deploy: set a **billing budget alert** (GCP Console → Billing → Budgets, e.g. email me at $5) as a tripwire.

## Conventions

- Credits are positive, debits negative; `creditAmount`/`debitAmount` are
  nonnegative. Amounts were generated from integer cents.
- Treat cursors as opaque strings.
- The client reports HTTP failures and partial results rather than silently
  treating them as complete. No automatic retries yet.
- The mock is a prototype: no balances/accounts routes, no pending settlement,
  no rate limits. Verify Australian response behaviour before production use.
