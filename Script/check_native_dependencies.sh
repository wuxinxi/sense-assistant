#!/usr/bin/env bash
# Check the built JNI bridge as well as the packaged core libraries.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/${NDK_HOST_TAG:-darwin-x86_64}/bin/llvm-readelf"
TASK_BRIDGE="${1:?Pass the built libsense_assistant.so path}"
for file in "$TASK_BRIDGE" "$TASK_ROOT/app/src/main/jniLibs/arm64-v8a/libllama.so" "$TASK_ROOT/app/src/main/jniLibs/arm64-v8a/libggml.so"; do
    TASK_DEPS="$($TASK_READELF -d "$file")"
    if printf '%s\n' "$TASK_DEPS" | rg 'NEEDED.*\[(/|.*libggml-(cpu|opencl)|.*libOpenCL)'; then
        echo "FAIL: $file links a host path or optional backend directly" >&2
        exit 1
    fi
done
echo "PASS: JNI/core dependencies contain no host paths or optional GPU dependencies"
