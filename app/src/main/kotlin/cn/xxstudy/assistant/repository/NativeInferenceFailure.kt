package cn.xxstudy.assistant.repository

enum class InferenceFailureCode { MODEL_NOT_READY, INPUT_TOO_LONG, RUNTIME_FAILURE }

class NativeInferenceFailure(val code: InferenceFailureCode) : IllegalStateException(code.name) {
    companion object {
        fun requireSuccess(raw: String): String {
            if (!raw.startsWith("Error:")) return raw
            val code = when {
                raw.contains("not initialized", ignoreCase = true) -> InferenceFailureCode.MODEL_NOT_READY
                raw.contains("too long", ignoreCase = true) || raw.contains("context window", ignoreCase = true) -> InferenceFailureCode.INPUT_TOO_LONG
                else -> InferenceFailureCode.RUNTIME_FAILURE
            }
            // Do not propagate native diagnostics as user-visible model answers or history.
            throw NativeInferenceFailure(code)
        }
    }
}
