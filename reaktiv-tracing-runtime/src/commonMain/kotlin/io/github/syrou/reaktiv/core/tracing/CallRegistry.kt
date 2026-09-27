package io.github.syrou.reaktiv.core.tracing

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndUpdate
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.Job

@OptIn(ExperimentalAtomicApi::class)
internal object CallRegistry {
    private val jobStacks = AtomicReference<Map<Job, List<String>>>(emptyMap())
    private val callJobs = AtomicReference<Map<String, Job>>(emptyMap())

    fun push(job: Job, callId: String): String? {
        val previous = jobStacks.fetchAndUpdate { stacks -> stacks + (job to (stacks[job].orEmpty() + callId)) }
        callJobs.update { jobs -> jobs + (callId to job) }
        return previous[job]?.lastOrNull()
    }

    fun pop(callId: String) {
        val job = callJobs.fetchAndUpdate { jobs -> jobs - callId }[callId] ?: return
        jobStacks.update { stacks ->
            val stack = stacks[job] ?: return@update stacks
            val trimmed = stack - callId
            if (trimmed.isEmpty()) stacks - job else stacks + (job to trimmed)
        }
    }

    fun clear() {
        jobStacks.store(emptyMap())
        callJobs.store(emptyMap())
    }
}
