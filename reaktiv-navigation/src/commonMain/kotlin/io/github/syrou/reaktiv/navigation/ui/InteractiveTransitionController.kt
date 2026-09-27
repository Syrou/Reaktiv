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
import io.github.syrou.reaktiv.navigation.ScrubType
import io.github.syrou.reaktiv.navigation.model.NavigationEntry
import kotlin.math.abs

internal val LocalInteractiveTransitionController =
    compositionLocalOf<InteractiveTransitionController?> { null }

internal class InteractiveTransitionController(
    private val dispatch: (NavigationAction) -> Unit = {}
) {

    enum class Source { Local, Replicated }

    sealed interface Phase {
        data object Idle : Phase

        data class Scrubbing(val kind: ScrubKind, val source: Source) : Phase

        data class Settling(val kind: ScrubKind, val source: Source, val commit: Boolean) : Phase
    }

    sealed interface ScrubKind {
        val top: NavigationEntry
        val revealed: NavigationEntry?
        val type: ScrubType

        data class ContentBack(
            override val top: NavigationEntry,
            override val revealed: NavigationEntry
        ) : ScrubKind {
            override val type: ScrubType get() = ScrubType.Back
        }

        data class ContentDismiss(
            override val top: NavigationEntry,
            override val revealed: NavigationEntry?
        ) : ScrubKind {
            override val type: ScrubType get() = ScrubType.Dismiss
        }

        data class ModalDismiss(
            override val top: NavigationEntry
        ) : ScrubKind {
            override val revealed: NavigationEntry? get() = null
            override val type: ScrubType get() = ScrubType.ModalDismiss
        }
    }

    var phase: Phase by mutableStateOf(Phase.Idle)
        private set

    val scrubKind: ScrubKind?
        get() = when (val current = phase) {
            Phase.Idle -> null
            is Phase.Scrubbing -> current.kind
            is Phase.Settling -> current.kind
        }

    val isReplicating: Boolean
        get() = sourceOf(phase) == Source.Replicated

    val committedTarget: NavigationEntry?
        get() = (phase as? Phase.Settling)?.takeIf { it.commit }?.kind?.revealed

    var contentTransitionActive: Boolean by mutableStateOf(false)

    var rootCoordinates: LayoutCoordinates? = null

    var indicatorCoordinates: LayoutCoordinates? = null

    private var progressState = mutableFloatStateOf(0f)

    val progress: Float get() = progressState.value

    private var ended = true
    private var lastReportedAtMs: Long = 0L
    private var lastReportedProgress: Float = 0f

    private fun sourceOf(phase: Phase): Source? = when (phase) {
        Phase.Idle -> null
        is Phase.Scrubbing -> phase.source
        is Phase.Settling -> phase.source
    }

    private fun report(progressValue: Float) {
        val scrubbing = phase as? Phase.Scrubbing ?: return
        if (scrubbing.source != Source.Local) return
        val kind = scrubbing.kind
        dispatch(
            NavigationAction.ScrubUpdate(
                ScrubState(kind.type, kind.top.stableKey, kind.revealed?.stableKey, progressValue)
            )
        )
    }

    fun beginScrub(kind: ScrubKind, source: Source = Source.Local): Boolean {
        if (phase != Phase.Idle) return false
        phase = Phase.Scrubbing(kind, source)
        ended = false
        lastReportedAtMs = 0L
        lastReportedProgress = 0f
        report(progressState.value)
        return true
    }

    fun scrubTo(value: Float) {
        if (phase !is Phase.Scrubbing) return
        progressState.value = value.coerceIn(0f, 1f)
        val now = currentTimeMillis()
        if (now - lastReportedAtMs >= 16L || abs(progressState.value - lastReportedProgress) >= 0.01f) {
            lastReportedAtMs = now
            lastReportedProgress = progressState.value
            report(progressState.value)
        }
    }

    suspend fun settle(commit: Boolean, initialVelocity: Float = 0f) {
        val kind = scrubKind ?: return
        val source = sourceOf(phase) ?: return
        if (!commit) end()
        phase = Phase.Settling(kind, source, commit)
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

    fun end() {
        if (!ended && sourceOf(phase) == Source.Local) dispatch(NavigationAction.ScrubEnd)
        ended = true
    }

    fun markLanded() {
        ended = true
    }

    fun reset() {
        end()
        progressState.value = 0f
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
