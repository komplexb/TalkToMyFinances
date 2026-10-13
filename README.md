# Talk to My Finances (TalkToMyFinances) – Firebase Android App

Welcome to **Talk to My Finances** (`TalkToMyFinances`), an AI-powered personal finance auditor and conversational companion built on Firebase and Android. Designed for everyday consumers, it strips away financial jargon and complex multi-chart dashboards, replacing them with **The Unasked Audit** (proactive, mathematically verified anomaly chips) and a **Gemini Live Conversational Companion**.

---

## 🚀 Features & Architecture

- **The Unasked Audit (Proactive Discovery)**: Reasons over transaction history across a 6-tier recency spectrum (Week to YTD) to surface proactive, positive prompt chips.
- **Conversational Companion (Gemini Live)**: Hands-free, low-latency conversational check-ins powered by the Gemini Live API / Firebase AI Logic.
- **Firebase AI Logic**: Manages cloud prompt templates, structured output generation, and model orchestration.
- **Firestore Enterprise Edition**: Secure, user-scoped storage for ledger data, verified facts, and conversation context.
- **Firebase Authentication**: Anonymous and authenticated user management ensuring strict data isolation.
- **Cloud Storage**: Secure storage for financial documents, statements, and receipts.
- **Remote Config**: Dynamic prompt parameters and feature flag control.
- **App Check**: Protects Firebase backend resources from abuse using Play Integrity.

---

## 📁 Repository Layout

This is a monorepo: the Android app, the Python backend, and the Firebase config live together.

```text
app/            Android app (Jetpack Compose + Firebase) — Domain A (Byron)
backend/        Python data layer: BankSync mock, client, auditor engine — Domain B (Kaz)
prompts/        Firebase AI Logic prompt templates
firestore.rules / storage.rules / firebase.json   Firebase backend config
docs/           (PRD & submission materials)
```

See [backend/README.md](backend/README.md) for how to run and test the Python side.

---

## 🛠️ Getting Started

1. **Prerequisites**:
   - Download and install [Android Studio](https://developer.android.com/studio).
   - Node.js & npm (for Firebase CLI tools).
   - [GitHub CLI](https://cli.github.com/) authenticated.

2. **Clone this repository**:
   ```sh
   git clone https://github.com/komplexb/TalkToMyFinances.git
   ```

3. **Open the project**:
   - Open `TalkToMyFinances` in Android Studio.

---

## 🔥 Setting Up Firebase

Choose the path below that applies to your role:

---

### Option A: Joining an Existing Team Project (Team Member Setup)

If your project lead or admin has already created and configured the Firebase backend:

1. **Get Project Access**: Ask your project lead to add your Google account under *Project Settings > Users and permissions* in the [Firebase Console](https://console.firebase.google.com/).
2. **Obtain `google-services.json` via Firebase CLI**:
   - Log in and select the active project:
     ```sh
     npx -y firebase-tools@latest login
     npx -y firebase-tools@latest use <PROJECT_ID>
     ```
   - List registered apps to retrieve the Android `<APP_ID>` for `com.talktomyfinances.android`:
     ```sh
     npx -y firebase-tools@latest apps:list --project <PROJECT_ID>
     ```
   - Download the app config into `app/google-services.json`:
     ```sh
     npx -y firebase-tools@latest apps:sdkconfig ANDROID <APP_ID> --project <PROJECT_ID> > app/google-services.json
     ```
3. **Register App Check Debug Token** *(For Emulators/Local Dev)*:
   - Run the app on an emulator and locate the secret printed in Logcat:
     ```text
     DebugAppCheckProvider: Enter this debug secret into the allow list
     in the Firebase Console for your project: (secret).
     ```
   - In [Firebase Console > App Check > Apps](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true), click the three dots for `com.talktomyfinances.android` > **Manage debug tokens** > add secret and save.

---

### Option B: Setting Up a New Firebase Project (Project Lead / Admin Setup)

If you are setting up a brand-new Firebase project from scratch for **Talk to My Finances**:

1. **Create Firebase Project & Register Android App**:
   ```sh
   npx -y firebase-tools@latest login
   npx -y firebase-tools@latest projects:create <YOUR_PROJECT_ID> --display-name "TalkToMyFinances"
   npx -y firebase-tools@latest apps:create ANDROID com.talktomyfinances.android --project <YOUR_PROJECT_ID>
   npx -y firebase-tools@latest apps:sdkconfig ANDROID <APP_ID> --project <YOUR_PROJECT_ID> > app/google-services.json
   ```

2. **Enable Blaze Plan Billing & Add AI Studio Prepayment Credits**:
   - Upgrade your project to the **Blaze (Pay-as-you-go) plan** in [Usage & Billing](https://console.firebase.google.com/project/_/usage/?useAutoProject=true).
   - Add prepayment credits in [Google AI Studio Projects](https://ai.studio/projects) or [AI Studio Plan Information](https://aistudio.google.com/app/plan_information). *(Note: Unbilled free-tier projects have a quota of 0 for Gemini image and multimodal endpoints).*

3. **Enable Firebase Authentication**:
   - Go to [Firebase Console > Authentication](https://console.firebase.google.com/project/_/authentication/?useAutoProject=true) and enable the **Anonymous** provider.

4. **Provision Databases & Security Rules**:
   - **Firestore (Enterprise Edition Required)**: Go to [Firestore Console](https://console.firebase.google.com/project/_/firestore/?useAutoProject=true), create a database using default ID **`(default)`**, and select **Enterprise Edition** when prompted. *(Note: This app uses Firestore Pipeline queries `firestore.pipeline()`, which require Enterprise Edition).*
     
     Under **Firestore > Rules**, configure:
     ```firestore-rules
     rules_version = '2';
     service cloud.firestore {
       match /databases/{database}/documents {
         match /{document=**} {
           allow read, write: if request.auth != null;
         }
       }
     }
     ```
   - **Cloud Storage**: Enable Storage in [Storage Console](https://console.firebase.google.com/project/_/storage/?useAutoProject=true) and deploy rules via CLI:
     ```sh
     npx -y firebase-tools@latest deploy --only storage --project <YOUR_PROJECT_ID>
     ```

5. **Configure App Check & Debug Tokens**:
   - Register Play Integrity with your SHA-256 fingerprint (`./gradlew signingReport`) under [App Check Console](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true). Add your emulator debug token under **Manage debug tokens**.

6. **Create Firebase AI Logic Prompt Templates**:
   - Enable the **Gemini Developer API** under [Firebase AI Logic Console](https://console.firebase.google.com/project/_/ailogic/?useAutoProject=true).
   - In [AI Logic > Prompt templates](https://console.firebase.google.com/project/_/ailogic/templates/?useAutoProject=true), create the required prompt templates:
     - **Base Foundation Templates** (using relative files in [`prompts/`](prompts/)):
       - `generate-recipe-template-v1-0-0` (from [`prompts/generate-recipe-template-v1-0-0.prompt`](prompts/generate-recipe-template-v1-0-0.prompt))
       - `generate-recipe-photo-gemini-template-v1-0-0` (from [`prompts/generate-recipe-photo-gemini-template-v1-0-0.prompt`](prompts/generate-recipe-photo-gemini-template-v1-0-0.prompt))
       - `scan-meal-template-v1-0-0` (from [`prompts/scan-meal-template-v1-0-0.prompt`](prompts/scan-meal-template-v1-0-0.prompt))
       - `find-stores-template-v1-0-0` (from [`prompts/find-stores-template-v1-0-0.prompt`](prompts/find-stores-template-v1-0-0.prompt))
     - **Talk to My Finances Core Templates**:
       - `financial-auditor-template-v1` (Personal Finance Anomaly & Audit Engine)
       - `unasked-audit-curation-template-v1` (6-tier Recency Spectrum Curation Engine)
       - `conversational-companion-template-v1` (Gemini Live Financial Companion)

---

## 🏃 Running the App Locally & Monitoring Logs

### 1. Start the Local Backend Engine (Monorepo)
To have the Android app communicate directly with the local Python Auditor engine over your live synthetic ledger:
```sh
cd backend
uv run uvicorn auditor.main:app --host 0.0.0.0 --port 8000 --reload
```

### 2. Reverse Port Forwarding to Android Device/Emulator
To allow the Android app running on an emulator or USB-connected physical phone to reach `http://127.0.0.1:8000` on your host Mac:
```sh
adb reverse tcp:8000 tcp:8000
```
*(Run this once whenever an emulator starts or after plugging in a physical device).*

### 3. Launch App & Monitor Logs
1. Open the project in Android Studio and run `app` on your Android Emulator or connected physical device.
2. View real-time application logs in Android Studio **Logcat** using filter:
   `package:com.talktomyfinances.android level:error`

---

## 🚢 How to Deploy to Production

When launching **Talk to My Finances** to production, follow these steps to deploy the backend resources, security rules, and production configurations:

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
```sh
# Option A: Store as a secret in Google Cloud Secret Manager (Recommended)
gcloud secrets create GEMINI_API_KEY --data-file=- <<< "<YOUR_GEMINI_API_KEY>"
gcloud run services update ttmf-banksync-mock \
  --region australia-southeast1 \
  --set-secrets GEMINI_API_KEY=GEMINI_API_KEY:latest

# Option B: Set directly as an environment variable
gcloud run services update ttmf-banksync-mock \
  --region australia-southeast1 \
  --set-env-vars GEMINI_API_KEY="<YOUR_GEMINI_API_KEY>"
```
After deployment, take note of the generated Cloud Run HTTPS URL (e.g., `https://ttmf-auditor-backend-abc123.a.run.app`).

### 3. Update Firebase Remote Config for Production
Set the production backend URL in the Firebase Console or via Remote Config template:
- Go to [Firebase Console > Remote Config](https://console.firebase.google.com/project/_/config).
- Set parameter `insights_base_url` to your live Cloud Run URL (`https://ttmf-auditor-backend-abc123.a.run.app`).
- Publish the changes. (Mobile clients will dynamically fetch this URL on startup).

### 4. Enforce App Check in Production
1. Ensure your production APK/AAB signing certificate fingerprint (SHA-256) is registered under Play Integrity in [App Check Console](https://console.firebase.google.com/project/_/appcheck/).
2. Switch App Check enforcement mode from **Unenforced / Monitoring** to **Enforced** for Firestore, Storage, and AI Logic to block unauthorized API requests.

---

## 📄 License
Proprietary. All rights reserved. No part of this codebase may be copied, reproduced, distributed, or forked without prior written permission.
