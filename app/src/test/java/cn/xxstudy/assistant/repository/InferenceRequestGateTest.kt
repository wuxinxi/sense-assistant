package cn.xxstudy.assistant.repository

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class InferenceRequestGateTest {
    @Test fun cancelCannotStopAnotherOwnerOrAnotherRequest() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stops = 0
        val gate = InferenceRequestGate({}, { stops++ })
        val job = async { gate.generate("ui", "new") { accept, _ ->
            entered.complete(Unit); release.await(); assertTrue(accept("token")); "answer"
        } }
        entered.await()
        assertFalse(gate.cancel("api", "new"))
        assertFalse(gate.cancel("ui", "old"))
        assertFalse(gate.cancel("ui", null))
        assertEquals(0, stops)
        release.complete(Unit); assertEquals("answer", job.await())
        assertFalse(gate.cancel("ui", "new"))
    }

    @Test fun cancelledBlockingGenerationKeepsOwnershipAndSuppressesLateTokens() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lateAccepted = true
        var invalidations = 0
        val gate = InferenceRequestGate({}, { stopped.complete(Unit) }, { invalidations++ })
        val first = launch { gate.generate("ui", "a") { accept, _ ->
            entered.complete(Unit); release.await(); lateAccepted = accept("late"); "partial"
        } }
        entered.await(); first.cancel(); withTimeout(2000) { stopped.await() }
        var secondEntered = false
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            gate.generate("ui", "b") { _, _ -> secondEntered = true; "second" }
        }
        assertFalse(secondEntered)
        release.complete(Unit); first.join()
        assertEquals("second", second.await())
        assertFalse(lateAccepted)
        assertEquals(1, invalidations)
    }

    @Test fun explicitCancellationBeforeNativeEntryIsNotErased() = runBlocking<Unit> {
        var prepared = false
        var stopped = false
        lateinit var gate: InferenceRequestGate
        gate = InferenceRequestGate({ prepared = true }, { stopped = true })
        try {
            gate.generate("ui", "a") { accept, _ ->
                assertTrue(prepared)
                assertTrue(gate.cancel("ui", "a"))
                assertTrue(stopped)
                assertFalse(accept("first"))
                "partial"
            }
            fail("Cancelled output must never become a successful answer")
        } catch (_: CancellationException) { }
    }

    @Test fun queuedCancellationDoesNotStopActiveGeneration() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stops = 0
        val gate = InferenceRequestGate({}, { stops++ })
        val first = async { gate.generate("ui", "a") { _, _ -> entered.complete(Unit); release.await(); "ok" } }
        entered.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.generate("api", "b") { _, _ -> fail("queued request was cancelled"); "bad" }
        }
        queued.cancelAndJoin()
        assertEquals(0, stops)
        release.complete(Unit); first.await()
    }

    @Test fun modelMutationWaitsForRealNativeCompletion() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = InferenceRequestGate({}, {})
        val generation = launch { gate.generate("ui", "a") { _, _ -> entered.complete(Unit); release.await() } }
        entered.await(); generation.cancel()
        var mutated = false
        val mutation = async(start = CoroutineStart.UNDISPATCHED) { gate.mutate { mutated = true } }
        assertFalse(mutated)
        release.complete(Unit); generation.join(); mutation.await(); assertTrue(mutated)
    }

    @Test fun ownerChangesForceFreshSessionButSameOwnerCanKeepChat() = runBlocking<Unit> {
        val fresh = mutableListOf<Boolean>()
        val ids = mutableListOf<Long>()
        val gate = InferenceRequestGate({ ids += it }, {})
        for ((owner, request) in listOf("a" to "1", "a" to "2", "b" to "1", "a" to "3")) {
            gate.generate(owner, request) { _, changed -> fresh += changed }
        }
        assertEquals(listOf(true, false, true, true), fresh)
        assertEquals(listOf(1L, 2L, 3L, 4L), ids)
    }

    @Test fun failedPreparationDoesNotClaimNativeContextOwnership() = runBlocking<Unit> {
        var failPrepare = false
        val gate = InferenceRequestGate({ if (failPrepare) error("fixture preparation failure") }, {})
        gate.generate("a", "1") { _, _ -> "old" }
        failPrepare = true
        try { gate.generate("b", "1") { _, _ -> fail("must not enter generation") } }
        catch (_: IllegalStateException) { }
        failPrepare = false
        gate.generate("b", "2") { _, changed -> assertTrue(changed) }
    }

    @Test fun failedInvalidationStillReleasesOwnership() = runBlocking<Unit> {
        val gate = InferenceRequestGate({}, {}, { error("fixture invalidation failure") })
        try {
            gate.generate("a", "1") { _, _ -> gate.cancel("a", "1"); "partial" }
        } catch (_: IllegalStateException) { }
        assertFalse(gate.cancel("a", "1"))
        gate.generate("b", "1") { accept, _ -> assertTrue(accept("new")) }
    }
}
