package cn.xxstudy.assistant.repository

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-scoped ownership of blocking native work. Cancellation is request-scoped. */
internal class InferenceRequestGate(
    private val prepareNative: (Long) -> Unit,
    private val stopNative: () -> Unit,
    private val invalidateSession: suspend () -> Unit = {}
) {
    private class Ticket(val owner: String, val request: String, val nativeId: Long) {
        val cancelled = AtomicBoolean(false)
    }
    private val mutex = Mutex()
    private val stateLock = Any()
    private var sequence = 0L
    private var active: Ticket? = null
    private var lastOwner: String? = null

    fun cancel(owner: String, request: String?): Boolean = synchronized(stateLock) {
        val ticket = active
        if (request == null || ticket == null || ticket.owner != owner || ticket.request != request) return false
        ticket.cancelled.set(true)
        stopNative()
        true
    }

    suspend fun <T> generate(owner: String, request: String, action: suspend ((String) -> Boolean, Boolean) -> T): T =
        coroutineScope {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val ownerChanged = lastOwner != owner
                val ticket = synchronized(stateLock) {
                    Ticket(owner, request, ++sequence).also {
                        prepareNative(it.nativeId)
                        active = it
                    }
                }
                lastOwner = owner
                val finished = AtomicBoolean(false)
                val cancellationWatcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        if (!finished.get()) cancel(owner, request)
                    }
                }
                try {
                    // JNI does not cooperate with coroutine cancellation. Keep ownership until
                    // the blocking call really returns; suppress all late output from a cancelled ticket.
                    val result = withContext(NonCancellable) {
                        action({ _ -> !ticket.cancelled.get() }, ownerChanged)
                    }
                    currentCoroutineContext().ensureActive()
                    if (ticket.cancelled.get()) throw CancellationException("Inference request cancelled")
                    result
                } finally {
                    try {
                        if (ticket.cancelled.get() || !currentCoroutineContext().isActive) {
                            withContext(NonCancellable) { invalidateSession() }
                        }
                    } finally {
                        synchronized(stateLock) {
                            finished.set(true)
                            if (active === ticket) active = null
                        }
                        withContext(NonCancellable) { cancellationWatcher.cancelAndJoin() }
                    }
                }
            }
        }

    /** Loads, resets and releases cannot run while JNI is still completing a cancelled request. */
    suspend fun <T> mutate(action: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) { action() }
    }
}
