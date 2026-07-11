# security — Security Enforcement

## Components

### SecretHealthChecker
Scans all stored API keys for validity indicators (format, placeholder values, length). Returns `HealthResult` enum: `OK`, `WARNING`, or `CRITICAL`. Used by `SecurityScannerScreen`.

**Constructor:** `SecretHealthChecker(SecureStorage)` — not `Context`.

### ExecutionFirewall
Prevents the agent from executing dangerous system operations (e.g. deleting system files, accessing restricted packages, exfiltrating data). Called by `AgentLoop` before every tool dispatch.

### LlmCertPins
Hardcoded SHA-256 SPKI pins for:
- `api.openai.com`
- `api.anthropic.com`
- `generativelanguage.googleapis.com`
- `openrouter.ai`

**⚠️ Maintenance required:** Re-verify pins with `openssl s_client` before each production release. Current pin expiry: **June 2027** (in `network_security_config.xml`).

## Encryption

API keys are stored in `EncryptedSharedPreferences` with:
- Key encryption: AES256_SIV
- Value encryption: AES256_GCM
- Key stored in Android Keystore (hardware-backed on supported devices)

Fallback: if `EncryptedSharedPreferences` fails to initialize (e.g. after OS upgrade corrupting the keystore), `SecureStorage` falls back to in-memory storage only — no unencrypted disk writes.

## FileProvider

Configured in `AndroidManifest.xml` with authority `${applicationId}.fileprovider`. Paths defined in `res/xml/file_paths.xml`:
- `files-path/attachments/` — persisted chat attachments
- `cache-path/` — temporary shares
- `external-cache-path/` — external cache

All URIs require explicit `FLAG_GRANT_READ_URI_PERMISSION` — the provider is not exported.

## Status

- API key encryption: **Production-ready**
- SSL pinning: **Active** (expires June 2027)
- FileProvider: **Configured** (manifest + paths + correct authority)
- PendingIntents: **All use FLAG_IMMUTABLE**
- WebView: **Hardened** (JS off, file access off)
- SQL: **Safe** (all Room queries parameterized)
