# Security package

This package contains application security and privacy controls.

## Current principles

Credentials must be stored through the project encrypted-storage path. Dynamic custom skills require an explicit HTTPS endpoint; a placeholder endpoint is rejected. The profile deletion flow uses `DataDeletionCoordinator`, which coordinates background-work cancellation, account deletion, local data cleanup, credential cleanup, preference reset, cache cleanup, and sign-out.

## Limits

Security depends on correct Android Keystore availability, external-provider restrictions, and runtime tests. Do not claim that a provider key, SQLCipher migration, Play Integrity result, or network transport is secure solely because code paths exist. Verify them on release candidates.

## Verification

Static checks confirm that the profile screen does not call Firebase-only deletion directly and that dynamic-skill registration rejects a missing HTTPS endpoint.
