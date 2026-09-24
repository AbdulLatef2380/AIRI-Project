---
id: telegram
name: Telegram
provider: Telegram
category: Communication
authentication: API_KEY
status: PARTIAL
author: AIRI
website: https://core.telegram.org/bots/api
---

# Telegram Connector

This document describes the AIRI connector catalog entry for **Telegram**. The entry is an AIRI adapter definition, not a claim that AIRI is the provider or owner of the external service.

## Status

`PARTIAL` is intentionally truthful. `PARTIAL` means AIRI has an adapter path or existing integration, but provider configuration and credentialed validation are still required. `COMING_SOON` means this catalog entry is discoverable but has no executable adapter and cannot be connected.

## Authentication

The declared authentication family is `API_KEY`. AIRI must use the provider's official authorization or credential mechanism; it must never request the provider password. Credentials must remain in encrypted platform storage and must not appear in logs, UI state, Git, or build artifacts.

## Permissions and capabilities

Only capabilities declared by the executable adapter may be exposed to the Agent. Read operations should be silent after authorization. Write, destructive, and administrative operations require a typed permission decision and, where applicable, user confirmation and idempotency context.

## Official provider

- Website: https://core.telegram.org/bots/api

Additional OAuth, privacy, and developer URLs must be added only after verification against the provider's official documentation.
