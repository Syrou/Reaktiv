package io.github.syrou.reaktiv.core.tracing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.Job

internal expect object CallRegistry {
    fun push(job: Job, callId: String): String?
    fun pop(callId: String)
    fun clear()
}

@OptIn(ExperimentalAtomicApi::class)
internal class CowCallRegistry {
    private val jobStacks = AtomicReference<Map<Job, List<String>>>(emptyMap())
    private val callJobs = AtomicReference<Map<String, Job>>(emptyMap())

    fun push(job: Job, callId: String): String? {
        var parent: String? = null
        while (true) {
            val stacks = jobStacks.load()
            val stack = stacks[job].orEmpty()
            parent = stack.lastOrNull()
            if (jobStacks.compareAndSet(stacks, stacks + (job to (stack + callId)))) break
        }
        while (true) {
            val jobs = callJobs.load()
            if (callJobs.compareAndSet(jobs, jobs + (callId to job))) break
        }
        return parent
    }

    fun pop(callId: String) {
        val job = callJobs.load()[callId] ?: return
        while (true) {
            val jobs = callJobs.load()
            if (callJobs.compareAndSet(jobs, jobs - callId)) break
        }
        while (true) {
            val stacks = jobStacks.load()
            val stack = stacks[job] ?: return
            val trimmed = stack - callId
            val updated = if (trimmed.isEmpty()) stacks - job else stacks + (job to trimmed)
            if (jobStacks.compareAndSet(stacks, updated)) return
        }
    }

    fun clear() {
        jobStacks.store(emptyMap())
        callJobs.store(emptyMap())
    }
}
