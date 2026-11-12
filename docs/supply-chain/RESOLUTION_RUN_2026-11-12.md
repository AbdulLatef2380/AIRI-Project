# Dependency resolution evidence — 2026-11-12

## Android Gradle

Commands attempted:

```text
./gradlew :app:dependencies --configuration debugRuntimeClasspath --no-daemon --console=plain
./gradlew :app:dependencies --configuration releaseRuntimeClasspath --no-daemon --console=plain
```

Both stopped during project configuration with:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable
or by setting the sdk.dir path in local.properties.
```

**Disposition:** `BLOCKED_BY_ENVIRONMENT`, not a dependency pass/fail. No Android transitive graph or release SBOM claim is made from this sandbox. Run the commands on the CI runner or a machine with the exact Android SDK/NDK/CMake toolchain.

The committed `gradle/verification-metadata.xml` currently contains `840` components and `1499` artifact SHA-256 entries, with metadata verification enabled and signature verification disabled. Signature verification should only be enabled after independently reviewing the generated keyring.

## Functions / Node

- Direct versions are pinned in `functions/package.json`.
- `functions/package-lock.json` is committed with lockfileVersion 3, package versions, registry URLs, integrity fields, and package license metadata.
- `npm ci --ignore-scripts` is the release install command.
- The sandbox used Node 22 while the project declares Node 20; this is a tooling mismatch to correct in CI validation.

`npm audit --package-lock-only` reported currently known advisories in the separate `prototypes/web-ui` lockfile, including a high-severity `brace-expansion` advisory and lower/moderate advisories. No automated mass upgrade was performed; each update requires its own regression review.

## Generated outputs

- `SBOM.spdx.json` — SPDX 2.3, declared direct Gradle catalog plus complete Functions lockfile components.
- `SBOM.cyclonedx.json` — CycloneDX 1.5 equivalent.
- Both explicitly carry `incomplete-declared-direct-plus-node-lock` status because Android resolution was blocked.
