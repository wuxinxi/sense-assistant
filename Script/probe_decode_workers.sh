#!/usr/bin/env bash
# Differential CPU probe; does not install, restart, or clear the application.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
TASK_HOST_TAG="${NDK_HOST_TAG:-darwin-x86_64}"
TASK_COMPILER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$TASK_HOST_TAG/bin/aarch64-linux-android24-clang++"
TASK_BIN="${NATIVE_BIN_DIR:-$TASK_ROOT/build/native-runtime/runtime/bin}"
TASK_MODEL="${1:-/sdcard/Android/data/cn.xxstudy.assistant/files/MiniCPM5-2B-Q4_K_M.gguf}"
TASK_REMOTE=/data/local/tmp/tangren-runtime-bench
TASK_ADB=(adb)
[[ -z "${ADB_SERIAL:-}" ]] || TASK_ADB+=(-s "$ADB_SERIAL")
[[ "$TASK_MODEL" =~ ^/[a-zA-Z0-9_./-]+$ ]] || exit 2
[[ "${MIN_SPEED_RATIO:-0}" =~ ^[0-9]+([.][0-9]+)?$ ]] || exit 2
mkdir -p "$TASK_ROOT/build/native-runtime"
"$TASK_COMPILER" -std=c++17 -O3 -static-libstdc++ \
    -I "$TASK_ROOT/app/src/main/cpp/include" -I "$TASK_ROOT/app/src/main/cpp" \
    "$TASK_ROOT/Script/native_decode_probe.cpp" \
    -L "$TASK_BIN" -lllama -lggml -lggml-base \
    -o "$TASK_ROOT/build/native-runtime/native-decode-probe"
"${TASK_ADB[@]}" shell mkdir -p "$TASK_REMOTE"
"${TASK_ADB[@]}" push "$TASK_ROOT/build/native-runtime/native-decode-probe" \
    "$TASK_BIN/libllama.so" "$TASK_BIN/libggml.so" \
    "$TASK_BIN/libggml-base.so" "$TASK_BIN/libggml-cpu.so" "$TASK_REMOTE/" >&2
echo "1024-token context, 64 teacher-forced tokens, 4 CPU threads, ABBA order. Keep other inference tasks idle." >&2
"${TASK_ADB[@]}" shell "LD_LIBRARY_PATH=$TASK_REMOTE MIN_SPEED_RATIO=${MIN_SPEED_RATIO:-0} timeout 240 $TASK_REMOTE/native-decode-probe $TASK_MODEL 2>$TASK_REMOTE/probe-engine.log"
