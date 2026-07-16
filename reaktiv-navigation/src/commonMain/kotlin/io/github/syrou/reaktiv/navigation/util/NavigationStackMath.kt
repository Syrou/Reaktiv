package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

internal data class StackSnapshot(
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>,
    val modalContexts: Map<String, ModalContext>
)

internal object NavigationStackMath {

    private fun NavigationEntry.isModal(): Boolean = navigatable is Modal

    private fun NavigationEntry.renderLayer(): RenderLayer = navigatable.renderLayer

    internal fun applyNavigate(
        snapshot: StackSnapshot,
        entry: NavigationEntry,
        modalContext: ModalContext?,
        dismissModals: Boolean
    ): StackSnapshot {
        val isModal = entry.isModal()
        val isSystemLayer = entry.renderLayer() == RenderLayer.SYSTEM
        val baseBackStack = if (dismissModals) snapshot.backStack.filter { !it.isModal() } else snapshot.backStack
        val stackPosition = when {
            isModal -> snapshot.backStack.size + 1
            baseBackStack.isEmpty() -> 1
            else -> baseBackStack.size + 1
        }
        val positionedEntry = entry.copy(stackPosition = stackPosition)

        val newBackStack = if (isSystemLayer) {
            snapshot.backStack + positionedEntry
        } else {
            val systemTail = baseBackStack.filter { it.renderLayer() == RenderLayer.SYSTEM }
            val nonSystemBase = baseBackStack.filter { it.renderLayer() != RenderLayer.SYSTEM }
            when {
                isModal -> nonSystemBase + positionedEntry + systemTail
                nonSystemBase.isEmpty() -> listOf(positionedEntry) + systemTail
                else -> nonSystemBase + positionedEntry + systemTail
            }
        }

        val effectiveCurrentEntry = if (!isSystemLayer &&
            newBackStack.lastOrNull()?.renderLayer() == RenderLayer.SYSTEM
        ) {
            newBackStack.last()
        } else {
            positionedEntry
        }

        val newModalContexts = when {
            dismissModals -> emptyMap()
            isModal && modalContext != null ->
                snapshot.modalContexts + (positionedEntry.path to modalContext)
            !isModal && !dismissModals && snapshot.currentEntry.isModal() && snapshot.modalContexts.isNotEmpty() -> {
                val modalPath = snapshot.currentEntry.path
                val ctx = snapshot.modalContexts[modalPath]
                if (ctx != null) {
                    val underlying = ctx.originalUnderlyingScreenEntry.path
                    mapOf(underlying to ctx.copy(navigatedAwayToRoute = positionedEntry.path))
                } else {
                    snapshot.modalContexts
                }
            }
            else -> snapshot.modalContexts
        }
        return StackSnapshot(effectiveCurrentEntry, newBackStack, newModalContexts)
    }

    internal fun applyReplace(snapshot: StackSnapshot, entry: NavigationEntry): StackSnapshot {
        val positioned = entry.copy(
            stackPosition = if (snapshot.backStack.isEmpty()) 1 else snapshot.backStack.size
        )
        val newBackStack = if (snapshot.backStack.isEmpty()) {
            listOf(positioned)
        } else {
            snapshot.backStack.dropLast(1) + positioned
        }
        return snapshot.copy(currentEntry = positioned, backStack = newBackStack)
    }

    internal fun applyBack(snapshot: StackSnapshot): StackSnapshot {
        if (snapshot.backStack.size <= 1) return snapshot
        val trimmed = snapshot.backStack.dropLast(1)
        val target = trimmed.last().copy(stackPosition = trimmed.size)
        val finalStack = trimmed.dropLast(1) + target
        val modalCtx = snapshot.modalContexts[target.path]
        return if (modalCtx != null) {
            val modal = modalCtx.modalEntry
            StackSnapshot(
                currentEntry = modal,
                backStack = finalStack + modal,
                modalContexts = mapOf(modal.path to modalCtx.copy(navigatedAwayToRoute = null))
            )
        } else {
            StackSnapshot(
                currentEntry = target,
                backStack = finalStack,
                modalContexts = snapshot.modalContexts.filterKeys { it != snapshot.currentEntry.path }
            )
        }
    }

    internal fun applyClearBackstack(snapshot: StackSnapshot): StackSnapshot =
        snapshot.copy(backStack = emptyList(), modalContexts = emptyMap())

    internal fun applyPopUpTo(
        snapshot: StackSnapshot,
        targetIndex: Int,
        inclusive: Boolean,
        entryToReAdd: NavigationEntry?
    ): StackSnapshot {
        if (targetIndex < 0) return snapshot
        val trimmedBackStack = if (inclusive) {
            snapshot.backStack.take(targetIndex)
        } else {
            snapshot.backStack.take(targetIndex + 1)
        }
        val finalBackStack = if (entryToReAdd != null && trimmedBackStack.none { it.path == entryToReAdd.path }) {
            trimmedBackStack + entryToReAdd.copy(stackPosition = trimmedBackStack.size + 1)
        } else {
            trimmedBackStack
        }
        if (finalBackStack.isEmpty()) return snapshot
        val newCurrentEntry = finalBackStack.last()
        val backStackPaths = finalBackStack.map { it.path }.toSet()
        return StackSnapshot(
            currentEntry = newCurrentEntry,
            backStack = finalBackStack,
            modalContexts = snapshot.modalContexts.filterKeys { it in backStackPaths }
        )
    }
}
