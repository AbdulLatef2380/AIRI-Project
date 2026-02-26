# =============================================================
# CMakeLists.txt — AIRI Native Build Configuration
# Fixes applied: C-8 (compile flag scoping), M-4 (Android-
# incompatible llama.cpp sub-targets disabled), GGML_NATIVE
# disabled for cross-compilation correctness.
# =============================================================

cmake_minimum_required(VERSION 3.22.1)

project(airi_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD   11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -------------------------------------------------------------
# llama.cpp sub-project configuration.
#
# These must be set BEFORE add_subdirectory() so they propagate
# as cache variables into the llama.cpp CMake scope.
#
# LLAMA_BUILD_TESTS / LLAMA_BUILD_EXAMPLES / LLAMA_BUILD_SERVER:
#   The server, examples, and test binaries all reference POSIX
#   headers and system libraries that are unavailable on Android.
#   Leaving them enabled causes the sub-project build to fail
#   during cross-compilation for any Android ABI.
#
# GGML_NATIVE:
#   When ON, ggml attempts to detect the HOST CPU's instruction
#   set and emit -march= flags for it. Under NDK cross-compilation
#   the host is the build machine (x86_64), not the target device
#   (arm64-v8a). This produces incorrect code-gen flags and may
#   cause compile errors or silent mis-compilation. Always OFF for
#   Android builds.
# -------------------------------------------------------------
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp)
add_subdirectory(${LLAMA_DIR})

# -------------------------------------------------------------
# AIRI native shared library
# -------------------------------------------------------------
add_library(airi_native SHARED
        airi_native.cpp
)

# -------------------------------------------------------------
# Compile options scoped ONLY to the airi_native target.
#
# C-8 / H-1 FIX (compile safety): -fno-exceptions and -fno-rtti
# must NOT be applied globally via add_compile_options(), because
# that would propagate them into the llama.cpp sub-project.
# llama.cpp uses STL types whose OOM paths depend on exception
# unwinding; compiling them with -fno-exceptions makes allocation
# failures call std::terminate() instead of throwing, and
# -fno-rtti breaks any code path using dynamic_cast or typeid.
#
# Using target_compile_options() with PRIVATE ensures these flags
# apply only to airi_native.cpp itself, where there are no such
# dependencies.
# -------------------------------------------------------------
target_compile_options(airi_native PRIVATE
        -O3
        -fvisibility=hidden
        -fdata-sections
        -ffunction-sections
        -fno-exceptions
        -fno-rtti
)

target_compile_definitions(airi_native PRIVATE
        ANDROID
)

# -------------------------------------------------------------
# Include paths and link libraries
# -------------------------------------------------------------
target_include_directories(airi_native PRIVATE
        ${LLAMA_DIR}/include
        ${LLAMA_DIR}
)

target_link_libraries(airi_native
        llama
        android
        log
)
