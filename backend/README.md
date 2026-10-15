# backend/ — Python data layer

The data/ingestion half of **Talk to My Finances** (hackathon Domain B):
the local BankSync mock, its client, and the deterministic auditor engine.

## Layout

```text
backend/
  banksync/
    main.py                 Example CLI consumer (prints credit/debit totals)
    banksync_client.py      HTTP calls + cursor pagination (env-configured base URL)
  mock_banksync/
    main.py                 FastAPI mock: key check, date filter, cursor paging
    data/generate.py        Deterministic dataset generator (seeded; anchored to --as-of)
    data/transactions.json  Generated data: ~13 months of synthetic AUD records
  auditor/
    main.py                 FastAPI: POST /audit (Curation Agent) + POST /query (Parser-Spec-Compute)
    models.py               FinancialQuerySpec / AuditReceipt schemas (ADR 0003)
  tests/
    test_mock.py            Behaviour + schema tests for the mock
    test_auditor.py         Auditor tests (compute path is fully deterministic)
    transaction-response.schema.json  BankSync's published 200-response schema
  Dockerfile                Cloud Run image for the mock
  cloudbuild.yaml           Build → push → deploy pipeline (mock)
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

Terminal 1 — start the server from root directory (Note: `make dev` maintains background ADB reverse `tcp:8000 -> tcp:8000` for emulators and USB devices; use `make dev-device` for physical devices over Wi-Fi/LAN):

```bash
make dev        # or `make dev-device` for physical device LAN testing
```

Alternatively, start uvicorn directly (note `0.0.0.0`: the Android emulator reaches the
host machine via `10.0.2.2` or via `adb reverse`):

```powershell
uv run -m uvicorn auditor.main:app --host 0.0.0.0 --port 8000
```

Terminal 2 — run the example client and the tests (the client's default range
is derived from the generated dataset):

```powershell
uv run python -m banksync.main
uv run -m pytest -q
```

The mock dataset is *generated*, not hand-authored (see
`mock_banksync/data/generate.py`): it is crafted so that every prompt-chip
recency tier (This Week → YTD) has a positive "micro-win" to demonstrate
(PRD 3.2 / issue #2).

**The demo clock is pinned, so the deployment never goes stale.** The dataset
is a frozen snapshot, and the mock endpoint's default date window is anchored
to the dataset's own last date (its `--as-of`), *not* the wall clock — so
"This Week" / "Last 30 Days" queries keep working indefinitely after deploy,
with no refresh. Set `BANKSYNC_MOCK_AS_OF=YYYY-MM-DD` in the environment to
pin an explicit date instead. Regenerate and redeploy only if you want to
re-anchor the story to a new date (the pin follows the dataset automatically):

```powershell
uv run python mock_banksync/data/generate.py --as-of 2026-11-10
```

Swagger UI: http://127.0.0.1:8000/docs

## Auditor engine (`auditor/`) — ADR 0003

The Python Auditor Engine behind the Insights and chat flows:

- **`POST /audit`** — Curation Agent: picks 3 prompt chips (2 goal + 1 serendipity)
  from the 10-Fact catalog, with a deterministic fallback when no LLM key is set.
- **`POST /query`** — Parser-Spec-Compute for out-of-catalog math (issue #3, ADR 0003):
  1. Gemini (structured output, schema-constrained) parses a freeform query into a
     `FinancialQuerySpec` — or marks it `is_out_of_spec` (synthesis / "what if" queries);
  2. the engine grounds the spec's entities against the ledger's real vocabulary
     (a hallucinated merchant → 422 `entity_not_found` with "did you mean…");
  3. a pure, unit-tested function computes the number — **the LLM never does arithmetic**;
  4. the `AuditReceipt` echoes every assumption in plain English: matched entities,
     exclusions, pending/refund handling, window, and denominator.
  A pre-parsed `spec` may be posted directly (client-side parsing); the compute path
  then needs no API key at all.

Run it locally (the compute path works without any key):

```powershell
uv run -m uvicorn auditor.main:app --host 0.0.0.0 --port 8001
```

Add `GEMINI_API_KEY` to `.env` to enable the freeform-`query` parse path
(and LLM-assisted chip selection in `/audit`).

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
- **`max-instances 1`** — the mock is stateless, so one instance (≈80 concurrent requests) is plenty for a prototype. Even a hammering caller tops out at one 512 Mi instance: a few cents an hour at full load, not unbounded.
- The data behind the endpoint is synthetic; there is nothing worth stealing. The deploy-time key (never stored in this repo) is the only gate — if this ever serves real bank data, switch to IAP + a KMS secret (see the note in `cloudbuild.yaml`).
- Before your first deploy: set a **billing budget alert** (GCP Console → Billing → Budgets, e.g. email me at $5) as a tripwire.

## Deploying the auditor (ADR 0003)

Same pattern as the mock — a second stateless Cloud Run service (`ttmf-auditor`):

1. **Image.** The current `Dockerfile` copies only `mock_banksync/`. For the
   auditor, add a variant (or an extra build stage in `cloudbuild.yaml`) that
   copies `auditor/` + `mock_banksync/data/` (the compute path reads the
   generated dataset from disk) and runs `uvicorn auditor.main:app`.
2. **Secret.** Create the Gemini key as a secret, never a plain `--env`:

   ```powershell
   echo -n "<gemini-api-key>" | gcloud secrets create ttmf-gemini-key --data-file=-
   ```
3. **Deploy** (mirrors the mock's cloudbuild, with the secret attached):

   ```powershell
   gcloud run deploy ttmf-auditor \
     --image <your-auditor-image> \
     --region australia-southeast1 \
     --min-instances 0 --max-instances 1 \
     --secret ttmf-gemini-key:GEMINI_API_KEY
   ```

Notes:

- The service works with **zero** API keys — the compute path and the
  `/audit` deterministic fallback need no LLM; the key only enables freeform
  query parsing and LLM-assisted chip selection.
- The app's `insights_base_url` (ADR 0004) points at this service; the Android
  app never talks to the mock directly.
- The dataset is a frozen snapshot, so the service never goes stale — same
  reasoning as the mock's pinned demo clock.

## Conventions

- Credits are positive, debits negative; `creditAmount`/`debitAmount` are
  nonnegative, and `amount = creditAmount - debitAmount` holds on every row.
  The dataset is generated by `mock_banksync/data/generate.py` (see above).
- Treat cursors as opaque strings.
- The client reports HTTP failures and partial results rather than silently
  treating them as complete. No automatic retries yet.
- The mock is a prototype: no balances/accounts routes, no pending settlement,
  no rate limits. Verify Australian response behaviour before production use.
