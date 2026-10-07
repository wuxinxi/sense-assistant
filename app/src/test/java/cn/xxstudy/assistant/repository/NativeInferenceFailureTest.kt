package cn.xxstudy.assistant.repository

import org.junit.Assert.*
import org.junit.Test

class NativeInferenceFailureTest {
    @Test fun successfulModelTextIsNotChanged() {
        assertEquals("答案\n<|metrics|>{}", NativeInferenceFailure.requireSuccess("答案\n<|metrics|>{}"))
    }
    @Test fun nativeErrorsBecomeTypedFailuresWithoutDiagnosticLeakage() {
        val fixtures = mapOf(
            "Error: Model not initialized." to InferenceFailureCode.MODEL_NOT_READY,
            "Error: Prompt too long." to InferenceFailureCode.INPUT_TOO_LONG,
            "Error: Prompt exceeds context window." to InferenceFailureCode.INPUT_TOO_LONG,
            "Error: llama_decode failed. /private/source" to InferenceFailureCode.RUNTIME_FAILURE
        )
        for ((raw, expected) in fixtures) {
            try { NativeInferenceFailure.requireSuccess(raw); fail("native error returned as answer") }
            catch (failure: NativeInferenceFailure) {
                assertEquals(expected, failure.code)
                assertFalse(failure.message!!.contains("/private"))
            }
        }
    }
}
