package io.github.syrou.reaktiv.navigation.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeProviderCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal actual fun rememberScreenReaderFocus(): ScreenReaderFocus? {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val focus = remember(view, scope) { AndroidScreenReaderFocus(view, scope) }
    DisposableEffect(focus) {
        focus.attach()
        onDispose { focus.detach() }
    }
    return focus
}

private class VirtualViewTarget(val id: Int) : ScreenReaderFocusTarget

private class AndroidScreenReaderFocus(
    private val view: View,
    private val scope: CoroutineScope
) : ScreenReaderFocus {
    private val accessibilityManager = view.context.getSystemService(AccessibilityManager::class.java)
    private var tracking: FocusTrackingDelegate? = null

    fun attach() {
        val current = ViewCompat.getAccessibilityDelegate(view) ?: return
        if (current is FocusTrackingDelegate) {
            tracking = current
            return
        }
        val delegate = FocusTrackingDelegate(current)
        ViewCompat.setAccessibilityDelegate(view, delegate)
        tracking = delegate
    }

    fun detach() {
        val delegate = tracking ?: return
        tracking = null
        if (ViewCompat.getAccessibilityDelegate(view) === delegate) {
            ViewCompat.setAccessibilityDelegate(view, delegate.wrapped)
        }
    }

    override fun focusedNode(): ScreenReaderFocusTarget? {
        if (accessibilityManager?.isTouchExplorationEnabled != true) return null
        return tracking?.focusedNode?.let(::VirtualViewTarget)
    }

    override fun moveTo(node: ScreenReaderFocusTarget) {
        val id = (node as? VirtualViewTarget)?.id ?: return
        scope.launch {
            withFrameNanos { }
            val provider = view.accessibilityNodeProvider ?: return@launch
            provider.performAction(id, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
            provider.performAction(id, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }
}

private class FocusTrackingDelegate(val wrapped: AccessibilityDelegateCompat) : AccessibilityDelegateCompat() {
    var focusedNode: Int? = null
        private set

    private var wrappedProvider: AccessibilityNodeProviderCompat? = null
    private var trackingProvider: AccessibilityNodeProviderCompat? = null

    override fun getAccessibilityNodeProvider(host: View): AccessibilityNodeProviderCompat? {
        val provider = wrapped.getAccessibilityNodeProvider(host) ?: return null
        if (provider !== wrappedProvider) {
            wrappedProvider = provider
            trackingProvider = FocusTrackingProvider(provider)
        }
        return trackingProvider
    }

    override fun sendAccessibilityEvent(host: View, eventType: Int) =
        wrapped.sendAccessibilityEvent(host, eventType)

    override fun sendAccessibilityEventUnchecked(host: View, event: AccessibilityEvent) =
        wrapped.sendAccessibilityEventUnchecked(host, event)

    override fun dispatchPopulateAccessibilityEvent(host: View, event: AccessibilityEvent): Boolean =
        wrapped.dispatchPopulateAccessibilityEvent(host, event)

    override fun onPopulateAccessibilityEvent(host: View, event: AccessibilityEvent) =
        wrapped.onPopulateAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) =
        wrapped.onInitializeAccessibilityEvent(host, event)

    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) =
        wrapped.onInitializeAccessibilityNodeInfo(host, info)

    override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean =
        wrapped.onRequestSendAccessibilityEvent(host, child, event)

    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
        wrapped.performAccessibilityAction(host, action, args)

    private inner class FocusTrackingProvider(
        private val provider: AccessibilityNodeProviderCompat
    ) : AccessibilityNodeProviderCompat() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfoCompat? =
            provider.createAccessibilityNodeInfo(virtualViewId)

        override fun findAccessibilityNodeInfosByText(
            text: String,
            virtualViewId: Int
        ): MutableList<AccessibilityNodeInfoCompat>? =
            provider.findAccessibilityNodeInfosByText(text, virtualViewId)

        override fun findFocus(focus: Int): AccessibilityNodeInfoCompat? = provider.findFocus(focus)

        override fun addExtraDataToAccessibilityNodeInfo(
            virtualViewId: Int,
            info: AccessibilityNodeInfoCompat,
            extraDataKey: String,
            arguments: Bundle?
        ) = provider.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            val performed = provider.performAction(virtualViewId, action, arguments)
            if (performed && action == AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS) {
                focusedNode = virtualViewId
            }
            if (action == AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS && focusedNode == virtualViewId) {
                focusedNode = null
            }
            return performed
        }
    }
}
