package cn.xxstudy.assistant.server

import cn.xxstudy.assistant.repository.LlamaRepository
import cn.xxstudy.assistant.security.CommercialSafetyPolicy

/** Compatibility facade. No listener is created until authenticated API v2 is delivered. */
object LlamaServer {
    @Suppress("UNUSED_PARAMETER")
    fun start(repository: LlamaRepository, port: Int = 8989) {
        check(!CommercialSafetyPolicy.externalApiAvailable) {
            "Implement authenticated, scoped API before enabling the external interface"
        }
        android.util.Log.i("LlamaServer", CommercialSafetyPolicy.externalApiStatus)
    }

    fun stop() = Unit
}
