# Gate — Android-to-Desktop Remote Control Foundation

## Decision

AIRI will not expose arbitrary Windows control. Android will control **only AIRI-owned desktop commands** through an explicitly paired, short-lived session. The first shared contract limits commands to status, a new draft, a bounded text request, and cancellation of an AIRI-owned request. It rejects arbitrary keyboard injection, shell execution, screen capture, arbitrary app launching, and unrestricted file access.

| Control | Current classification | Reason |
|---|---|---|
| Shared command contract | `BUILD_VERIFIED` | `RemoteControlPolicy` compiles and passes shared tests |
| Ordered command acceptance | `BUILD_VERIFIED` | Session sequence prevents replay or out-of-order commands |
| Capability allowlist | `BUILD_VERIFIED` | Commands outside the Desktop manifest are rejected |
| Pairing transport and secret exchange | `PLANNED` | Requires an authenticated, platform-specific channel and secure token storage |
| Android controller UI | `PLANNED` | No Android controller adapter is connected yet |
| Desktop pairing approval UI | `PLANNED` | No local user approval adapter is connected yet |
| Windows end-to-end control | `EXTERNAL_VERIFICATION_REQUIRED` | Requires a real Android device and interactive Windows host after transport implementation |

## Security Invariants

The policy rejects revoked or expired sessions, pairing and controller identifier mismatches, replayed command sequences, unavailable command types, empty text requests, oversized text requests, and payload fields not valid for the command. The contract is deliberately transport-agnostic and contains no secret, socket, or operating-system API.

The later platform adapters must use authenticated encrypted transport, short-lived revocable session material, platform-owned secure token storage, user-visible local pairing approval, and audit events that exclude text content and credentials. This follows least privilege, secure-by-default, short-lived sessions, secure token storage, and explicit authorization guidance from OWASP and Android security documentation. [1] [2]

## Evidence

```bash
./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx768m' \
  :core-domain:desktopTest :core-domain:compileDebugKotlinAndroid
# BUILD SUCCESSFUL — 11 actionable tasks
```

The shared tests cover acceptance from the paired controller, rejection of replayed commands, expired/revoked/unavailable sessions, and text payload shape/size validation. This is not a runtime pairing, network, or Windows-control claim.

## References

[1]: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html "OWASP Mobile Application Security Cheat Sheet"
[2]: https://developer.android.com/privacy-and-security/security-tips "Android Security Checklist"
