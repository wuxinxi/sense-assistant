#include <jni.h>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <atomic>
#include <mutex>
#include <cmath>
#include "llama.h"
#include "ggml-backend.h"
#include "cpu-worker-pool.h"
#include "generation-policy.h"
#include <android/log.h>

#define TAG "TangRenLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global pointers for the singleton engine
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;
std::string g_current_model_path = "";
std::mutex g_ctx_mutex;
bool g_current_gpu_requested = false;
std::string g_backend_label = "未加载";
static ggml_backend_dev_t g_llm_devices[2] = {nullptr, nullptr};
static ggml_backend_dev_t g_cpu_devices[1] = {nullptr};
static std::mutex g_backend_mutex;
static CpuWorkerPool g_generation_workers;

// Call with g_ctx_mutex held, after inference has finished.
static void free_generation_context() {
    if (g_ctx != nullptr) {
        llama_detach_threadpool(g_ctx);
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    g_generation_workers.reset();
}

// Explicit plugin loading avoids searching /system/bin (app_process), and
// keeps the optional vendor OpenCL dependency out of the CPU library chain.
static bool ensure_cpu_backend() {
    if (ggml_backend_reg_by_name("CPU") == nullptr &&
        ggml_backend_load("libggml-cpu.so") == nullptr) {
        LOGE("Could not load CPU backend");
        return false;
    }
    llama_backend_init();
    return true;
}

static bool load_generation_context(const std::string & path, int n_ctx, ggml_backend_dev_t device) {
    g_llm_devices[0] = device;
    llama_model_params model_params = llama_model_default_params();
    model_params.devices = device ? g_llm_devices : g_cpu_devices;
    model_params.n_gpu_layers = device ? -1 : 0;
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) return false;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    ctx_params.offload_kqv = device != nullptr;
    ctx_params.op_offload = device != nullptr;
    ctx_params.no_perf = false;
    // The verified Adreno path uses ordinary attention, not experimental FA.
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }
    if (device == nullptr && !g_generation_workers.attach(g_ctx, 4)) {
        LOGE("Could not attach persistent CPU workers");
        free_generation_context();
        llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_getBackendName(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    return env->NewStringUTF(g_backend_label.c_str());
}

// 线程安全与打断控制
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
        llama_synchronize(g_ctx);
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
JNIEXPORT void JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_releaseContext(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx != nullptr) {
        LOGI("releaseContext: Releasing existing llama_context...");
    }
    free_generation_context();
    if (g_model != nullptr) {
        LOGI("releaseContext: Releasing existing llama_model...");
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_current_model_path.clear();
    g_backend_label = "未加载";
    g_n_past = 0;
    g_n_keep = 0;
    g_turn_starts.clear();
    LOGI("releaseContext: Model and Context completely unloaded.");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_initContext(JNIEnv *env, jobject thiz, jstring model_path, jint n_ctx, jboolean use_gpu) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    std::lock_guard<std::mutex> backend_lock(g_backend_mutex);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::string new_path(path ? path : "");
    env->ReleaseStringUTFChars(model_path, path);

    if (new_path.empty() || n_ctx <= 0) {
        LOGE("initContext: Invalid model path/context size!");
        return JNI_FALSE;
    }

    // 若当前已经加载了同一个物理文件，且 Context 健全，直接复用
    if (g_model != nullptr && g_ctx != nullptr && g_current_model_path == new_path &&
        g_current_gpu_requested == static_cast<bool>(use_gpu) && llama_n_ctx(g_ctx) == static_cast<uint32_t>(n_ctx)) {
        LOGI("Model already loaded from identical path: %s", new_path.c_str());
        return JNI_TRUE;
    }

    // 核心修复：若此前已装载过旧模型，先彻底释放旧 Context 和 Model 内存，避免内存泄漏与模型锁死！
    if (g_ctx != nullptr) {
        LOGI("Releasing existing llama_context...");
    }
    free_generation_context();
    if (g_model != nullptr) {
        LOGI("Releasing existing llama_model...");
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_current_model_path.clear();
    g_backend_label = "未加载";

    LOGI("Attempting to load new model from: %s", new_path.c_str());
    
    if (!ensure_cpu_backend()) return JNI_FALSE;
    ggml_backend_dev_t gpu = nullptr;
    if (use_gpu) {
        auto reg = ggml_backend_reg_by_name("OpenCL");
        if (reg == nullptr) reg = ggml_backend_load("libggml-opencl.so");
        if (reg != nullptr) {
            for (size_t i = 0; i < ggml_backend_reg_dev_count(reg); ++i) {
                auto candidate = ggml_backend_reg_dev_get(reg, i);
                if (ggml_backend_dev_type(candidate) == GGML_BACKEND_DEVICE_TYPE_GPU) {
                    gpu = candidate;
                    break;
                }
            }
        }
    }
    if (!load_generation_context(new_path, n_ctx, gpu)) {
        if (gpu == nullptr || !load_generation_context(new_path, n_ctx, nullptr)) {
            LOGE("Failed to load model/context from %s", new_path.c_str());
            return JNI_FALSE;
        }
        gpu = nullptr;
    }
    if (gpu != nullptr) {
        g_backend_label = "GPU OpenCL";
        LOGI("Inference backend: OpenCL (%s), requested all-layer offload", ggml_backend_dev_description(gpu));
    } else {
        g_backend_label = use_gpu ? "CPU (GPU 不可用)" : "CPU · 4 线程";
        LOGI("Inference backend: CPU, 4 threads, persistent workers, GPU offload disabled");
    }
    g_current_model_path = new_path;
    g_current_gpu_requested = use_gpu;
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
    std::string prompt_str(prompt_cstr);
    LOGI("Received prompt: %zu bytes", prompt_str.size());
    
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
    if (disable_thinking && apply_disabled_thinking_template(
            final_prompt, llama_model_chat_template(g_model, nullptr))) {
        LOGI("Thinking disabled using model chat template");
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
    // A 1536-token unconditional reserve leaves almost no room for prompt and
    // conversation history in the common 2048-token context. Keep answers
    // bounded and always leave the current prompt inside the context window.
    if (n_tokens >= static_cast<int>(n_ctx)) {
        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        return env->NewStringUTF("Error: Prompt exceeds context window.");
    }
    int max_predict = std::min(512, static_cast<int>(n_ctx) - n_tokens - 1);

    // 如果是全新对话，重置状态
    if (g_n_past == 0) {
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_turn_starts.clear();
        // 如果有 system prompt 或者前导 tokens，可以将其标记为 n_keep
        // 简单起见，把第一轮 Prompt 的前半部分（或全部）作为 n_keep
        // 为了防崩溃，强制保留最初的 32 个 token 作为 Attention Sink
        g_n_keep = (n_tokens > 32) ? 32 : n_tokens;
    }

    // 3. 滑动窗口检测与截断 (如果空间不足)
    // g_turn_starts only contains completed turns here. The previous code pushed
    // the current position first, then tried to evict [current, current), so a
    // full context discarded zero tokens and kept stale answers in the cache.
    while (g_turn_starts.size() >= 2 && g_n_past + n_tokens + max_predict > n_ctx) {
        int oldest_start = std::max(g_turn_starts.front(), g_n_keep);
        int oldest_end = g_turn_starts[1];
        int n_discard = oldest_end - oldest_start;

        if (n_discard <= 0) {
            break;
        }
        LOGI("Context full (g_n_past=%d). Discarding oldest turn from pos %d to %d (n_discard=%d)", g_n_past, oldest_start, oldest_end, n_discard);
        
        // 删除旧的 Token
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0, oldest_start, oldest_end);
        // 将后续的 Token 平移
        llama_memory_seq_add(llama_get_memory(g_ctx), 0, oldest_end, -1, -n_discard);
        
        // 更新所有的位置追踪变量
        g_n_past -= n_discard;
        g_turn_starts.erase(g_turn_starts.begin());
        for (int & turn_start : g_turn_starts) {
            turn_start -= n_discard;
        }
    }

    // With only one oversized historical turn there is no safe boundary to
    // evict. Starting fresh is preferable to an out-of-range decode or reusing
    // stale logits. The current prompt is still ingested below.
    if (g_n_past + n_tokens + max_predict > static_cast<int>(n_ctx)) {
        LOGI("Context still full after turn eviction. Resetting KV cache.");
        llama_memory_clear(llama_get_memory(g_ctx), true);
        g_n_past = 0;
        g_n_keep = (n_tokens > 32) ? 32 : n_tokens;
        g_turn_starts.clear();
    }

    // Record the current turn only after eviction so it can never be selected
    // as an already-completed eviction range.
    g_turn_starts.push_back(g_n_past);
    const int kv_tokens_before_prompt = g_n_past;
    // Capped output may leave one queued eval in llama's accounting. Flush
    // before reset so idle time cannot enter the next request's prefill sample.
    llama_synchronize(g_ctx);
    llama_perf_context_reset(g_ctx);

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
    sparams.no_perf = false;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    
    if (disable_thinking) {
        auto get_token = [&](const std::string& str) {
            std::vector<llama_token> t(2);
            int n = llama_tokenize(vocab, str.c_str(), str.length(), t.data(), t.size(), false, true);
            // Do not ban the first piece of a multi-token tag (e.g. plain '<').
            if (n == 1) return t[0];
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
    double callback_ms = 0.0;
    const int thought_limit = thinking_token_limit(max_predict);
    bool forced_thinking_end = false;
    int control_tokens = 0;
    
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
                const auto callback_start = std::chrono::steady_clock::now();
                env->CallVoidMethod(callback, onTokenMethod, j_token);
                callback_ms += std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - callback_start).count();
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

        // Preserve history, but reserve tokens for an answer using the model's
        // matching closing marker. Wait for a complete UTF-8 boundary first.
        if (!forced_thinking_end && thought_limit > 0 && generated_tokens >= thought_limit &&
            token_stream_buf.empty()) {
            const auto end_tag = pending_thinking_end(response);
            if (!end_tag.empty()) {
                const std::string close_text = "\n" + end_tag + "\n\n";
                std::vector<llama_token> close_tokens(64);
                const int n_close = llama_tokenize(vocab, close_text.c_str(), close_text.size(),
                                                   close_tokens.data(), close_tokens.size(), false, true);
                if (n_close > 0 && i + n_close < max_predict && g_n_past + n_close < static_cast<int>(n_ctx)) {
                    auto close_batch = llama_batch_init(n_close, 0, 1);
                    close_batch.n_tokens = n_close;
                    for (int j = 0; j < n_close; ++j) {
                        close_batch.token[j] = close_tokens[j];
                        close_batch.pos[j] = g_n_past + j;
                        close_batch.n_seq_id[j] = 1;
                        close_batch.seq_id[j][0] = 0;
                        close_batch.logits[j] = j == n_close - 1;
                    }
                    const int decode_status = llama_decode(g_ctx, close_batch);
                    llama_batch_free(close_batch);
                    if (decode_status != 0) {
                        LOGE("Could not close bounded thinking");
                        break;
                    }
                    for (int j = 0; j < n_close; ++j) llama_sampler_accept(smpl, close_tokens[j]);
                    g_n_past += n_close;
                    i += n_close; // forced control tokens consume the same cap
                    control_tokens += n_close;
                    forced_thinking_end = true;
                    response += close_text;
                    const auto callback_start = std::chrono::steady_clock::now();
                    jstring j_close = env->NewStringUTF(close_text.c_str());
                    env->CallVoidMethod(callback, onTokenMethod, j_close);
                    env->DeleteLocalRef(j_close);
                    callback_ms += std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - callback_start).count();
                    if (end_tag == "<|thought_end|>") pos_thought_end = g_n_past - 1;
                    LOGI("Thinking budget reached: limit=%d control_tokens=%d; reserving final answer", thought_limit, n_close);
                }
            }
        }
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
    
    llama_synchronize(g_ctx);
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
    LOGI("Generation metrics: backend=%s tokens=%d ttft_ms=%lld total_ms=%lld speed=%.1f tk/s",
         g_backend_label.c_str(), generated_tokens, ttft_ms, total_ms, speed);
    const auto perf = llama_perf_context(g_ctx);
    const auto sampling_perf = llama_perf_sampler(smpl);
    LOGI("Generation stages: prompt_tokens=%d kv_before=%d prefill_ms=%.1f decode_tokens=%d decode_ms=%.1f sample_ms=%.1f callback_ms=%.1f thought_limit=%d forced_think_end=%d control_tokens=%d",
         n_tokens, kv_tokens_before_prompt, perf.t_p_eval_ms, perf.n_eval,
         perf.t_eval_ms, sampling_perf.t_sample_ms, callback_ms, thought_limit, forced_thinking_end, control_tokens);
    response += metrics_buf;
    
    llama_sampler_free(smpl);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    return env->NewStringUTF(response.c_str());
}

// =========================================================================================
// EmbeddingEngine JNI Implementation for RAG
// =========================================================================================

static llama_model *g_embed_model = nullptr;
static llama_context *g_embed_ctx = nullptr;
static std::string g_current_embed_model_path = "";
static std::mutex g_embed_mutex;

extern "C"
JNIEXPORT void JNICALL
Java_cn_xxstudy_assistant_engine_EmbeddingEngine_releaseContext(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    if (g_embed_ctx != nullptr) {
        LOGI("EmbeddingEngine: Releasing llama_context...");
        llama_free(g_embed_ctx);
        g_embed_ctx = nullptr;
    }
    if (g_embed_model != nullptr) {
        LOGI("EmbeddingEngine: Releasing llama_model...");
        llama_model_free(g_embed_model);
        g_embed_model = nullptr;
    }
    g_current_embed_model_path.clear();
    LOGI("EmbeddingEngine: Context and model completely released.");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_xxstudy_assistant_engine_EmbeddingEngine_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    std::lock_guard<std::mutex> backend_lock(g_backend_mutex);

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    std::string new_path(path ? path : "");
    env->ReleaseStringUTFChars(model_path, path);

    if (new_path.empty()) {
        LOGE("EmbeddingEngine_initContext: Empty model path!");
        return JNI_FALSE;
    }

    if (g_embed_model != nullptr && g_embed_ctx != nullptr && g_current_embed_model_path == new_path) {
        LOGI("EmbeddingEngine: Model already loaded from: %s", new_path.c_str());
        return JNI_TRUE;
    }

    if (g_embed_ctx != nullptr) {
        llama_free(g_embed_ctx);
        g_embed_ctx = nullptr;
    }
    if (g_embed_model != nullptr) {
        llama_model_free(g_embed_model);
        g_embed_model = nullptr;
    }
    g_current_embed_model_path.clear();

    if (!ensure_cpu_backend()) return JNI_FALSE;

    LOGI("EmbeddingEngine: Loading embedding model from %s...", new_path.c_str());
    llama_model_params model_params = llama_model_default_params();
    model_params.devices = g_cpu_devices;
    model_params.n_gpu_layers = 0;

    g_embed_model = llama_model_load_from_file(new_path.c_str(), model_params);
    if (g_embed_model == nullptr) {
        LOGE("EmbeddingEngine: Failed to load model from %s!", new_path.c_str());
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 512;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    ctx_params.embeddings = true;
    ctx_params.offload_kqv = false;
    ctx_params.op_offload = false;
    // BGE-small-zh-v1.5 is trained with the first [CLS] token as the
    // sentence representation. Mean pooling changes the embedding space and
    // materially degrades retrieval quality.
    ctx_params.pooling_type = LLAMA_POOLING_TYPE_CLS;

    g_embed_ctx = llama_init_from_model(g_embed_model, ctx_params);
    if (g_embed_ctx == nullptr) {
        LOGE("EmbeddingEngine: Failed to create embedding context!");
        llama_model_free(g_embed_model);
        g_embed_model = nullptr;
        return JNI_FALSE;
    }

    g_current_embed_model_path = new_path;
    LOGI(
        "EmbeddingEngine: Successfully initialized embedding model (dim=%d, pooling=%d).",
        llama_model_n_embd(g_embed_model),
        static_cast<int>(llama_pooling_type(g_embed_ctx))
    );
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_cn_xxstudy_assistant_engine_EmbeddingEngine_getEmbedding(JNIEnv *env, jobject thiz, jstring text) {
    std::lock_guard<std::mutex> lock(g_embed_mutex);
    if (g_embed_model == nullptr || g_embed_ctx == nullptr) {
        LOGE("EmbeddingEngine_getEmbedding: Model or context not initialized!");
        return nullptr;
    }

    const char *text_cstr = env->GetStringUTFChars(text, nullptr);
    std::string input_text(text_cstr ? text_cstr : "");
    env->ReleaseStringUTFChars(text, text_cstr);

    if (input_text.empty()) {
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_embed_model);
    int max_tokens = 512;
    std::vector<llama_token> tokens(max_tokens);
    int n_tokens = llama_tokenize(vocab, input_text.c_str(), input_text.length(), tokens.data(), max_tokens, true, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, input_text.c_str(), input_text.length(), tokens.data(), tokens.size(), true, false);
    }
    if (n_tokens <= 0) {
        LOGE("EmbeddingEngine: Tokenization produced 0 tokens");
        return nullptr;
    }

    if (n_tokens > 512) {
        LOGE("EmbeddingEngine: Token count %d exceeded n_ctx (512), truncating.", n_tokens);
        n_tokens = 512;
    }

    // 清空上一次的内存缓存
    llama_memory_clear(llama_get_memory(g_embed_ctx), true);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    batch.n_tokens = n_tokens;
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.seq_id[i][0] = 0;
        batch.n_seq_id[i] = 1;
        batch.logits[i] = true;
    }

    if (llama_decode(g_embed_ctx, batch) != 0) {
        LOGE("EmbeddingEngine: llama_decode failed!");
        llama_batch_free(batch);
        return nullptr;
    }

    const int n_embd = llama_model_n_embd(g_embed_model);
    float *embd = llama_get_embeddings_seq(g_embed_ctx, 0);

    std::vector<float> final_embd(n_embd, 0.0f);
    if (embd != nullptr) {
        for (int i = 0; i < n_embd; i++) {
            final_embd[i] = embd[i];
        }
    } else {
        // Fallback: 手动 mean pooling
        int count = 0;
        for (int t = 0; t < n_tokens; t++) {
            float *token_embd = llama_get_embeddings_ith(g_embed_ctx, t);
            if (token_embd != nullptr) {
                for (int i = 0; i < n_embd; i++) {
                    final_embd[i] += token_embd[i];
                }
                count++;
            }
        }
        if (count > 0) {
            for (int i = 0; i < n_embd; i++) {
                final_embd[i] /= count;
            }
        }
    }

    llama_batch_free(batch);

    // L2 归一化 (使得余弦相似度等价于点积)
    float sum_sq = 0.0f;
    for (int i = 0; i < n_embd; i++) {
        sum_sq += final_embd[i] * final_embd[i];
    }
    float norm = std::sqrt(sum_sq);
    if (norm > 1e-6f) {
        for (int i = 0; i < n_embd; i++) {
            final_embd[i] /= norm;
        }
    }

    jfloatArray result = env->NewFloatArray(n_embd);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, n_embd, final_embd.data());
    }

    return result;
}
