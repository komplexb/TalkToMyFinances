---
status: superseded by ADR-0004
---

# ADR 0001: Firestore as the Exclusively Decoupled Handshake Between Auditor and Android

The Auditor Engine (Python) and Android client communicate exclusively via Cloud Firestore document snapshots (`users/{userId}/audit/latest`), with no direct HTTP/REST connection between the mobile app and Cloud Run. This decouples mobile UI and AI logic development completely from data ingestion timelines and allows static fixture seeding during development.

