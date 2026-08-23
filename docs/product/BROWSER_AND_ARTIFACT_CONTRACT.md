# AIRI Browser and Artifact Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for policy-governed public read-only web extraction, redirect-boundary enforcement, user-takeover signaling for sensitive browser actions, artifact file-name isolation, version snapshots, and in-app restoration. `PARTIAL` for authenticated interactive browsing, DOM action execution, downloads/uploads, desktop automation, visual diffs, and cross-device artifact sync.

## Browser agent boundary

`CloudBrowserAgent` is a public, read-only content retriever. It does not impersonate a logged-in browser and it does not execute external side effects. The shared `BrowserNavigationPolicy` normalizes HTTP(S) addresses, rejects user-info URLs and private/local network hosts, and categorizes requested browser operations before an HTTP call occurs. `LocalBrowserOperator` uses the same boundary before any external handoff; it resolves a candidate only, emits `browser_user_takeover`, and does not call `ACTION_VIEW` or launch another application autonomously.

| Browser operation | Current policy outcome |
|---|---|
| Public HTTP(S) page read | Allowed after URL normalization and public-host validation. |
| Redirect | Up to three redirects; each target is resolved and validated as a new public read target. |
| Login, payment, form submit, upload | User takeover is required; the agent emits a structured tool request and stops. |
| Download | Explicit approval is required before implementation-specific storage writes. |
| Localhost, loopback, RFC1918, link-local, `.local`, `.internal`, non-HTTP(S) | Blocked before fetch, preventing browser-agent SSRF into device or private services. |

The Android `LocalBrowserOperator` is now a **handoff proposal**, not an autonomous `ACTION_VIEW` launcher. Public HTTP(S) targets and `geo:` maps deep links emit a structured `browser_user_takeover` event; private HTTP(S) targets and unsupported schemes fail closed. The application still needs a user-initiated UI action that consumes this event and launches the platform browser after the user explicitly elects to continue. It must not claim DOM inspection, automated form completion, authenticated browsing, or external navigation completion before that UI path exists and is device-verified.

## Artifact boundary

`ArtifactManager` writes every new artifact with its immutable ID as part of the managed filename, preventing two same-named artifacts in one session from overwriting each other. Before updating an artifact, it snapshots the current file under a private `.history/<artifactId>/version-N.<ext>` directory. `listVersions` exposes archived and current versions, while `restoreVersion` creates a new version by restoring the chosen snapshot through the normal update path.

Artifact preview now exposes a version-history menu when more than one version exists. Selecting an entry restores it as a new current version; it never edits an archive snapshot in place. HTML preview remains sandboxed with JavaScript, file/content access, external navigation, and network loads disabled.

## Evidence

| Evidence | Contract proven |
|---|---|
| `BrowserNavigationPolicyTest` | Public HTTPS reads, private/non-HTTP target blocking, and login/payment takeover behavior. |
| `LocalBrowserOperatorPolicyTest` | Public external handoffs and `geo:` deep links require takeover; private HTTP targets are blocked before an external app can be launched. |
| JVM unit-test compilation/execution | CloudBrowserAgent redirect policy and the local browser handoff boundary compile and execute in the Android unit-test target. |

The next browser implementation must provide a visible, user-initiated handoff UI that consumes `browser_user_takeover` without silently launching any external activity, then device-verify that path. A separately designed policy-gated authenticated browser backend, download scanner plus project-file import flow, DOM action execution with durable exact-step approvals, and cross-device artifact sync remain out of scope for the current implementation.
