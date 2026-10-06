#!/usr/bin/env bash
# Vocabulary-only measurement: no model weights, inference, install or data reset.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_BIN="${NATIVE_BIN_DIR:-$TASK_ROOT/build/native-runtime/runtime/bin}"
TASK_FIXTURES="$TASK_ROOT/build/rag-prompt-fixtures"
TASK_REMOTE=/data/local/tmp/tangren-rag-token-probe
TASK_MODEL="${1:-/sdcard/Android/data/cn.xxstudy.assistant/files/MiniCPM5-2B-Q4_K_M.gguf}"
TASK_ADB=(adb)
[[ -z "${ADB_SERIAL:-}" ]] || TASK_ADB+=(-s "$ADB_SERIAL")
[[ "$TASK_MODEL" =~ ^/[a-zA-Z0-9_./-]+$ ]] || exit 2
[[ -f "$TASK_FIXTURES/before.txt" && -f "$TASK_FIXTURES/after.txt" ]] || {
    echo "Generate fixtures by running RagPromptEfficiencyTest with RAG_PROMPT_FIXTURES_DIR=$TASK_FIXTURES" >&2
    exit 2
}
[[ -f "$TASK_BIN/llama-tokenize" ]] || {
    echo "Build llama-tokenize in the pinned native runtime first." >&2
    exit 2
}
"${TASK_ADB[@]}" get-state >/dev/null
"${TASK_ADB[@]}" shell mkdir -p "$TASK_REMOTE"
TASK_FILES=("$TASK_BIN/llama-tokenize" "$TASK_FIXTURES/before.txt" "$TASK_FIXTURES/after.txt")
for lib in llama-common llama ggml ggml-base ggml-cpu; do
    TASK_FILES+=("$TASK_BIN/lib$lib.so")
done
"${TASK_ADB[@]}" push "${TASK_FILES[@]}" "$TASK_REMOTE/" >&2
TASK_COUNTS=()
for fixture in before after; do
    TASK_COUNT=$("${TASK_ADB[@]}" shell "LD_LIBRARY_PATH=$TASK_REMOTE timeout 30 $TASK_REMOTE/llama-tokenize -m $TASK_MODEL -f $TASK_REMOTE/$fixture.txt --no-escape --parse-special --show-count 2>/dev/null" |
        sed -n 's/^Total number of tokens: //p' | tr -d '\r')
    [[ "$TASK_COUNT" =~ ^[0-9]+$ ]] || { echo "FAIL: no complete tokenizer result" >&2; exit 1; }
    TASK_COUNTS+=("$TASK_COUNT")
    echo "$fixture tokens=$TASK_COUNT"
done
[[ "${TASK_COUNTS[1]}" -lt "${TASK_COUNTS[0]}" ]] || { echo "FAIL: prompt token count did not decrease" >&2; exit 1; }
echo "PASS: vocabulary-only prompt token reduction; not a latency or decode benchmark"
