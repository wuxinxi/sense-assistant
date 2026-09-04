#include <jni.h>
#include <string>
#include "llama.h"
#include <android/log.h>

#define TAG "TangRenLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Global pointers for the singleton engine
llama_model *g_model = nullptr;
llama_context *g_ctx = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_initContext(JNIEnv *env, jobject thiz, jstring model_path) {
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

#include <vector>
#include <chrono>

// 这是一个简化的推理接口，用于快速打通 MVP 验证。
extern "C"
JNIEXPORT jstring JNICALL
Java_cn_xxstudy_assistant_engine_LlamaEngine_generateText(JNIEnv *env, jobject thiz, jstring prompt, jobject callback) {
    if (g_model == nullptr || g_ctx == nullptr) {
        return env->NewStringUTF("Error: Model not initialized.");
    }
    
    // 获取回调的类和方法
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    
    const char *prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Received prompt: %s", prompt_cstr);
    
    // 1. 组装 Qwen2.5 专属 Chat Template
    std::string qwen_prompt = "<|im_start|>user\n" + std::string(prompt_cstr) + "<|im_end|>\n<|im_start|>assistant\n";
    
    // 2. Tokenize
    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<llama_token> tokens(qwen_prompt.length() + 100);
    int n_tokens = llama_tokenize(vocab, qwen_prompt.c_str(), qwen_prompt.length(), tokens.data(), tokens.size(), true, true);
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

    // 4. 初始化采样器 (贪婪采样，最快返回)
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    
    std::string response = "";
    int max_predict = 256; 
    
    bool is_first_token = true;
    long long ttft_ms = 0;
    int generated_tokens = 0;
    
    for (int i = 0; i < max_predict; i++) {
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
        
        // Token 转文字
        char buf[128];
        int n_chars = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
        if (n_chars > 0) {
            response.append(buf, n_chars);
            // 流式回调回传给 Kotlin
            jstring j_token = env->NewStringUTF(std::string(buf, n_chars).c_str());
            env->CallVoidMethod(callback, onTokenMethod, j_token);
            env->DeleteLocalRef(j_token);
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
    
    char metrics_buf[256];
    snprintf(metrics_buf, sizeof(metrics_buf), "<|metrics|>{\"ttft_ms\":%lld,\"total_ms\":%lld,\"speed\":%.1f}", ttft_ms, total_ms, speed);
    response += metrics_buf;
    
    llama_sampler_free(smpl);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);
    
    return env->NewStringUTF(response.c_str());
}
