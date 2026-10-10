# ADR 0004: Android Calls the Cloud Run Auditor over REST

Supersedes ADR 0001. Android triggers the Auditor Engine and Curation Agent on demand via REST during the Insights Screen loading state, and the response carries the 3 Chips. We chose this over the Firestore handshake for fresher data on each visit and a single round trip (issue #15). The transport sits behind `InsightsRepository`, so a Firestore snapshot can replace it later.

