package io.github.syrou.reaktiv.core.tracing

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

internal actual object CallRegistry {
    private val jobStacks = ConcurrentHashMap<Job, ArrayDeque<String>>()
    private val callJobs = ConcurrentHashMap<String, Job>()

    actual fun push(job: Job, callId: String): String? {
        val stack = jobStacks.computeIfAbsent(job) { ArrayDeque() }
        val parent = stack.lastOrNull()
        stack.addLast(callId)
        callJobs[callId] = job
        return parent
    }

    actual fun pop(callId: String) {
        val job = callJobs.remove(callId) ?: return
        val stack = jobStacks[job] ?: return
        stack.remove(callId)
        if (stack.isEmpty()) {
            jobStacks.remove(job)
        }
    }

    actual fun clear() {
        jobStacks.clear()
        callJobs.clear()
    }
}
