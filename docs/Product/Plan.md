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

---

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
[x] /setup-matt-pocock-skills: use GitHub Issues so you and Kaz share one board.
[x] /grill-with-docs with Kaz, or /to-questionnaire first, to settle the Firestore fact contract and write it up as an ADR.
[ ] /to-spec, then /to-tickets. Tickets fall out naturally on either side of the contract:
Yours: a hand-seeded facts document → 3 chips on the home screen → tap one → a Gemini answer that quotes fact fields → deployed. Then text chat, then voice, then render_visual_card.
Kaz's: BankSync → scrub → auditor → writes the same document. Your tickets don't wait on his, because the seeded document has the same shape.
/implement per ticket, or /implement-spec for the whole spec. Use /wizard for the Firebase and Cloud Run console steps so you can deploy on Sunday.

Kaz's 100 synthetic transactions are a good shared fixture: compute the facts for your seeded document from them, so the demo numbers match what his auditor will produce later
---

## Grill Me

### 1-3

[ ] Specs
[ ] Tickets

### Technical

#### 4

[ ] Specs
[ ] Tickets

#### 5

[ ] Specs
[ ] Tickets

#### 6

[ ] Specs
[ ] Tickets

#### 7

[ ] Specs
[ ] Tickets

#### 8

[ ] Specs
[ ] Tickets

#### 6

[ ] Specs
[ ] Tickets
