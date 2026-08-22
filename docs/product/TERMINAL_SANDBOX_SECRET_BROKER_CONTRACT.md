# AIRI Terminal, Sandbox, and Secret Broker Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for argv-only sandbox execution, governed allowlisted commands, bounded output, terminal session/history/scrollback, cooperative command cancellation, and temporary secret capabilities backed by Android Keystore in production. `PARTIAL` for desktop PTY support, portable process-resource accounting, task-continuation wiring for every secret-consuming provider, and device-level cancellation verification.

## Terminal and sandbox boundary

The Terminal screen executes through `TerminalRuntime` and `SandboxExecutor`; it does not invoke a device shell through string interpolation. The sandbox tokenizes command input, rejects shell metacharacters, applies a binary and subcommand allowlist, scrubs environment variables, applies relative-path restrictions, and starts allowlisted binaries using `ProcessBuilder(argv)`. Standard output is capped at 256 KiB, and the process is force-destroyed from `finally` on completion, timeout, or coroutine cancellation.

| Concern | Enforced contract |
|---|---|
| Command form | No `sh -c`; input becomes argv after whitespace tokenisation. Shell metacharacters are rejected. |
| File boundary | File reads/writes canonicalise their path under the sandbox workspace; path escape is a security violation. |
| Process scope | Binary allowlist and read-only Git subcommand allowlist; network primitives remain unavailable through raw terminal commands. |
| Output and time | Output is bounded to 256 KiB; subprocess wait is bounded and terminal coroutine cancellation destroys the process. |
| Governance | Every non-built-in command is evaluated through `PermissionGovernanceLayer` before the sandbox is invoked. |
| User control | TerminalScreen presents a cancel action while a command is running; it cancels the owning coroutine rather than merely changing UI state. |

## Secret broker boundary

`SecretVault` no longer returns a raw credential to an agent. `issueCapability` and the compatibility `brokerSecret` method return a `SecretCapability` that contains no secret value. The capability is bound to agent ID, key name, intended operation, optional task ID, issue time, expiry, and remaining uses. A trusted provider adapter may call `useCapability` with the matching identity and operation; the vault consumes the capability before the adapter receives the secret inside the callback.

When initialized by `ServiceLocator`, secret values are stored in `SecureStorage`, which uses Android Keystore-backed `EncryptedSharedPreferences` and falls back to memory only if keystore initialization fails. Capabilities themselves remain memory-only and expire within bounded lifetime; replacing a secret revokes capabilities issued for its key.

| Failure | Broker result |
|---|---|
| Policy denied, malformed key, or no stored secret | No capability issued |
| Wrong agent or operation | `DENIED` without consuming the capability |
| Expired, revoked, or already used capability | No secret callback; status identifies the safe terminal state |
| Provider callback failure | Capability remains consumed, preventing replay with the same authority |

## Evidence

| Evidence | Contract proven |
|---|---|
| `SandboxExecutorTest` | Existing argv, path, and sandbox policy checks. |
| `SecretVaultTest` | No raw broker return, agent/operation binding, single-use consumption, policy denial, and revocation. |
| Kotlin compilation and selected unit suite | Terminal runtime/UI, sandbox, vault, startup initialization, and durable task integration compile together. |

The next platform increment should introduce a desktop PTY/session backend behind this contract, expose process metadata and resource usage without weakening argv isolation, and adapt cloud/connector providers to accept SecretCapability rather than independently retrieving raw API keys.
