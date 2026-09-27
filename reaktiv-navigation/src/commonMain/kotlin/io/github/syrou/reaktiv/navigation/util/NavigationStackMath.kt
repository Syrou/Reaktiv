package io.github.syrou.reaktiv.navigation.util

import io.github.syrou.reaktiv.navigation.definition.LoadingModal
import io.github.syrou.reaktiv.navigation.definition.Modal
import io.github.syrou.reaktiv.navigation.definition.Screen
import io.github.syrou.reaktiv.navigation.layer.RenderLayer
import io.github.syrou.reaktiv.navigation.model.ModalContext
import io.github.syrou.reaktiv.navigation.model.NavigationEntry

internal data class StackSnapshot(
    val currentEntry: NavigationEntry,
    val backStack: List<NavigationEntry>
)

internal object NavigationStackMath {

    private fun NavigationEntry.isModal(): Boolean = navigatable is Modal

    private fun NavigationEntry.renderLayer(): RenderLayer = navigatable.renderLayer

    internal fun underlyingScreen(backStack: List<NavigationEntry>, modalIndex: Int): NavigationEntry? =
        backStack.subList(0, modalIndex).lastOrNull { it.navigatable is Screen }

    internal fun deriveModalContexts(backStack: List<NavigationEntry>): Map<String, ModalContext> {
        val contexts = LinkedHashMap<String, ModalContext>()
        backStack.forEachIndexed { index, entry ->
            if (entry.isModal()) {
                underlyingScreen(backStack, index)?.let { contexts[entry.path] = ModalContext(entry, it) }
            }
        }
        return contexts
    }

    internal fun applyNavigate(
        snapshot: StackSnapshot,
        entry: NavigationEntry,
        dismissModals: Boolean
    ): StackSnapshot {
        val isModal = entry.isModal()
        val isSystemLayer = entry.renderLayer() == RenderLayer.SYSTEM
        val stack = if (isSystemLayer) snapshot.backStack else withoutPlaceholder(snapshot.backStack)
        val baseBackStack = if (dismissModals) {
            stack.filter { !it.isModal() || it.renderLayer() == RenderLayer.SYSTEM }
        } else {
            stack
        }
        val stackPosition = when {
            isModal -> stack.size + 1
            baseBackStack.isEmpty() -> 1
            else -> baseBackStack.size + 1
        }
        val positionedEntry = entry.copy(stackPosition = stackPosition)

        val newBackStack = if (isSystemLayer) {
            stack + positionedEntry
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

        return StackSnapshot(effectiveCurrentEntry, newBackStack)
    }

    internal fun applyReplace(snapshot: StackSnapshot, entry: NavigationEntry): StackSnapshot {
        val stack = withoutPlaceholder(snapshot.backStack)
        val systemTail = stack.takeLastWhile { it.renderLayer() == RenderLayer.SYSTEM }
        val content = stack.dropLast(systemTail.size)
        val positioned = entry.copy(
            stackPosition = if (content.isEmpty()) 1 else content.size
        )
        val newBackStack = content.dropLast(1) + positioned + systemTail
        return StackSnapshot(systemTail.lastOrNull() ?: positioned, newBackStack)
    }

    private fun withoutPlaceholder(backStack: List<NavigationEntry>): List<NavigationEntry> =
        backStack.filter { it.navigatable !is LoadingModal }

    internal fun applyBack(snapshot: StackSnapshot): StackSnapshot {
        if (snapshot.backStack.size <= 1) return snapshot
        val trimmed = snapshot.backStack.dropLast(1)
        val target = trimmed.last().copy(stackPosition = trimmed.size)
        val finalStack = trimmed.dropLast(1) + target
        return StackSnapshot(target, finalStack)
    }

    internal fun applyTraverse(snapshot: StackSnapshot, entries: List<NavigationEntry>): StackSnapshot {
        if (entries.isEmpty()) return snapshot
        val systemTail = snapshot.backStack.filter {
            it.renderLayer() == RenderLayer.SYSTEM && it.navigatable !is LoadingModal
        }
        val newBackStack = entries.mapIndexed { index, entry -> entry.copy(stackPosition = index + 1) } + systemTail
        return StackSnapshot(newBackStack.last(), newBackStack)
    }

    internal fun applyClearBackstack(snapshot: StackSnapshot): StackSnapshot {
        val systemTail = snapshot.backStack.filter {
            it.renderLayer() == RenderLayer.SYSTEM && it.navigatable !is LoadingModal
        }
        return StackSnapshot(
            currentEntry = systemTail.lastOrNull() ?: snapshot.currentEntry,
            backStack = systemTail
        )
    }

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
        val reAdded = if (entryToReAdd != null && trimmedBackStack.none { it.stableKey == entryToReAdd.stableKey }) {
            trimmedBackStack + entryToReAdd.copy(stackPosition = trimmedBackStack.size + 1)
        } else {
            trimmedBackStack
        }
        val systemTail = snapshot.backStack.drop(trimmedBackStack.size).filter {
            it.renderLayer() == RenderLayer.SYSTEM && it.stableKey != entryToReAdd?.stableKey
        }
        val finalBackStack = reAdded + systemTail
        if (finalBackStack.isEmpty()) return snapshot
        return StackSnapshot(
            currentEntry = finalBackStack.last(),
            backStack = finalBackStack
        )
    }
}
