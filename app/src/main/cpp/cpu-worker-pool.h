#pragma once

#include "llama.h"
#include "ggml-backend.h"

// Resolve functions from the dynamically loaded CPU module, never link it
// directly: MODULEs without a SONAME can record a host-only dependency path.
class CpuWorkerPool {
public:
    CpuWorkerPool() = default;
    CpuWorkerPool(const CpuWorkerPool &) = delete;
    CpuWorkerPool & operator=(const CpuWorkerPool &) = delete;
    ~CpuWorkerPool() { reset(); }

    bool attach(llama_context * ctx, int threads) {
        if (!ctx || pool_) return false;
        auto reg = ggml_backend_reg_by_name("CPU");
        if (!reg) return false;
        auto create = reinterpret_cast<Create>(ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new"));
        destroy_ = reinterpret_cast<Destroy>(ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free"));
        if (!create || !destroy_) return false;
        auto params = ggml_threadpool_params_default(threads);
        pool_ = create(&params);
        if (!pool_) return false;
        llama_attach_threadpool(ctx, pool_, nullptr);
        return true;
    }

    // The pool must outlive the context's CPU backend. First finish inference,
    // detach, and destroy the context, then reset this pool. Detach alone does
    // not clear the backend's cached pool pointer until its next compute.
    void reset() {
        if (pool_) destroy_(pool_);
        pool_ = nullptr;
        destroy_ = nullptr;
    }

private:
    using Create = ggml_threadpool_t (*)(ggml_threadpool_params *);
    using Destroy = void (*)(ggml_threadpool_t);
    ggml_threadpool_t pool_ = nullptr;
    Destroy destroy_ = nullptr;
};
