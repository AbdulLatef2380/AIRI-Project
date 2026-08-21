# Gate — Android-to-Desktop Remote Control Foundation

## Decision

AIRI will not expose arbitrary Windows control. Android may control **only AIRI-owned desktop commands** through an explicitly paired, short-lived session. The shared contract limits commands to status, state synchronization, a new draft, a bounded text request, and cancellation of an AIRI-owned request. It rejects arbitrary keyboard injection, shell execution, screen capture, arbitrary app launching, and unrestricted file access.

| Control | Product state | Evidence classification | Evidence |
|---|---|---|---|
| Shared command, pairing, security, and transport contracts | Implemented | `BUILD_VERIFIED` | Platform-neutral contracts compile with `core-domain` and shared tests cover policy behavior. |
| Ordered command acceptance and replay protection | Implemented | `TESTED` | The common test fixture and policy tests reject replayed or out-of-order sequences. |
| Pairing ownership and revocation | Implemented | `TESTED` | Shared policy tests cover controller identity, owner binding, device revocation, and later command rejection. |
| Rate limits and payload-safe audit records | Implemented | `TESTED` | Shared tests cover windowed limits; audit records contain metadata only, not text payloads or credentials. |
| Firestore owner isolation and command validation | Implemented | `TESTED` | Firestore Emulator permits only a valid owner command with an active session and rejects unauthenticated, foreign-owner, unknown, oversized, expired, mutable, and deletable requests. |
| Desktop command dispatcher | Partial adapter | `BUILD_VERIFIED` | Dispatcher accepts only prevalidated AIRI-owned commands and has no direct network transport. |
| Android authenticated controller adapter | Not implemented | `SOURCE_VERIFIED` | No Android adapter binds authenticated Firebase identity, device storage, and command transport yet. |
| Desktop local pairing approval adapter | Not implemented | `SOURCE_VERIFIED` | No local approval UI or platform token storage adapter exists yet. |
| Production relay and encrypted authenticated transport | Not deployed | `EXTERNAL_VERIFICATION_REQUIRED` | Deployment requires real Firebase configuration, relay ownership, certificate and credential review. |
| Android-to-Windows user journey | Not executed | `EXTERNAL_VERIFICATION_REQUIRED` | Requires a physical Android device and interactive Windows host after adapters are connected. |

## Security Invariants

The shared policy rejects revoked or expired sessions, pairing and controller identifier mismatches, replayed command sequences, unavailable command types, empty text requests, oversized text requests, and payload fields not valid for the command. The contract is transport-agnostic and contains no secret, raw socket, or operating-system API.

The Firestore schema scopes client-accessible documents under `users/{uid}/devices/{deviceId}`. Device documents use immutable owner and device bindings. Client sessions and events are relay-managed, command documents are append-only, and each command has exact field, command-type, payload, timestamp, expiry, owner, controller-device, and active-session validation. No broad `allow read, write` rule is present.

Platform adapters must use authenticated encrypted transport, short-lived revocable session material, platform-owned secure token storage, user-visible local pairing approval, and audit events that exclude text content and credentials. This follows least-privilege, secure-by-default, short-lived-session, secure-token-storage, and explicit-authorization guidance from OWASP and Android security documentation. [1] [2]

## Local Evidence

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx768m' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid

python3 scripts/airi_remote_control_health.py
python3 scripts/airi_remote_control_security.py
python3 scripts/airi_firestore_rules_test.py --run-emulator
```

The Emulator suite uses the demo project `demo-airi-remote-control` only. It does not require a Firebase login, production project, service account, or production credential. It verifies the allowed owner command plus negative paths for unauthenticated and foreign owners, unknown commands, oversized text payloads, expired sessions, and client update/delete attempts.

## Remaining External Verification

The following work deliberately remains outside the evidence of this gate: authenticated Android transport implementation, Android secure session storage, desktop approval UI, encrypted relay deployment, production Firestore configuration, physical Android-to-Windows command exchange, and user acceptance on Windows. None of those claims are implied by the local contract or Emulator evidence.

## References

[1]: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html "OWASP Mobile Application Security Cheat Sheet"
[2]: https://developer.android.com/privacy-and-security/security-tips "Android Security Checklist"
