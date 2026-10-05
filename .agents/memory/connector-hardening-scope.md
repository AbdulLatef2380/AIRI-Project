---
name: Connector hardening scope
description: User's explicit scope for making the connector layer production-ready.
---

Do not disable, hide, or disconnect existing connectors as a substitute for resolving production gaps. Preserve connector availability and report unsupported or unconfigured behavior explicitly until it is implemented.

**Why:** The user clarified that the goal is to identify and close deployment gaps, not to shut down connectors.

**How to apply:** Keep connector registrations visible and enabled while fixing credential flows, execution wiring, permission and approval gates, and honest failure reporting.
