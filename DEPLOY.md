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
Set the production backend URL and model configurations in the Firebase Console or via Remote Config template:

1. Go to [Firebase Console > Remote Config](https://console.firebase.google.com/project/_/config).
2. Configure parameters:
   - `insights_base_url`: Your live Cloud Run URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`).
   - `auditor_base_url`: Your live Cloud Run URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`).
   - `chat_ai_model`: `gemini-3.8-flash` (intent parser model).
   - `judge_ai_model`: `gemini-3.5-flash-lite` (readability judge model).
   - `live_model_name`: `gemini-3.1-flash-live-preview` (Gemini Live voice streaming model).
   - `tts_model`: `gemini-3.1-flash-tts-preview`.
3. Publish the changes. *(Mobile clients dynamically fetch these parameters on startup).*

---

### 4. Enforce App Check in Production
1. Register your production APK/AAB signing certificate fingerprint (SHA-256 obtained via `./gradlew signingReport`) under Play Integrity in [App Check Console](https://console.firebase.google.com/project/_/appcheck/).
2. Switch App Check enforcement mode from **Unenforced / Monitoring** to **Enforced** for Firestore, Storage, and AI Logic to block unauthorized API requests.
