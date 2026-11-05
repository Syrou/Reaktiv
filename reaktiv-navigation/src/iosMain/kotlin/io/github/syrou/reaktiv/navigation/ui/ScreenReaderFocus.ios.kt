package io.github.syrou.reaktiv.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectEqualToRect
import platform.UIKit.UIAccessibilityElement
import platform.UIKit.UIAccessibilityFocusedElement
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityLayoutChangedNotification
import platform.UIKit.UIAccessibilityPostNotification
import platform.UIKit.UIView
import platform.UIKit.accessibilityElements
import platform.UIKit.accessibilityFrame
import platform.UIKit.isAccessibilityElement
import platform.darwin.NSObject
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal actual fun rememberScreenReaderFocus(): ScreenReaderFocus? {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosScreenReaderFocus(scope) }
}

@OptIn(ExperimentalForeignApi::class)
private class ElementTarget(
    val host: UIView,
    val frame: CValue<CGRect>
) : ScreenReaderFocusTarget

@OptIn(ExperimentalForeignApi::class)
private class IosScreenReaderFocus(
    private val scope: CoroutineScope
) : ScreenReaderFocus {

    override fun focusedNode(): ScreenReaderFocusTarget? {
        if (!UIAccessibilityIsVoiceOverRunning()) return null
        val focused = UIAccessibilityFocusedElement(null) as? NSObject ?: return null
        val host = focused.hostView() ?: return null
        return ElementTarget(host, focused.accessibilityFrame)
    }

    override fun moveTo(node: ScreenReaderFocusTarget) {
        val target = node as? ElementTarget ?: return
        scope.launch {
            repeat(RESTORE_CHECKS) {
                delay(RESTORE_INTERVAL)
                val element = find(target) ?: return@repeat
                if (UIAccessibilityFocusedElement(null) !== element) {
                    UIAccessibilityPostNotification(UIAccessibilityLayoutChangedNotification, element)
                }
                return@launch
            }
        }
    }

    private fun find(target: ElementTarget): NSObject? {
        val pending = ArrayDeque<Any?>()
        pending.add(target.host)
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_VISITED) {
            val obj = pending.removeFirst() as? NSObject ?: continue
            visited++
            if (obj !is UIView && obj.isAccessibilityElement()) {
                if (CGRectEqualToRect(obj.accessibilityFrame, target.frame)) return obj
                continue
            }
            val elements = obj.accessibilityElements
            when {
                !elements.isNullOrEmpty() -> pending.addAll(elements)
                obj is UIView -> pending.addAll(obj.subviews)
            }
        }
        return null
    }

    private fun NSObject.hostView(): UIView? {
        var current: NSObject? = this
        repeat(MAX_CONTAINER_DEPTH) {
            val node = current ?: return null
            if (node is UIView) return node
            current = (node as? UIAccessibilityElement)?.accessibilityContainer() as? NSObject
        }
        return null
    }

    private companion object {
        const val RESTORE_CHECKS = 20
        const val MAX_VISITED = 5000
        const val MAX_CONTAINER_DEPTH = 100
        val RESTORE_INTERVAL = 100.milliseconds
    }
}
