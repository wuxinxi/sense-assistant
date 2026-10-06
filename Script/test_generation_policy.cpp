#include "generation-policy.h"
#include <cstdio>
#include <cstdlib>

static void check(bool ok, const char * message) {
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", message); std::exit(1); }
}
int main() {
    check(thinking_token_limit(512) == 128, "512-token cap must reserve tokens for a final answer");
    check(thinking_token_limit(128) == 32, "smaller contexts must retain answer space");
    check(thinking_token_limit(16) == 0, "tiny contexts cannot safely insert closing tags");
    check(pending_thinking_end("<think>unfinished") == "</think>", "common thinking must be bounded");
    check(pending_thinking_end(" \n<|thought_begin|>unfinished") == "<|thought_end|>", "special thinking must be bounded");
    check(pending_thinking_end("<think>done</think>answer").empty(), "natural end must not be closed twice");
    check(pending_thinking_end("Here is a <think> code sample").empty(), "answer text must not be treated as reasoning");
    check(pending_thinking_end("<thi").empty(), "partial start tag is not yet actionable");
    std::string prompt = "<|im_start|>user\nunicorn<|im_end|>\n<|im_start|>assistant\n";
    const char * model_template = "enable_thinking is false '<think>\\n\\n</think>\\n\\n'";
    check(apply_disabled_thinking_template(prompt, model_template), "disabled mode must use the actual GGUF convention");
    check(prompt.find("assistant\n<think>\n\n</think>\n\n") != std::string::npos, "empty reasoning prefix required");
    check(!apply_disabled_thinking_template(prompt, model_template), "template application must be idempotent");
    std::string other = "<|im_start|>assistant\n";
    check(!apply_disabled_thinking_template(other, "plain ChatML"), "non-thinking models must not be modified");
    check(!apply_disabled_thinking_template(other, nullptr), "missing model metadata is safe");
    std::puts("PASS: generation budget, tag boundaries and model-specific disabled template");
}
