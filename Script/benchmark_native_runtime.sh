#!/usr/bin/env bash
# Non-destructive device benchmark: never installs/uninstalls the application.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_BIN="${NATIVE_BIN_DIR:-$TASK_ROOT/build/native-runtime/runtime/bin}"
TASK_MODE="${1:-cpu}"
TASK_MODEL="${2:-/sdcard/Android/data/cn.xxstudy.assistant/files/MiniCPM5-2B-Q4_K_M.gguf}"
TASK_REMOTE=/data/local/tmp/tangren-runtime-bench
TASK_ADB=(adb)
[[ -z "${ADB_SERIAL:-}" ]] || TASK_ADB+=(-s "$ADB_SERIAL")
[[ "$TASK_MODE" == cpu || "$TASK_MODE" == gpu ]] || { echo "Usage: bash $0 cpu|gpu [device-model-path]" >&2; exit 1; }
[[ "$TASK_MODEL" =~ ^/[a-zA-Z0-9_./-]+$ ]] || { echo "Use a model path without shell metacharacters" >&2; exit 1; }
[[ "${MIN_DECODE_TPS:-0}" =~ ^[0-9]+([.][0-9]+)?$ ]] || exit 1
echo "For comparable results, close TangRen and other inference apps first. Model files and application data are not modified." >&2
"${TASK_ADB[@]}" shell mkdir -p "$TASK_REMOTE"
TASK_FILES=("$TASK_BIN/llama-bench")
for lib in llama-bench-impl llama-common llama ggml ggml-base ggml-cpu ggml-opencl; do
    TASK_FILES+=("$TASK_BIN/lib$lib.so")
done
"${TASK_ADB[@]}" push "${TASK_FILES[@]}" "$TASK_REMOTE/" >&2
TASK_OPTIONS='-ngl 0 -dev none -nopo 1'
[[ "$TASK_MODE" != gpu ]] || TASK_OPTIONS='-ngl 99'
"${TASK_ADB[@]}" shell "LD_LIBRARY_PATH=$TASK_REMOTE:/vendor/lib64 $TASK_REMOTE/llama-bench -m $TASK_MODEL -p 128 -n 32 -t 4 -fa off -r 2 -o json $TASK_OPTIONS" |
    python3 -c '
import json, sys
try:
    rows = json.load(sys.stdin)
except ValueError:
    sys.exit("FAIL: benchmark did not return complete results (interrupted or crashed)")
decode = next(row for row in rows if row["n_gen"] == 32 and row["n_prompt"] == 0)
for row in rows:
    print(("decode" if row["n_gen"] else "prefill") + ": %.2f token/s" % row["avg_ts"])
minimum = float(sys.argv[1])
if decode["avg_ts"] < minimum:
    sys.exit("FAIL: decode below requested threshold %.2f" % minimum)
print("PASS: benchmark completed, decode meets threshold %.2f" % minimum)
' "${MIN_DECODE_TPS:-0}"
