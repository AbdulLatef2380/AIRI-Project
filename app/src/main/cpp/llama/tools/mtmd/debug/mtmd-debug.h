#pragma once
//
// debug/mtmd-debug.h — release-safe stub for the MTMD debug overlay.
//
// WHY THIS FILE EXISTS
// ────────────────────
// The original tree expected an external "debug overlay" file at this exact
// path. That overlay was a developer-local file (never committed) that
// added verbose tracing/assert macros around the MTMD pipeline. When the
// overlay was missing — i.e. on every fresh clone and every CI runner —
// `tools/mtmd/mtmd.cpp` failed to compile with:
//
//     fatal error: 'debug/mtmd-debug.h' file not found
//
// The fix has two parts:
//   1. `mtmd.cpp` now wraps the include in `__has_include` AND defines
//      no-op fallback macros, so a missing/empty overlay is harmless.
//   2. This in-tree stub guarantees the include path always resolves and
//      that a real overlay can be layered on top with a single drop-in
//      replacement of this file. Either layer alone is sufficient — both
//      are present so the build cannot regress through accidental deletion
//      of one or the other (defense in depth).
//
// CONTRACT
// ────────
// Every macro defined here MUST evaluate to `((void)0)` (a statement-form
// no-op). Anything else risks pulling cost into hot paths inside
// `clip_image_encode` / `mtmd_encode_chunk`, which are called per-token.
//
// To enable real instrumentation locally:
//   1. Replace the contents of this file with your tracing implementation.
//   2. Define MTMD_ENABLE_DEBUG_INSTRUMENTATION at compile time
//      (Debug builds set this automatically via CMake — see CMakeLists.txt).
//   3. Do NOT commit your overlay back. The stub is the canonical version.
//

// Use #ifndef guards rather than unconditional #define so an overlay can
// shadow individual macros without losing the others.
#ifndef MTMD_DEBUG_LOG
#  define MTMD_DEBUG_LOG(...)         ((void)0)
#endif

#ifndef MTMD_DEBUG_ASSERT
#  define MTMD_DEBUG_ASSERT(...)      ((void)0)
#endif

#ifndef MTMD_DEBUG_TRACE_SCOPE
#  define MTMD_DEBUG_TRACE_SCOPE(...) ((void)0)
#endif

#ifndef MTMD_DEBUG_DUMP_TENSOR
#  define MTMD_DEBUG_DUMP_TENSOR(...) ((void)0)
#endif
