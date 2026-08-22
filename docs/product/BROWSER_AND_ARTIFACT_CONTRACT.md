# AIRI Browser and Artifact Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for policy-governed public read-only web extraction, redirect-boundary enforcement, user-takeover signaling for sensitive browser actions, artifact file-name isolation, version snapshots, and in-app restoration. `PARTIAL` for authenticated interactive browsing, DOM action execution, downloads/uploads, desktop automation, visual diffs, and cross-device artifact sync.

## Browser agent boundary

`CloudBrowserAgent` is a public, read-only content retriever. It does not impersonate a logged-in browser and it does not execute external side effects. The shared `BrowserNavigationPolicy` normalizes HTTP(S) addresses, rejects user-info URLs and private/local network hosts, and categorizes requested browser operations before an HTTP call occurs.

| Browser operation | Current policy outcome |
|---|---|
| Public HTTP(S) page read | Allowed after URL normalization and public-host validation. |
| Redirect | Up to three redirects; each target is resolved and validated as a new public read target. |
| Login, payment, form submit, upload | User takeover is required; the agent emits a structured tool request and stops. |
| Download | Explicit approval is required before implementation-specific storage writes. |
| Localhost, loopback, RFC1918, link-local, `.local`, `.internal`, non-HTTP(S) | Blocked before fetch, preventing browser-agent SSRF into device or private services. |

The Android `LocalBrowserOperator` remains a hand-off to the user’s browser through `ACTION_VIEW`; it must not claim DOM inspection or automated form completion. Its future actions must reuse the same policy before launch.

## Artifact boundary

`ArtifactManager` writes every new artifact with its immutable ID as part of the managed filename, preventing two same-named artifacts in one session from overwriting each other. Before updating an artifact, it snapshots the current file under a private `.history/<artifactId>/version-N.<ext>` directory. `listVersions` exposes archived and current versions, while `restoreVersion` creates a new version by restoring the chosen snapshot through the normal update path.

Artifact preview now exposes a version-history menu when more than one version exists. Selecting an entry restores it as a new current version; it never edits an archive snapshot in place. HTML preview remains sandboxed with JavaScript, file/content access, external navigation, and network loads disabled.

## Evidence

| Evidence | Contract proven |
|---|---|
| `BrowserNavigationPolicyTest` | Public HTTPS reads, private/non-HTTP target blocking, and login/payment takeover behavior. |
| Kotlin compilation | CloudBrowserAgent redirect policy, ArtifactManager history, and ArtifactPreview restoration integrate into the Android app. |

The next implementation must add a policy-gated authenticated browser backend with explicit user takeover, a download scanner plus project-file import flow, artifact content hashes/visual compare, and provider-produced artifact linkage to the durable execution timeline.
