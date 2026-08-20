# Connector package

This package contains adapters and configuration for external services.

## Current contract

A connector must expose its real availability state before the UI suggests it as usable. Secrets belong in encrypted storage and must never be logged. Any action with external side effects requires an explicit user confirmation in the calling UI.

## Limitations

Provider integrations depend on user credentials, network availability, provider policy, and live API verification. A visible integration or preference is not evidence that the external service has been successfully exercised.

## Verification

Build-time and live-credential verification are pending in this workspace because Android Gradle Plugin dependencies cannot currently be resolved from the configured repositories.
