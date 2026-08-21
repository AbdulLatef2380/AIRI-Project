# Gate 7 — Desktop Productization

## Scope

This gate gives AIRI Desktop its own composable design primitives and desktop-first interaction model. It does not add model inference, skill execution, authentication, voice, or scheduling. All capability controls continue to show actual availability rather than an artificial successful state.

| Deliverable | Classification | Evidence |
|---|---|---|
| Desktop spacing, shapes, semantic colors, and Material color scheme | `BUILD_VERIFIED` | `DesktopDesign.kt` compiled in `:app-desktop:test` |
| Minimum window size and desktop layout breakpoint | `BUILD_VERIFIED` | Window state and a compact-width layout path compiled in `:app-desktop:test` |
| Capability controls with disabled unavailable model and skill actions | `BUILD_VERIFIED` | Existing availability contracts are rendered through explicit disabled menu items |
| Keyboard command policy | `BUILD_VERIFIED` | `DesktopShortcutPolicyTest` covers Ctrl+N, Ctrl+K, Esc, and plain text keys |
| Composer focus and action state | `BUILD_VERIFIED` | Focus requester, enabled send button, and disabled capability actions compile in the Desktop package |
| Linux package and titled window | `RUNTIME_VERIFIED` | DEB launched under software-rendered X11; process stayed alive and `AIRI Desktop` window was discovered |
| Linux visual layout, shortcut, resize, and focus acceptance | `EXTERNAL_VERIFICATION_REQUIRED` | The current non-interactive runtime probe cannot inspect visual layout or prove each command after the changed UI |
| Windows visual layout, keyboard, resize, and focus acceptance | `EXTERNAL_VERIFICATION_REQUIRED` | Requires an interactive Windows host; CI only verifies MSI packaging and process smoke tests |

## Interaction Contract

The window opens at 1240 × 820 dp and sets a 920 × 640 pixel minimum. The layout becomes compact below 1040 dp rather than forcing a mobile-style navigation hierarchy. AIRI uses a restrained dark desktop surface system, stable spacing, and distinct message shapes without introducing decorative or Android-specific controls.

The following keyboard commands are backed by an explicit tested policy. **Ctrl+N** clears only the current unsent draft and focuses the composer. **Ctrl+K** focuses the composer. **Esc** dismisses transient capability menus. Plain text input is not captured by the policy. The existing Enter and Shift+Enter composer behavior remains available at the field level.

## Verification

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:test
# BUILD SUCCESSFUL — 11 actionable tasks

./gradlew --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC' \
  '-Dkotlin.daemon.jvmargs=-Xmx1024m' \
  :app-desktop:packageDeb
# BUILD SUCCESSFUL — DEB artifact produced
```

The packaged DEB was extracted and launched under Xvfb with software rendering (`LIBGL_ALWAYS_SOFTWARE=1`, `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe`). After eight seconds the process remained alive and X11 reported a window named `AIRI Desktop` (`WINDOW_ID=2097159`). This confirms the updated package and window lifecycle on Linux only; it is not evidence that the visual hierarchy or keyboard commands were accepted by a human user.

## External Acceptance Checklist

| Target | Required scenario |
|---|---|
| Linux | Open the updated window; resize below and above 1040 dp; use Ctrl+N, Ctrl+K, Esc, Enter, Shift+Enter, and Send; verify focus indicators and disabled capability reasons |
| Windows | Install the MSI; repeat the Linux interaction sequence at standard and high-DPI scale; verify focus, menus, file-picker launch, clear history, and close/reopen behavior |

No model or skill control may change to enabled until its corresponding Desktop adapter is implemented and independently verified.
