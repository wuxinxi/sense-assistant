// CPU-only differential regression for JNI's per-token decode pattern.
// Runs disposable vs reusable workers with identical teacher-forced tokens.
#include "llama.h"
#include "ggml-backend.h"
#include "cpu-worker-pool.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using Clock = std::chrono::steady_clock;

int main(int argc, char ** argv) {
    if (argc != 2) {
        std::fprintf(stderr, "Usage: native-decode-probe MODEL\n");
        return 2;
    }
    auto reg = ggml_backend_load("libggml-cpu.so");
    if (!reg) return 2;
    llama_backend_init();
    ggml_backend_dev_t cpu_only[] = {nullptr};
    auto mp = llama_model_default_params();
    mp.devices = cpu_only;
    mp.n_gpu_layers = 0;
    auto model = llama_model_load_from_file(argv[1], mp);
    if (!model) return 2;
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_threads = cp.n_threads_batch = 4;
    cp.offload_kqv = cp.op_offload = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    const auto vocab = llama_model_get_vocab(model);
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::string fixture;
    while (fixture.size() < 20000) fixture += "Hive is a local key-value database. A box stores keys and values. Flutter uses Dart. ";
    int count = -llama_tokenize(vocab, fixture.c_str(), fixture.size(), nullptr, 0, true, true);
    if (count < 1024) return 2;
    std::vector<llama_token> prompt(count);
    if (llama_tokenize(vocab, fixture.c_str(), fixture.size(), prompt.data(), count, true, true) != count) return 2;
    prompt.resize(1024);
    constexpr int steps = 64;
    std::vector<llama_token> continuation(steps);
    std::vector<float> reference(steps * n_vocab);
    CpuWorkerPool workers;
    double timings[4] = {};
    float max_diff = 0;
    // ABBA order reduces simple temperature/order bias. Only round 0 picks tokens;
    // subsequent rounds evaluate exactly those same tokens (including EOS).
    for (int round = 0; round < 4; ++round) {
        const bool reuse = round == 1 || round == 2;
        auto ctx = llama_init_from_model(model, cp);
        if (!ctx) return 2;
        if (reuse && !workers.attach(ctx, 4)) return 2;
        llama_memory_clear(llama_get_memory(ctx), true);
        auto batch = llama_batch_init(1024, 0, 1);
        batch.n_tokens = 1024;
        for (int i = 0; i < 1024; ++i) {
            batch.token[i] = prompt[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = i == 1023;
        }
        if (llama_decode(ctx, batch)) return 2;
        for (int step = 0; step < steps; ++step) {
            auto logits = llama_get_logits_ith(ctx, -1);
            if (!logits) return 2;
            if (round == 0) {
                std::copy(logits, logits + n_vocab, reference.begin() + step * n_vocab);
                continuation[step] = std::max_element(logits, logits + n_vocab) - logits;
            } else {
                for (int i = 0; i < n_vocab; ++i) {
                    if (!std::isfinite(logits[i])) return 3;
                    max_diff = std::max(max_diff, std::fabs(logits[i] - reference[step * n_vocab + i]));
                }
            }
            batch.n_tokens = 1;
            batch.token[0] = continuation[step];
            batch.pos[0] = 1024 + step;
            batch.logits[0] = true;
            const auto start = Clock::now();
            if (llama_decode(ctx, batch)) return 2;
            timings[round] += std::chrono::duration<double>(Clock::now() - start).count();
        }
        llama_batch_free(batch);
        std::printf("round=%d workers=%s decode_tps=%.2f\n", round, reuse ? "reusable" : "disposable", steps / timings[round]);
        std::fflush(stdout);
        llama_detach_threadpool(ctx);
        llama_free(ctx);
        workers.reset();
    }
    llama_model_free(model);
    llama_backend_free();
    const double ratio = (timings[0] + timings[3]) / (timings[1] + timings[2]);
    std::printf("speed_ratio=%.3f max_logit_difference=%.6f\n", ratio, max_diff);
    if (max_diff > 0.001f) {
        std::puts("FAIL: CPU worker configuration changed logits");
        return 3;
    }
    const char * minimum = std::getenv("MIN_SPEED_RATIO");
    if (minimum && ratio < std::atof(minimum)) {
        std::puts("FAIL: speed ratio below requested threshold");
        return 4;
    }
    std::puts("PASS: logits unchanged and speed ratio meets threshold");
    return 0;
}
