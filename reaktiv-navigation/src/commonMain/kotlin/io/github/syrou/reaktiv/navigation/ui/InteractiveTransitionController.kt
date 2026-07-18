package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import io.github.syrou.reaktiv.core.util.currentTimeMillis
import io.github.syrou.reaktiv.navigation.NavigationAction
import io.github.syrou.reaktiv.navigation.ScrubState
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlin.math.abs

internal val LocalInteractiveTransitionController =
    compositionLocalOf<InteractiveTransitionController?> { null }

internal class InteractiveTransitionController {

    enum class Phase { Idle, Scrubbing, Settling }

    sealed interface ScrubKind {
        data class ContentBack(
            val topEntry: NavigationEntry,
            val revealedEntry: NavigationEntry
        ) : ScrubKind

        data class ContentDismiss(
            val topEntry: NavigationEntry,
            val revealedEntry: NavigationEntry?
        ) : ScrubKind

        data class ModalDismiss(
            val modalEntry: NavigationEntry
        ) : ScrubKind
    }

    internal data class Handoff(val poppedKey: String, val targetKey: String)

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    var scrubKind: ScrubKind? by mutableStateOf(null)
        private set

    var contentTransitionActive: Boolean by mutableStateOf(false)

    var committedTarget: NavigationEntry? by mutableStateOf(null)
        private set

    var rootCoordinates: LayoutCoordinates? = null

    var indicatorCoordinates: LayoutCoordinates? = null

    fun markCommittedTarget(entry: NavigationEntry) {
        committedTarget = entry
    }

    private var progressState = mutableFloatStateOf(0f)

    val progress: Float get() = progressState.value

    private var pendingHandoff: Handoff? = null

    internal var externallyDriven: Boolean = false

    internal var scrubDispatch: ((NavigationAction) -> Unit)? = null

    private var lastReportedAtMs: Long = 0L
    private var lastReportedProgress: Float = 0f

    private fun ScrubKind.scrubKindName(): String = when (this) {
        is ScrubKind.ContentBack -> "back-scrub"
        is ScrubKind.ContentDismiss -> "dismiss-scrub"
        is ScrubKind.ModalDismiss -> "modal-dismiss-scrub"
    }

    private fun ScrubKind.toScrubState(progressValue: Float): ScrubState = when (this) {
        is ScrubKind.ContentBack -> ScrubState(scrubKindName(), topEntry.stableKey, revealedEntry.stableKey, progressValue)
        is ScrubKind.ContentDismiss -> ScrubState(scrubKindName(), topEntry.stableKey, revealedEntry?.stableKey, progressValue)
        is ScrubKind.ModalDismiss -> ScrubState(scrubKindName(), modalEntry.stableKey, null, progressValue)
    }

    private fun dispatchScrubUpdate(progressValue: Float) {
        if (externallyDriven) return
        val kind = scrubKind ?: return
        scrubDispatch?.invoke(NavigationAction.ScrubUpdate(kind.toScrubState(progressValue)))
    }

    fun beginScrub(kind: ScrubKind): Boolean {
        if (phase != Phase.Idle) return false
        scrubKind = kind
        phase = Phase.Scrubbing
        lastReportedAtMs = 0L
        lastReportedProgress = 0f
        dispatchScrubUpdate(progressState.value)
        return true
    }

    fun scrubTo(value: Float) {
        if (phase != Phase.Scrubbing) return
        progressState.value = value.coerceIn(0f, 1f)
        val now = currentTimeMillis()
        if (now - lastReportedAtMs >= 16L || abs(progressState.value - lastReportedProgress) >= 0.01f) {
            lastReportedAtMs = now
            lastReportedProgress = progressState.value
            dispatchScrubUpdate(progressState.value)
        }
    }

    suspend fun settle(commit: Boolean, initialVelocity: Float = 0f) {
        if (phase == Phase.Idle) return
        if (!externallyDriven && !commit) {
            scrubDispatch?.invoke(NavigationAction.ScrubEnd)
        }
        phase = Phase.Settling
        val target = if (commit) 1f else 0f
        val start = progressState.value
        val remaining = abs(target - start)
        if (remaining > 0f) {
            val duration = (SETTLE_FULL_DURATION_MILLIS * remaining).toInt()
                .coerceAtLeast(SETTLE_MIN_DURATION_MILLIS)
            val animatable = Animatable(start)
            animatable.animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = duration, easing = LinearOutSlowInEasing),
                initialVelocity = initialVelocity
            ) {
                progressState.value = this.value.coerceIn(0f, 1f)
            }
        }
        progressState.value = target
    }

    private var pendingModalHandoff: String? = null

    fun armHandoff(poppedKey: String, targetKey: String) {
        pendingHandoff = Handoff(poppedKey, targetKey)
    }

    fun consumeHandoff(oldKey: String, newKey: String): Boolean {
        val handoff = pendingHandoff ?: return false
        pendingHandoff = null
        return handoff.poppedKey == oldKey && handoff.targetKey == newKey
    }

    fun clearHandoffs() {
        pendingHandoff = null
        pendingModalHandoff = null
    }

    fun armModalHandoff(modalKey: String) {
        pendingModalHandoff = modalKey
    }

    fun consumeModalHandoff(removedKey: String): Boolean {
        val pending = pendingModalHandoff ?: return false
        pendingModalHandoff = null
        return pending == removedKey
    }

    fun reset() {
        progressState.value = 0f
        scrubKind = null
        committedTarget = null
        phase = Phase.Idle
    }

    companion object {
        const val COMMIT_PROGRESS_THRESHOLD = 0.3f
        const val COMMIT_VELOCITY_DP_PER_SEC = 700f
        const val SETTLE_FULL_DURATION_MILLIS = 250
        const val SETTLE_MIN_DURATION_MILLIS = 80

        fun shouldCommit(progress: Float, velocity: Float, velocityThreshold: Float): Boolean = when {
            velocity >= velocityThreshold -> true
            velocity <= -velocityThreshold -> false
            else -> progress > COMMIT_PROGRESS_THRESHOLD
        }
    }
}
