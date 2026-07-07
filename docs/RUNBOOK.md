# AIRI Operations Runbook

## Certificate Pinning Rotation Procedure (AP-01)

**File:** `connector/api/LlmCertPins.kt`

### When to rotate

Rotate pins when any of the following events occur:
- A pinned provider changes their TLS certificate chain (CDN migrations, CA renewals)
- A pin expiry approaches (see expiry dates in `LlmCertPins.kt`)
- A certificate breach is publicly disclosed

### How to extract a fresh SPKI pin

Run this command for each host (replace `<host>` with the API hostname):

```bash
openssl s_client -connect <host>:443 </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary \
  | base64
```

**Hosts to check:**
```
api.openai.com
api.anthropic.com
generativelanguage.googleapis.com
```

### Rotation steps

1. Run the `openssl` command above for each host. Copy the base64 output.
2. Update `LlmCertPins.kt`:
   - If the **primary** cert is being replaced: move the current primary → backup, put new pin in primary.
   - If the **backup** cert is being replaced: replace backup only.
   - Always keep **two pins per host** to allow a rotation window.
3. Set `PINNING_ENABLED = true` (should already be `true` in production).
4. Test on a physical device and verify API calls succeed.
5. Test via mitmproxy: `mitmproxy --mode transparent` — API calls must reject with `SSLPeerUnverifiedException`.
6. Commit and tag the release.
7. Deploy. Monitor crash logs for `SSLPeerUnverifiedException` for 24 hours post-deploy.

### Rollback

Set `PINNING_ENABLED = false` in `LlmCertPins.kt`. This is an instant rollback with no data loss.
Investigate the pin mismatch before re-enabling.

### Emergency pin rotation

If a cert breach is discovered in production:
1. Immediately set `PINNING_ENABLED = false` and release a hotfix to stop user lockout.
2. Extract fresh pins using the `openssl` commands above.
3. Update `LlmCertPins.kt` with verified hashes.
4. Re-enable `PINNING_ENABLED = true`.
5. Deploy the pin-updated build.
6. Monitor for 24 hours.

---

## Database Key Rotation

The SQLCipher encryption key is stored in the Android Keystore via `SecureStorage`.
Key rotation requires:
1. Export current database to plaintext (decrypt).
2. Generate a new key.
3. Re-encrypt with new key.
4. Wipe old key from Keystore.

Contact the security team before attempting key rotation on production databases.

---

## Audit Log Retention

Audit log entries older than 30 days are automatically pruned by `ScheduledJobOrchestrator`
(`audit_log_pruner` job, every 24 hours). To adjust retention, change the `cutoff` parameter
in the `ServiceLocator` scheduled job registration.

