# Talk to My Finances – Production Deployment Guide

This guide details how to deploy the **Talk to My Finances** (`TalkToMyFinances`) backend services, Firebase security rules, environment secrets, and production configurations.

---

## 📋 Prerequisites & Tools

Before deploying to production, ensure you have installed and authenticated the required CLI tools:

- **Google Cloud SDK (`gcloud`)**: Authenticated with permission to deploy Cloud Run services and manage GCP Secrets.
  ```sh
  gcloud auth login
  gcloud config set project <PROD_PROJECT_ID>
  ```
- **Firebase CLI (`firebase-tools`)**: Authenticated with permission to manage the target Firebase project.
  ```sh
  npx -y firebase-tools@latest login
  ```

---

## 🚢 Production Deployment Steps

### 1. Deploy Firebase Security Rules & Storage Config
Deploy the Firestore security rules (`firestore.rules`) and Cloud Storage rules (`storage.rules`) to your production project:
```sh
npx -y firebase-tools@latest deploy --only firestore:rules,storage --project <PROD_PROJECT_ID>
```

### 2. Deploy Cloud Run Python Backend & Configure Secrets
Build and deploy the Python Auditor & Curation backend (`backend/`) using Google Cloud Build:
```sh
gcloud builds submit backend \
  --config backend/cloudbuild.yaml \
  --substitutions _MOCK_KEY=$(openssl rand -hex 16)
```

Ensure the Cloud Run service has access to your Gemini API Key (`GEMINI_API_KEY`) so the server-side Curation Agent and Intent Parser can execute LLM calls:

#### Option A: Store as a secret in Google Cloud Secret Manager (Recommended)
1. **Create the secret in your GCP project:**
   ```sh
   gcloud secrets create GEMINI_API_KEY \
     --replication-policy="automatic" \
     --project=<PROD_PROJECT_ID>
   ```

2. **Add the API key version (obtained from GCP Credentials or AI Studio):**
   ```sh
   echo -n "<YOUR_GEMINI_API_KEY>" | gcloud secrets versions add GEMINI_API_KEY \
     --data-file=- \
     --project=<PROD_PROJECT_ID>
   ```

3. **Mount the secret to the Cloud Run service:**
   ```sh
   gcloud run services update ttmf-banksync-mock \
     --region australia-southeast1 \
     --project=<PROD_PROJECT_ID> \
     --set-secrets GEMINI_API_KEY=GEMINI_API_KEY:latest
   ```

#### Option B: Set directly as an environment variable (Fast testing / Non-production)
```sh
gcloud run services update ttmf-banksync-mock \
  --region australia-southeast1 \
  --project=<PROD_PROJECT_ID> \
  --set-env-vars GEMINI_API_KEY="<YOUR_GEMINI_API_KEY>"
```

*After deployment, copy the generated Cloud Run HTTPS URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`).*

---

### 3. Update Firebase Remote Config for Production
Set the production backend URL, model configurations, and voice parameters in the Firebase Console or via Remote Config template:

1. Go to [Firebase Console > Remote Config](https://console.firebase.google.com/project/_/config).
2. Configure parameters:
   - `insights_base_url`: Your live Cloud Run URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`). Serves the `/audit` endpoint for the Insights Screen (including the 3 verified Golden Demo Chips for `MINDFUL_SPENDING`).
   - `auditor_base_url`: Your live Cloud Run URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`). Serves Track A verifiable calculations and transaction line-item drill-downs (`/compute` and `/query`, returning `AuditReceipt.breakdown` and `AuditReceipt.transactions`). Track B conversational guidance executes client-side via Firebase AI Logic.
   - `chat_ai_model`: `gemini-3.8-flash` (intent parser model using declarative `generationConfig.responseSchema` constrained decoding for `FinancialQuerySpec`, including `TRANSACTION_LIST`, `THIS_WEEK`, and `LAST_WEEK`).
   - `judge_ai_model`: `gemini-3.5-flash-lite` (readability judge model).
   - `live_model_name`: `gemini-3.1-flash-live-preview` (Gemini Live voice streaming model).
   - `live_model_voice`: `Puck` (overridable in console with any Gemini studio voice, e.g., `Aoede`, `Charon`, `Fenrir`, `Kore`).
   - `live_model_prompt`: Canonical voice persona system prompt (see Section 5 below).
   - `tts_model`: `gemini-3.1-flash-tts-preview`.
3. Publish the changes. *(Mobile clients dynamically fetch these parameters on startup from `remote_config_defaults.xml` fallbacks).*

---

### 4. Enforce App Check in Production
1. Register your production APK/AAB signing certificate fingerprint (SHA-256 obtained via `./gradlew signingReport`) under Play Integrity in [App Check Console](https://console.firebase.google.com/project/_/appcheck/).
2. Switch App Check enforcement mode from **Unenforced / Monitoring** to **Enforced** for Firestore, Storage, and AI Logic to block unauthorized API requests.

---

### 5. Gemini Live Client-Side Tool Calling & Visual Offloading (PRD 6.2)
1. In [Firebase Console > Remote Config](https://console.firebase.google.com/project/_/config), configure `live_model_prompt` with the canonical 6-guardrail voice persona prompt:
   ```text
   You are the calm, articulate, and empathetic financial voice companion for Talk to My Finances.

   Core Voice Persona Guardrails:
   1. Acoustic Persona & Delivery:
      - Speak in a clean, natural, articulate tone.
      - Ground all discussions in Australian financial context ($ AUD currency, fortnightly pay cycles, local retail terms like supermarkets, fuel, cafes, chemists).
      - Strictly avoid exaggerated regional drawls, caricatures, or stereotypical colloquial slang. Never say 'g'day', 'mate', 'fair dinkum', 'crikey', or similar caricatures. Speak standard, professional, conversational English.
   2. Brevity Cap & Flexible Expression:
      - Your spoken answers MUST be concise—strictly 2 to 3 spoken sentences maximum.
      - Spoken acknowledgments must vary naturally and contextually (e.g. 'Looking at your recent dining...', 'Here is what came through for groceries...', 'Taking a look at your bills...'). Avoid repetitive or wooden canned phrases.
   3. No Spoken Tables:
      - NEVER read long transaction lists, raw data tables, CSV strings, or detailed ledger calculations aloud.
      - Give a concise high-level takeaway and offload the structured breakdown to the visual screen.
   4. Proactive Visual Offloading:
      - Proactively trigger the client-side tool render_visual_card(card_type, payload) over WebSockets whenever a visual breakdown, chart, or category distribution effectively supports your verbal answer (such as category spending breakdowns, multi-month trends, or comparison across periods)—do not wait for an explicit user command to display a card.
      - Accompany every visual card with a brief, context-aware verbal summary.
   5. Tool Schema Contract:
      - When calling render_visual_card, the payload MUST be a structured JSON object containing: title (string), total_amount (number), timeframe (string), items (array of category/amount/percentage), and audit_receipt (a JSON object with 'summary' string and 'value' number, e.g. {"summary": "...", "value": 123.45}, NEVER a plain string).
   6. Out-of-Catalog Boundaries & Anti-Hallucination:
      - If the user asks about out-of-catalog topics (such as cryptocurrency, investments/shares, tax deductions, or unlinked external accounts) or questions that require missing data, explain the boundary conversationally and gracefully (e.g. "I can only track verified transactions from your connected bank accounts, so I don't have visibility into crypto or investments.").
      - Never invent, estimate, or hallucinate dollar amounts or arithmetic.
   ```
2. Verify in [Firebase Console > AI Logic](https://console.firebase.google.com/project/_/ailogic) that the Gemini Developer API is enabled and quota is provisioned for `gemini-3.1-flash-live-preview`.
3. Visual Card offloading is 100% self-contained from the WebSocket tool call payload (`render_visual_card`); cards mount directly into the Jetpack Compose `LazyColumn` message thread and sync state to Cloud Firestore under `users/{userId}/chat_history` without requiring secondary REST calls to Cloud Run.
