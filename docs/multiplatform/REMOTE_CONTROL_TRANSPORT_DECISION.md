# Remote Control Transport Decision

## Current State

AIRI Android already has Firebase Auth and Firestore for profile and memory sync. AIRI Desktop currently has no Firebase/Auth dependency, no Desktop identity adapter, no Firestore security rules in the repository, and no deployed server-side command validator. The repository contains Android `google-services.json` only.

| Path | Decision | Reason |
|---|---|---|
| Unauthenticated local LAN socket | Rejected | It would expose command reception to network peers without authenticated encrypted transport or a deployable consent model |
| Arbitrary Windows automation | Rejected | It violates the AIRI command allowlist and least-privilege boundary |
| Firestore relay using existing Android configuration only | Rejected | Desktop identity, Firestore rules, server-side validation, and revocation/expiry enforcement are absent |
| Secure paired relay | Planned | Requires a deployed authenticated relay, Desktop enrollment, rules or server validation, encrypted transport, short-lived secrets, and target-device acceptance |

## Required External Deployment Inputs

The next transport implementation needs an approved project-level Firebase/relay deployment with Desktop identity and command authorization rules. The deployable path must validate the authenticated account, paired device identity, command id, command sequence, expiry, replay state, and Desktop capability manifest before any command reaches `PairedDesktopControl`.

Until those inputs exist, AIRI exposes no listener, no remote-control button, and no claim that Android can control Windows. The shared policy and Desktop dispatcher remain `BUILD_VERIFIED`; real Android-to-Windows pairing is `EXTERNAL_VERIFICATION_REQUIRED` after the relay is deployed.

## References

[1]: https://cheatsheetseries.owasp.org/cheatsheets/Mobile_Application_Security_Cheat_Sheet.html "OWASP Mobile Application Security Cheat Sheet"
[2]: https://developer.android.com/privacy-and-security/security-tips "Android Security Checklist"
