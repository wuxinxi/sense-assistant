#pragma once
#include <algorithm>
#include <string>
#include <utility>

// Reserve roughly three quarters for closing control tokens and the answer. Tiny windows
// cannot safely accommodate closing control tokens.
inline int thinking_token_limit(int max_predict) {
    return max_predict < 24 ? 0 : std::min(128, max_predict / 4);
}
inline std::string pending_thinking_end(const std::string & response) {
    const auto start = response.find_first_not_of(" \r\n\t");
    if (start == std::string::npos || start > 32) return {};
    for (const auto & tags : {std::pair<const char *, const char *>("<think>", "</think>"),
                              {"<|thought_begin|>", "<|thought_end|>"}}) {
        const std::string begin(tags.first), end(tags.second);
        if (response.compare(start, begin.size(), begin) == 0 &&
            response.find(end, start + begin.size()) == std::string::npos) return end;
    }
    return {};
}
inline bool apply_disabled_thinking_template(std::string & prompt, const char * model_template) {
    if (!model_template) return false;
    const std::string templ(model_template);
    const std::string assistant = "<|im_start|>assistant\n";
    if (templ.find("enable_thinking") == std::string::npos || templ.find("<think>") == std::string::npos ||
        prompt.size() < assistant.size() ||
        prompt.compare(prompt.size() - assistant.size(), assistant.size(), assistant) != 0) return false;
    prompt += "<think>\n\n</think>\n\n";
    return true;
}
