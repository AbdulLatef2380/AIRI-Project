# AIRI — UI Redesign Report

## Design Philosophy

AIRI's visual identity is built on three principles:

1. **Spatial depth** — surfaces exist at five distinct elevation levels. Content closer to the user is lighter; background is almost black. This creates a sense of physical depth without using literal shadows.
2. **Chromatic restraint** — one brand colour (violet 290°, `#6B5CE7`) does the entire accent job. Everything else is neutral. This makes the accent feel intentional rather than decorative.
3. **Typography over decoration** — information hierarchy is achieved with weight and size, not borders, cards, or backgrounds.

---

## What Changed and Why

### Empty State (ChatScreen)
**Before:** Generic `SmartToy` robot icon centred on a blank background with a plain "How can I help?" label.

**After:** Layered cosmic orb — outer halo with breathing scale animation (0.95→1.05, 2800 ms), inner ring with sweep gradient border, letterform "A" core. Subtitle "Your on-device AI assistant — private by default". Four suggestion chips in a 2×2 grid give users an immediate action without having to think.

**Why:** The previous empty state communicated nothing about AIRI's identity. The new state tells you it's private, local, and yours — before you type a single character.

### Model Pill (Top Bar)
**Before:** Text + chevron + cloud/memory icon in a rounded rectangle. No status communication.

**After:** Live status dot (6 dp circle: green=local ready, cyan=cloud, amber=loading, violet=generating) + model name + chevron. Status is communicated in 6 dp of coloured ink, not a separate icon.

**Why:** Users with a local model need to know it's loaded before typing. The status dot gives instant visual feedback without adding cognitive load.

### Bubble Shapes
**Before:** Hardcoded `RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp)` throughout.

**After:** `AIRIShapes.userBubble` / `AIRIShapes.aiBubble` — same values but from the design token system. When the design changes, one edit propagates everywhere.

**Why:** Design tokens are the difference between a project and a product.

### AIRI Avatar
**Before:** Small 28 dp circle with a semi-transparent background and "A" text.

**After:** 30 dp circle with layered radial gradient (accent violet fading outward), 0.5 dp border at 50% opacity, ExtraBold "A" with −0.5 sp letter-spacing.

**Why:** The letterform needed more presence. ExtraBold with negative letter-spacing reads like a logotype rather than a label.

### Scroll-to-Bottom FAB
**Before:** Flat `CosmicAccent.copy(0.90f)` filled circle with a black down arrow.

**After:** `Brush.radialGradient([CosmicAccentAlt → CosmicAccent])` gradient, white icon, spring animation with `DampingRatioMediumBouncy`.

**Why:** The FAB is a call to action — it should feel alive. The spring bounce adds physicality without being distracting.

### Terminal
**Before:** Plain black background, one text colour, no interactivity beyond input.

**After:** Five-colour syntax system (`TermInput` cyan, `TermError` red, `TermWarn` amber, `TermSuccess` green, `TermOutput` off-white), history panel, search with highlight, prompt-style `$` glyph in accent violet. Feels like a real terminal.

**Why:** Developers using the terminal need fast visual parsing. Colour is not decoration here — it's information.

### About Screen
**Before:** 92-line screen with a generic `Info` icon at the top and grey card bodies.

**After:** 225-line screen with the AIRI letterform orb (matching WelcomeScreen and empty state), version + build chips, icon-in-box info cards, navigation links to licenses and privacy.

**Why:** The about screen is the only place users can find out what AIRI actually is. It should tell the story properly.

### Bottom Nav — Chat Tab Icon
**Before:** `SmartToy` (generic robot outline).

**After:** `AutoAwesome` (sparkle/stars). Communicates AI capability without looking like a toy robot.

---

## Colour System

| Token | Hex | Purpose |
|---|---|---|
| `CosmicAccent` | `#6B5CE7` | Primary — all interactive elements |
| `CosmicAccentAlt` | `#8B78F0` | Gradient endpoint, hover states |
| `CosmicAccentDark` | `#4B3DB5` | Pressed state, deep glows |
| `CosmicBlack` | `#080B14` | Base layer — deepest background |
| `SurfaceBase` | `#0C0F1C` | Sheet backgrounds |
| `SurfaceRaised` | `#111525` | Cards, chips, secondary elements |
| `SurfaceFloating` | `#171B2E` | Popovers, dialogs |
| `SurfaceHighlight` | `#1E2438` | Selected rows, active states |
| `SemanticSuccess` | `#30D158` | Positive feedback, status OK |
| `SemanticError` | `#FF453A` | Errors, destructive actions |
| `SemanticWarn` | `#FFD60A` | Caution, in-progress states |

---

## Typography Decisions

- **Screen titles:** `SemiBold 16–17 sp`, letter-spacing `−0.3 sp` — feels tighter and more premium than default
- **Body text:** `13–14 sp`, `lineHeight 18–20 sp` — comfortable reading in dark environments
- **Labels and captions:** `11–12 sp`, often with reduced alpha (0.55–0.75) for hierarchy
- **Monospace (terminal/sandbox):** `12–13 sp`, `lineHeight 17 sp` — matches common terminal line heights
- **Letter-spacing on headings:** Negative (−0.3 to −2 sp) — creates a handcrafted editorial feel
