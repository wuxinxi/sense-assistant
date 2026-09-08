#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <atomic>
#include <mutex>
#include "llama.h"
#include <android/log.h>

#define TAG "TangRenLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global pointers for the singleton engine
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;

// 线程安全与打断控制
std::mutex g_ctx_mutex;
std::atomic<bool> g_should_stop{false};

extern "C"
JNIEXPORT void JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_stopGeneration(JNIEnv *env, jobject thiz) {
    LOGI("LlamaEngine_stopGeneration called -> setting g_should_stop = true");
    g_should_stop.store(true);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_model != nullptr) return JNI_TRUE; // Already loaded

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Attempting to load model from: %s", path);
    
    llama_backend_init();
    
    llama_model_params model_params = llama_model_default_params();
    g_model = llama_model_load_from_file(path, model_params);
    
    env->ReleaseStringUTFChars(model_path, path);
    
    if (g_model == nullptr) {
        LOGE("Failed to load model!");
        return JNI_FALSE;
    }
    
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024; // Limit context for mobile test
    
    // 性能优化：显式指定线程数（通常设置为 4 个大核能达到最佳能效比）
    ctx_params.n_threads = 4;
    ctx_params.n_threads_batch = 4;
    
    g_ctx = llama_init_from_model(g_model, ctx_params);
    
    LOGI("Model and Context loaded successfully!");
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
    
    // 1. 组装 Chat Template（若上层已包装则直接使用，否则以标准 ChatML 格式包裹）
    std::string prompt_str(prompt_cstr);
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

    // 开始计时
    auto t_start = std::chrono::high_resolution_clock::now();

    // 核心修复：每次全新对话必须清空上下文 Memory (即原 KV Cache)，否则位置会无限累加，导致速度暴降！
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // 3. 将 Prompt 送入推理上下文 (Ingest)
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(g_ctx, batch) != 0) {
        env->ReleaseStringUTFChars(prompt, prompt_cstr);
        return env->NewStringUTF("Error: llama_decode failed.");
    }

    // 4. 初始化采样器 (支持温度与 Top-P，同时保留确定性分布)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    
    std::string response = "";
    std::string token_stream_buf = "";
    int max_predict = 1536; // 放宽最大步数至 1536，完整承载 MiniCPM5 深度思考链与长文本回答
    
    bool is_first_token = true;
    long long ttft_ms = 0;
    int generated_tokens = 0;
    
    for (int i = 0; i < max_predict; i++) {
        // 核心检测：检查是否收到上层打断请求
        if (g_should_stop.load()) {
            LOGI("Generation interrupted by user request at token %d.", i);
            break;
        }

        // 采样预测下一个 token
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        llama_sampler_accept(smpl, id);
        
        if (is_first_token) {
            auto t_first = std::chrono::high_resolution_clock::now();
            ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_first - t_start).count();
            is_first_token = false;
        }
        
        generated_tokens++;
        
        // 检查是否生成结束 (End of generation)
        if (llama_vocab_is_eog(vocab, id)) {
            break; 
        }
        
        // Token 转文字 (special 设置为 true，使得 <|thought_begin|> 等思考标记能作为文本输出供上层状态机捕获)
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n_chars > 0) {
            response.append(buf, n_chars);
            token_stream_buf.append(buf, n_chars);

            // 核心修复：检查缓冲区中完整 UTF-8 字符长度，防止中文字符跨 Token 截断导致 NewStringUTF 报 illegal continuation byte 闪退
            size_t complete_len = get_complete_utf8_length(token_stream_buf);
            if (complete_len > 0) {
                std::string ready_text = token_stream_buf.substr(0, complete_len);
                token_stream_buf.erase(0, complete_len);

                jstring j_token = env->NewStringUTF(ready_text.c_str());
                env->CallVoidMethod(callback, onTokenMethod, j_token);
                env->DeleteLocalRef(j_token);
            }
        }
        
        // 将新 token 放回上下文准备下一轮推理
        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            break;
        }
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



