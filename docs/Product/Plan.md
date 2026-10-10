PRD: @docs/Product/PRD_Talk_to_My_Finances.md

The PRD outlines our approach for our submission to the AI Builder Hackathon outlined in docs/AIBuilderOfficial/AI Builder Cup Introductory & Problem Statement Explainer Session.md.

The Gap Analysis in @docs/Product/Gap%20Analysis.md outlines the gaps between the FriendlyMeals-Android sample project and our target prototype, "Talk to My Finances."
The sample project gives us an idea of what Android app architecture and Firebase plumbing looks like, but we will need to build a lot of new functionality to meet the requirements of our prototype. We'll also need to cull what we don't need from the sample project, and make sure that the remaining code is clean and well-structured.

A loose timeline comes from the project organisers:
> What to get done this weekend (Oct 10-11)

> Saturday: lock the team and the problem (Sat, Oct 10)

> Add your members on the dashboard, pick one of the six problem statements, and agree the single thing your prototype has to do.

> Sunday: get something running and deployed (Sun, Oct 11)

> A rough version is fine. Deploy it on Cloud Run or Firebase now rather than on the 17th, because deployment is where most teams lose their last day.

> Before Monday: read the submission rules (Mon, Oct 12)

> The deck template, the video length limit and the prototype uptime rules are all in the submission section. Knowing them now shapes what you build next week.

With the aforementioned context in mind we need to create a plan to vertically slice work from the PRD to have a working prototype by Thursday, Oct 15.

## Downstream Task Generation Blueprint

[x] Phase 0: Start building to feel what’s possible

* Choose a Flutter or Android Template
* Get it to run and understand the quirks and constraints
* Decide if we need to port from an existing template or start from scratch

Copied from the PRD: Breaking engineering work down into 4 distinct implementation domains:

├── Domain A: Flutter or Android & Firebase AI Logic (UI & Voice)
│   ├── \[ \] Build Flutter home shell with 3 proactive prompt card widgets
│   ├── \[ \] Connect Firebase AI Logic SDK & Gemini Live WebSocket stream
│   ├── \[ \] Implement \`render\_visual\_card\` client handler for inline charts
│   └── \[ \] Build voice/text mode toggle with state persistence in Firestore
│
├── Domain B: Python Auditor Engine & Privacy Boundary (Kaz – Data Engineer)
│   ├── \[ \] Build local PII scrubbing & merchant string normalization in Python
│   ├── \[ \] Code deterministic Python math catalog (10 Verified Facts generator)
│   ├── \[ \] Build BankSync REST API loader (\`GET /v1/accounts\`, \`GET /v1/transactions\`)
│   └── \[ \] Build synthetic CSV data generator for local development/testing
│
├── Domain C: Agentic Curation & Meta-Prompting (LLM Layer)
│   ├── \[ \] Implement Curation Agent meta-prompt (ranking 10 facts down to 3 chips)
│   ├── \[ \] Configure Gemini Live system instructions & voice persona constraints
│   └── \[ \] Build "What-If" scenario simulation prompt templates
│
└── Domain D: Cloud Deployment & Security (GCP / Firebase)
    ├── \[ \] Deploy Python Auditor endpoints to Cloud Functions for Firebase
    ├── \[ \] Configure Firebase App Check & Firestore security rules

Phase 0 is complete. We have a working prototype of the FriendlyMeals-Android sample project running on our local machines and wired up to our Firebase project instance. We have also decided to commit to Native Android for our prototype, "Talk to My Finances."

The 4 domains however aren't vertically sliced if we are thinking of vertical slices, as top down feature demonstrable feature. Also the 4 domains seems to miss much details from the PRD body.

The chat and voice interfaces depend on the backend that Kaz is building out, so its not clear to me what the app would be talking to without the backend housing our apis/tool calls.

I think for starters I can build out the UI and the firebase plumbing according to the designs:

* Settings
* Onboarding
* Chat UI
* Voice Toggle
* Find/Figure out a Material Design template
* Configure the Hybrid Inferencing
* Design the system prompts
* Test

Basically build the UI for the backend to drive, and in the mean time we just talk to the model as is, and stitch in our capabilities as they come online.

---
Updated route
/setup-matt-pocock-skills: use GitHub Issues so you and Kaz share one board.
/grill-with-docs with Kaz, or /to-questionnaire first, to settle the Firestore fact contract and write it up as an ADR.
/to-spec, then /to-tickets. Tickets fall out naturally on either side of the contract:
Yours: a hand-seeded facts document → 3 chips on the home screen → tap one → a Gemini answer that quotes fact fields → deployed. Then text chat, then voice, then render_visual_card.
Kaz's: BankSync → scrub → auditor → writes the same document. Your tickets don't wait on his, because the seeded document has the same shape.
/implement per ticket, or /implement-spec for the whole spec. Use /wizard for the Firebase and Cloud Run console steps so you can deploy on Sunday.

Kaz's 100 synthetic transactions are a good shared fixture: compute the facts for your seeded document from them, so the demo numbers match what his auditor will produce later
---

the thread mentioned some ambiguity and pitched some questions to Kaz and myself.
both kaz and I are noobs when it comes to android and firebase. we want the AI (you) to leverage the `/firebase-**` skills to wisely direct us according to our expressed intent in the [PRD_Talk_to_My_Finances.md](file;file:///Users/byronbuckley_1/Dev/projects/TalkToMyFinances/docs/Product/PRD_Talk_to_My_Finances.md) and the goals and constraints outlined in [docs](directory;file:///Users/byronbuckley_1/Dev/projects/TalkToMyFinances/docs)

One clarification is that his synthetic transactions are wht we will use for the prototype, no authentication or talking to the real banksync.io.
a real world setup would be user account + banksync auth to individual bank accounts. the prototype skips all of that and demonstrates the core functionality of the app with synthetic data.
