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
gcloud builds submit backend --config backend/cloudbuild.yaml `
  --substitutions _AR_REPO=<your-registry>,_MOCK_KEY=mock-key
```

## Conventions

- Credits are positive, debits negative; `creditAmount`/`debitAmount` are
  nonnegative. Amounts were generated from integer cents.
- Treat cursors as opaque strings.
- The client reports HTTP failures and partial results rather than silently
  treating them as complete. No automatic retries yet.
- The mock is a prototype: no balances/accounts routes, no pending settlement,
  no rate limits. Verify Australian response behaviour before production use.
