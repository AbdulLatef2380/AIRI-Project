# AIRI Priority Repair Audit

## Conclusion

The audit confirmed several functional defects rather than purely visual problems. The first repair set has been published to both requested branches. It fixes the Gemini credential namespace mismatch, removes retired Gemini model defaults, separates the input controls into two directional groups, synchronizes composer text with the session-keyed ViewModel draft, prevents persisted Brave Search from entering LLM routing, and rejects catalog model files whose size is not exactly the authoritative catalog size.

## Completed repairs

The built-in provider flow now stores credentials in the provider namespace consumed by the runtime adapter. Existing catalog-specific credentials are migrated on first read. Gemini defaults now use `gemini-3.8-flash` and the catalog uses current Gemini 3 stable identifiers. The official model table identifies Gemini 3.8 Flash as a stable model and lists the retired Gemini 2.0 endpoints as shut down [1] [2].

The composer now reports each text change to the ViewModel and renders the session-keyed draft. Sending clears the ViewModel draft only through the send completion callback. The action bar now has a start-side group for attachment and microphone actions and an end-side group for connector and send/live controls. Compose start/end placement mirrors correctly under RTL when the parent layout direction is set by the locale.

Catalog downloads and activation no longer accept a 97-percent file as complete. A file is promoted or registered only when its measured length matches the catalog size. Availability checks use the same rule, preventing a truncated model from being rediscovered after restart. This is an immediate mitigation for the reported near-99-percent crash path; cryptographic digest and GGUF metadata validation remain the next hardening step.

The LLM provider selector now excludes Brave Search. Persisted Brave selections are normalized to Gemini before CloudBackend constructs an adapter, avoiding the previous unsupported-provider exception.

## Confirmed remaining work

The history drawer and standalone history screen still require consolidation behind one action contract. Share, rename, pin, and delete need end-to-end UI verification and localized labels. Connector disconnect must clear durable credentials consistently, and the connector “test” action must call a typed read-only connector operation rather than merely prefilling a chat prompt.

Resource settings still distinguish an AIRI warning budget from real device capacity. The UI should present device total/free storage, AIRI-owned bytes, system total/available RAM, and configurable warning thresholds as separate facts. Directory scans must remain off the main thread and refresh on resume while the screen is visible.

The skills, integrations, execution, model-library, and model settings screens still contain Kotlin hardcoded English and dynamic enum labels. They need a shared localized presentation contract, adaptive layouts, RTL-safe icons, and Compose UI tests at Arabic locale, compact width, and increased font scale.

The model download implementation still has two competing pipelines. The authoritative path should be a durable resumable WorkManager or user-initiated transfer state machine with per-model progress, cancellation, process-death recovery, mandatory checksum when catalog metadata provides one, and atomic promotion only after validation. The service should not use a process-global cancellation flag for multiple downloads.

## References

[1]: https://ai.google.dev/gemini-api/docs/models "Gemini API models"
[2]: https://ai.google.dev/gemini-api/docs/deprecations "Gemini API deprecations"
[3]: https://developer.android.com/training/basics/supporting-devices/languages "Android language and RTL support"
[4]: https://developer.android.com/develop/ui/compose/state "State and state hoisting in Jetpack Compose"
[5]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/observe "Observe WorkManager progress"
[6]: https://developer.android.com/training/data-storage/app-specific "Android app-specific storage"
[7]: https://developer.android.com/reference/android/os/StatFs "Android StatFs reference"
[8]: https://ai.google.dev/gemini-api/docs/api-key "Gemini API key security guidance"

## Verification

The source-contract and localization checks passed with **88/88 checks** and `likely_untranslated_values=0`. Android compilation and instrumentation for these new commits are running in GitHub Actions; the local sandbox does not contain an Android SDK and therefore cannot claim a local Gradle build.
