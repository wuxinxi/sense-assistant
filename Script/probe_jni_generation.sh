#!/usr/bin/env bash
# No install, no app restart, no instrumentation runner, no data clearing.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to the SDK containing build-tools 36.1.0}"
TASK_D8="${D8_BIN:-$ANDROID_SDK_ROOT/build-tools/36.1.0/d8}"
TASK_BUILD="$TASK_ROOT/build/native-runtime/jni-probe"
TASK_APK="$TASK_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TASK_MODEL="${1:-/sdcard/Android/data/cn.xxstudy.assistant/files/MiniCPM5-2B-Q4_K_M.gguf}"
TASK_REMOTE=/data/local/tmp/tangren-jni-generation-probe
TASK_ADB=(adb)
[[ -z "${ADB_SERIAL:-}" ]] || TASK_ADB+=(-s "$ADB_SERIAL")
[[ "$TASK_MODEL" =~ ^/[a-zA-Z0-9_./-]+$ ]] || exit 2
mkdir -p "$TASK_BUILD/classes" "$TASK_BUILD/dex" "$TASK_BUILD/libs"
javac --release 8 -encoding UTF-8 -d "$TASK_BUILD/classes" "$TASK_ROOT/Script/jni-probe/"*.java
jar cf "$TASK_BUILD/classes.jar" -C "$TASK_BUILD/classes" .
"$TASK_D8" --min-api 24 --output "$TASK_BUILD/dex" "$TASK_BUILD/classes.jar"
jar cf "$TASK_BUILD/generation-probe.jar" -C "$TASK_BUILD/dex" classes.dex
TASK_LIBS=()
for lib in sense_assistant llama ggml ggml-base ggml-cpu; do
    unzip -jo "$TASK_APK" "lib/arm64-v8a/lib$lib.so" -d "$TASK_BUILD/libs" >/dev/null
    TASK_LIBS+=("$TASK_BUILD/libs/lib$lib.so")
done
if unzip -Z -1 "$TASK_APK" | rg -q '^lib/arm64-v8a/libc\+\+_shared[.]so$'; then
    unzip -jo "$TASK_APK" lib/arm64-v8a/libc++_shared.so -d "$TASK_BUILD/libs" >/dev/null
    TASK_LIBS+=("$TASK_BUILD/libs/libc++_shared.so")
fi
"${TASK_ADB[@]}" shell mkdir -p "$TASK_REMOTE"
"${TASK_ADB[@]}" push "$TASK_BUILD/generation-probe.jar" "${TASK_LIBS[@]}" "$TASK_REMOTE/" >&2
echo "Running synthetic CPU fixtures in a separate VM. Keep the application idle." >&2
TASK_RESULT=0
"${TASK_ADB[@]}" shell "LD_LIBRARY_PATH=$TASK_REMOTE timeout 180 dalvikvm -Djava.library.path=$TASK_REMOTE -cp $TASK_REMOTE/generation-probe.jar cn.xxstudy.assistant.engine.GenerationProbe $TASK_MODEL 2>$TASK_REMOTE/engine.log" || TASK_RESULT=$?
if [[ "$TASK_RESULT" != 0 ]]; then
    "${TASK_ADB[@]}" shell "tail -n 12 $TASK_REMOTE/engine.log"
fi
exit "$TASK_RESULT"
