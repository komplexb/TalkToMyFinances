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
   - Each emulator generates a unique debug token on its first run.
   - Launch the app on your emulator and check Android Studio **Logcat** (filter for `DebugAppCheckProvider`):
     ```text
     DebugAppCheckProvider: Enter this debug secret into the allow list
     in the Firebase Console for your project: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
     ```
   - In [Firebase Console > App Check > Apps](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true), click the three dots (`⋮`) for `com.talktomyfinances.android` > **Manage debug tokens** > click **Add debug token**, paste your secret UUID, and save.
   - *Note*: If this token is not registered, Firebase App Check will block backend requests (e.g. after entering your pay date during onboarding), causing network calls to fail or freeze.

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

5. **Configure App Check & Debug Tokens** *(For Production & Local Dev)*:
   - For Production: Register Play Integrity with your SHA-256 fingerprint (`./gradlew signingReport`) under [App Check Console](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true).
   - For Local Emulator Dev: Launch the app on your emulator, search Android Studio **Logcat** for `DebugAppCheckProvider` to find your generated debug secret UUID:
     ```text
     DebugAppCheckProvider: Enter this debug secret into the allow list
     in the Firebase Console for your project: XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX
     ```
   - In [App Check Console](https://console.firebase.google.com/project/_/appcheck/?useAutoProject=true), click `com.talktomyfinances.android` $\rightarrow$ **Manage debug tokens** $\rightarrow$ **Add debug token**, paste the UUID secret, and save. *(Note: Every device/emulator generates its own debug token; all developers running the app locally must register their respective token).*

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

### 1. Configure Backend Environment & Start Engine (Monorepo)
The backend Curation Agent and Intent Parser require a Gemini API key (`GEMINI_API_KEY`).

#### Where Collaborators Get the Key (Project Credentials)
Since you are a collaborator on the `talktomyfinances-app` project, you already have access—no need to share keys privately:
- **Option A (Google Cloud Console - Recommended)**:
  1. Go to [Google Cloud Console > Credentials (talktomyfinances-app)](https://console.cloud.google.com/apis/credentials?project=talktomyfinances-app) (or click **View in Google Cloud** from Firebase Console *Project Settings*).
  2. Locate the backend API key (e.g. `Gemini-Backend-Developer-Key`) or click **+ CREATE CREDENTIALS > API key**.
  3. Copy the key string (`AIzaSy...`).
- **Option B (Google AI Studio)**:
  1. Go to [Google AI Studio > API Keys](https://aistudio.google.com/app/apikey).
  2. Click **Create API key** (or **Get API key**).
  3. Select the shared project: **`talktomyfinances-app`** (Project Number `430381251226`).
  4. Copy the generated key.

#### Configure Local Environment
1. **Configure `backend/.env`:**
   - Create or edit [`backend/.env`](backend/.env) (this file is `.gitignore`d to prevent credentials from being committed):
     ```env
     GEMINI_API_KEY=AIzaSy...
     ```
2. **Start Development Environment (One Command):**
   ```sh
   make dev     # or `npm run dev`
   ```
   *This automatically sets up `adb reverse` port forwarding and starts the local Python uvicorn backend on port 8000.*

---

### 2. Available Monorepo Commands (`Makefile` / `package.json`)

Instead of remembering paths or individual server flags, use the root shortcuts:

| Task               | Command                                 | Description                                                             |
|:-------------------|:----------------------------------------|:------------------------------------------------------------------------|
| **Start Dev Mode** | `make dev` *(or `npm run dev`)*         | Configures `adb reverse` port forwarding and starts the backend server. |
| **Backend Server** | `make backend` *(or `npm run backend`)* | Runs the Python uvicorn backend server on port 8000.                    |
| **ADB Reverse**    | `make reverse` *(or `npm run reverse`)* | Runs `adb reverse tcp:8000 tcp:8000` for physical devices/emulators.    |
| **Run All Tests**  | `make test` *(or `npm run test`)*       | Executes Android unit tests and Python backend tests.                   |
| **Build Android**  | `make build` *(or `npm run build`)*     | Assembles the Android debug APK.                                        |

---

### 3. Launch App & Monitor Logs
1. Open the project in Android Studio and run `app` on your Android Emulator or connected physical device.
2. View real-time application logs in Android Studio **Logcat** using filter:
   `package:com.talktomyfinances.android level:error`

---

## 🔍 Troubleshooting

### Emulator Connection Failure ("Emulator failed to connect within 5 minutes" / Freeze)
If the Android emulator fails to boot, freezes on launch, or gives a `failed to connect within 5 minutes` error:

1. **Clear Stuck Background Emulator Processes**:
   Open Terminal on your Mac and run:
   ```sh
   pkill -9 qemu-system
   pkill -9 emulator
   adb kill-server && adb start-server
   ```
2. **Wipe Data and Cold Boot the Virtual Device**:
   When an app crashes or freezes, Android Studio saves a corrupted Quick Boot snapshot that prevents future launches.
   - In Android Studio, open **Device Manager** (right sidebar).
   - Click the **three dots (`⋮`)** next to your emulator (e.g. `Medium Phone API 37.0`) $\rightarrow$ select **Wipe Data**.
   - Click the **three dots (`⋮`)** again $\rightarrow$ select **Cold Boot Now**.

### App Freezes / Network Errors After Pay Date Onboarding Step
If the app errors or hangs after submitting your pay date on the onboarding screen:
- This is caused by Firebase App Check blocking requests because your local emulator's unique debug token has not been registered in the Firebase Console yet.
- Follow Step 3 in **Option A (Team Member Setup)** above to locate `DebugAppCheckProvider` in **Logcat** and register your debug token under **App Check > Manage debug tokens**.

---

## 🚢 Production Deployment

For complete step-by-step instructions on deploying security rules, Cloud Run Python backend, GCP Secret Manager credentials, production Remote Config, and App Check enforcement, see:

👉 **[DEPLOY.md](DEPLOY.md)**

### 5. Deploy Gemini Live Client-Side Tool Calling & Voice Persona (PRD 6.2)
1. In [Firebase Console > Remote Config](https://console.firebase.google.com/project/_/config), verify the production template contains:
   - `live_model_name`: `gemini-3.1-flash-live-preview`
   - `live_model_prompt`: Contains the 5 voice persona rules including Rule 5 (Visual Offloading via `render_visual_card(card_type, payload)`).
2. Publish Remote Config changes.
3. In [Firebase Console > AI Logic](https://console.firebase.google.com/project/_/ailogic), ensure Gemini Developer API is enabled and quota is provisioned for Gemini Live bidirectional streaming.
4. Client cards mount directly from the self-contained WebSocket payload into `LazyColumn` and persist to Cloud Firestore (`users/{userId}/chat_history`) without secondary backend roundtrips.

---

## 📄 License
Proprietary. All rights reserved. No part of this codebase may be copied, reproduced, distributed, or forked without prior written permission.
