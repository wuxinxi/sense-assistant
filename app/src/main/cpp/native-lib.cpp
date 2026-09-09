#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <atomic>
#include <mutex>
#include <cmath>
#include "llama.h"
#include <android/log.h>

#define TAG "TangRenLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global pointers for the singleton engine
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::string g_current_model_path = "";

// 线程安全与打断控制
std::mutex g_ctx_mutex;
std::atomic<bool> g_should_stop{false};

// 多轮对话状态追踪 (KV Cache 管理)
int g_n_keep = 0; // Attention sink tokens (System prompt)
int g_n_past = 0; // Current total tokens in cache
std::vector<int> g_turn_starts; // Track start pos of each turn for eviction

extern "C"
JNIEXPORT void JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_resetSession(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx != nullptr) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
    }
    g_n_past = 0;
    g_n_keep = 0;
    g_turn_starts.clear();
    LOGI("Session reset. KV Cache cleared.");
}

extern "C"
JNIEXPORT void JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_stopGeneration(JNIEnv *env, jobject thiz) {
    LOGI("LlamaEngine_stopGeneration called -> setting g_should_stop = true");
    g_should_stop.store(true);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_initContext(JNIEnv *env, jobject thiz, jstring model_path, jint n_ctx) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::string new_path(path ? path : "");
    env->ReleaseStringUTFChars(model_path, path);

    if (new_path.empty()) {
        LOGE("initContext: Empty model path provided!");
        return JNI_FALSE;
    }

    // 若当前已经加载了同一个物理文件，且 Context 健全，直接复用
    if (g_model != nullptr && g_ctx != nullptr && g_current_model_path == new_path) {
        LOGI("Model already loaded from identical path: %s", new_path.c_str());
        return JNI_TRUE;
    }

    // 核心修复：若此前已装载过旧模型，先彻底释放旧 Context 和 Model 内存，避免内存泄漏与模型锁死！
    if (g_ctx != nullptr) {
        LOGI("Releasing existing llama_context...");
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        LOGI("Releasing existing llama_model...");
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_current_model_path.clear();

    LOGI("Attempting to load new model from: %s", new_path.c_str());
    
    llama_backend_init();
    
    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(new_path.c_str(), model_params);
    
    if (g_model == nullptr) {
        LOGE("Failed to load model from %s!", new_path.c_str());
        return JNI_FALSE;
    }
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx; // Use user configured context size
    
    // 性能优化：显式指定线程数（通常设置为 4 个大核能达到最佳能效比）
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create context from model!");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    
    g_current_model_path = new_path;
    g_n_past = 0;
    g_n_keep = 0;
    g_turn_starts.clear();
    LOGI("New Model and Context loaded successfully: %s", new_path.c_str());
    return JNI_TRUE;
}

// 计算字符串中最后一个完整 UTF-8 字符的结束偏移量
// 若尾部存在残缺的多字节字符（例如汉字/Emoji 跨 Token 截断只吐出 1~2 字节），返回完整部分的长度以供留存缓冲
static size_t get_complete_utf8_length(const std::string &s) {
    size_t i = 0;
    size_t last_complete = 0;
    const size_t len = s.length();
    while (i < len) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t char_len = 0;
        if ((c & 0x80) == 0) {
            char_len = 1;
        } else if ((c & 0xE0) == 0xC0) {
            char_len = 2;
        } else if ((c & 0xF0) == 0xE0) {
            char_len = 3;
        } else if ((c & 0xF8) == 0xF0) {
            char_len = 4;
        } else {
            // 非法引导字节，单字节跳过避免死循环
            char_len = 1;
        }

        if (i + char_len <= len) {
            bool valid = true;
            for (size_t j = 1; j < char_len; ++j) {
                if ((static_cast<unsigned char>(s[i + j]) & 0xC0) != 0x80) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                i += char_len;
            } else {
                i += 1;
            }
            last_complete = i;
        } else {
            // 遇到末尾残缺的多字节 UTF-8 字符，跳出循环等待后续字节拼齐
            break;
        }
    }
    return last_complete;
}

// 统一受互斥锁保护并支持即时打断的推理接口
extern "C"
JNIEXPORT jstring JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_generateText(JNIEnv *env, jobject thiz, jstring prompt, jobject callback) {
    // 互斥锁保护：确保同一时刻只有一个线程操作 g_ctx，彻底避免多线程并发 SIGSEGV
    std::lock_guard<std::mutex> lock(g_ctx_mutex);

    // 重置打断标志
    g_should_stop.store(false);

    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("Error: Model not initialized.");
    }
    
    // 获取回调的类和方法
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Received prompt: %s", prompt_cstr);
    
    std::string prompt_str(prompt_cstr);
    
    bool disable_thinking = false;
    std::string disable_flag = "<|system_cmd_disable_thinking|>";
    if (prompt_str.find(disable_flag) == 0) {
        disable_thinking = true;
        prompt_str = prompt_str.substr(disable_flag.length());
    }

    // 1. 组装 Chat Template
    std::string final_prompt;
    if (prompt_str.find("<|im_start|>") != std::string::npos) {
        final_prompt = prompt_str;
    } else {
        final_prompt = "<|im_start|>user\n" + prompt_str + "<|im_end|>\n<|im_start|>assistant\n";
    }
    
    // 2. Tokenize
    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(final_prompt.length() + 100);
    int n_tokens = llama_tokenize(vocab, final_prompt.c_str(), final_prompt.length(), tokens.data(), tokens.size(), true, true);
    if (n_tokens < 0) {
        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        return env->NewStringUTF("Error: Prompt too long.");
    }
    tokens.resize(n_tokens);

    auto t_start = std::chrono::high_resolution_clock::now();
    uint32_t n_ctx = llama_n_ctx(g_ctx);
    int max_predict = 1536;

    // 如果是全新对话，重置状态
    if (g_n_past == 0) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_turn_starts.clear();
        // 如果有 system prompt 或者前导 tokens，可以将其标记为 n_keep
        // 简单起见，把第一轮 Prompt 的前半部分（或全部）作为 n_keep
        // 为了防崩溃，强制保留最初的 32 个 token 作为 Attention Sink
        g_n_keep = (n_tokens > 32) ? 32 : n_tokens;
    }

    // 记录本轮对话起始位置
    g_turn_starts.push_back(g_n_past);

    // 3. 滑动窗口检测与截断 (如果空间不足)
    while (g_turn_starts.size() > 1 && g_n_past + n_tokens + max_predict > n_ctx) {
        int oldest_start = g_turn_starts[1]; // [0] is attention sink / first turn start
        int oldest_end = (g_turn_starts.size() > 2) ? g_turn_starts[2] : g_n_past;
        int n_discard = oldest_end - oldest_start;
        
        LOGI("Context full (g_n_past=%d). Discarding oldest turn from pos %d to %d (n_discard=%d)", g_n_past, oldest_start, oldest_end, n_discard);
        
        // 删除旧的 Token
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0, oldest_start, oldest_end);
        // 将后续的 Token 平移
        llama_memory_seq_add(llama_get_memory(g_ctx), 0, oldest_end, -1, -n_discard);
        
        // 更新所有的位置追踪变量
        g_n_past -= n_discard;
        g_turn_starts.erase(g_turn_starts.begin() + 1);
        for (size_t i = 1; i < g_turn_starts.size(); ++i) {
            g_turn_starts[i] -= n_discard;
        }
    }

    // 4. 将 Prompt 送入推理上下文 (Ingest)
    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    batch.n_tokens = n_tokens;
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = g_n_past + i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = (i == n_tokens - 1); // Only need logits for the last token
    }

    if (llama_decode(g_ctx, batch) != 0) {
        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        llama_batch_free(batch);
        return env->NewStringUTF("Error: llama_decode failed.");
    }
    g_n_past += n_tokens;

    // 5. 初始化采样器
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    
    if (disable_thinking) {
        auto get_token = [&](const std::string& str) {
            std::vector<llama_token> t(2);
            int n = llama_tokenize(vocab, str.c_str(), str.length(), t.data(), t.size(), false, true);
            if (n > 0) return t[0];
            return (llama_token)-1;
        };
        llama_token t1 = get_token("<|thought_begin|>");
        llama_token t2 = get_token("<think>");
        std::vector<llama_logit_bias> biases;
        if (t1 != -1) biases.push_back({t1, -INFINITY});
        if (t2 != -1) biases.push_back({t2, -INFINITY});
        if (!biases.empty()) {
            llama_sampler_chain_add(smpl, llama_sampler_init_logit_bias(
                llama_vocab_n_tokens(vocab), biases.size(), biases.data()
            ));
            LOGI("Thinking disabled via logit bias. t1=%d, t2=%d", t1, t2);
        }
    }

    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    
    std::string response = "";
    std::string token_stream_buf = "";
    
    bool is_first_token = true;
    long long ttft_ms = 0;
    int generated_tokens = 0;
    
    // B1 思考链外科手术：记录起始和结束坐标
    int pos_thought_start = -1;
    int pos_thought_end = -1;
    
    for (int i = 0; i < max_predict; i++) {
        if (g_should_stop.load()) {
            LOGI("Generation interrupted by user request at token %d.", i);
            break;
        }

        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        
        if (is_first_token) {
            auto t_first = std::chrono::high_resolution_clock::now();
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_first - t_start).count();
            is_first_token = false;
        }
        
        generated_tokens++;
        
        if (llama_vocab_is_eog(vocab, id)) {
            break; 
        }
        
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            std::string token_str(buf, n_chars);
            response += token_str;
            token_stream_buf += token_str;
            
            // 追踪思考链边界
            if (token_str.find("<|thought_begin|>") != std::string::npos) {
                pos_thought_start = g_n_past;
                LOGI("B1: Found <|thought_begin|> at pos %d", pos_thought_start);
            } else if (token_str.find("<|thought_end|>") != std::string::npos) {
                pos_thought_end = g_n_past;
                LOGI("B1: Found <|thought_end|> at pos %d", pos_thought_end);
            }

            size_t complete_len = get_complete_utf8_length(token_stream_buf);
            if (complete_len > 0) {
                std::string ready_text = token_stream_buf.substr(0, complete_len);
                token_stream_buf.erase(0, complete_len);

                jstring j_token = env->NewStringUTF(ready_text.c_str());
                env->CallVoidMethod(callback, onTokenMethod, j_token);
                env->DeleteLocalRef(j_token);
            }
        }
        
        batch.token[0] = id;
        batch.pos[0] = g_n_past;
        batch.seq_id[0][0] = 0;
        batch.n_seq_id[0] = 1;
        batch.logits[0] = true;
        batch.n_tokens = 1;

        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
        g_n_past++;
    }
    
    llama_batch_free(batch);
    
    // B1: 思考链外科手术切除执行
    if (pos_thought_start != -1 && pos_thought_end != -1 && pos_thought_end > pos_thought_start) {
        int n_thought = pos_thought_end - pos_thought_start + 1;
        LOGI("B1: Excisional surgery of thought chain from pos %d to %d (n=%d tokens)", pos_thought_start, pos_thought_end, n_thought);
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0, pos_thought_start, pos_thought_end + 1);
        llama_memory_seq_add(llama_get_memory(g_ctx), 0, pos_thought_end + 1, -1, -n_thought);
        g_n_past -= n_thought;
    } else if (pos_thought_start != -1) {
        LOGI("B1: Thought chain start found but no end. Skipping excision to avoid corruption.");
    }
    
    auto t_end = std::chrono::high_resolution_clock::now();
    long long total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();
    
    double speed = 0.0;
    if (total_ms > ttft_ms && generated_tokens > 1) {
        speed = (generated_tokens - 1) * 1000.0 / (total_ms - ttft_ms);
    }
    
    // 确保 response 尾部也是合法完整的 UTF-8，防止异常打断时截断汉字导致最终返回闪退
    size_t resp_complete_len = get_complete_utf8_length(response);
    if (resp_complete_len < response.length()) {
        response.erase(resp_complete_len);
    }

    char metrics_buf[256];
    snprintf(metrics_buf, sizeof(metrics_buf), "<|metrics|>{\"ttft_ms\":%lld,\"total_ms\":%lld,\"speed\":%.1f}", ttft_ms, total_ms, speed);
    response += metrics_buf;
    
    llama_sampler_free(smpl);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    return env->NewStringUTF(response.c_str());
}



