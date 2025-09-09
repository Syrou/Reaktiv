# Remember Log: LaunchedEffect(Unit) Retriggering Fix

## Problem Summary
- Screens kept alive for exit animations retrigger `LaunchedEffect(Unit)` during composition
- This causes initialization effects to fire twice: once on enter, once on exit
- Root cause: Both current and previous screens recompose during navigation transitions

## Research Findings
- **Common Compose Issue**: Well-documented problem where destinations recompose on every frame during navigation animations
- **Industry Solutions**: 
  1. Custom OnResumeEffect using lifecycle observers
  2. Navigation state context providers
  3. Stable key management
  4. Navigation configuration tweaks

## Recommended Solution: Navigation Phase Context

**Core Concept**: Provide screens with context about their navigation state so they can decide when to run initialization effects.

```kotlin
/**
 * Navigation phase for screens during transitions
 */
enum class NavigationPhase {
    ENTERING,   // Screen is entering (should trigger initialization effects)
    CURRENT,    // Screen is currently active  
    EXITING     // Screen is exiting (should NOT trigger initialization effects)
}

/**
 * CompositionLocal providing navigation phase context to screens
 */
val LocalNavigationPhase = staticCompositionLocalOf<NavigationPhase> {
    NavigationPhase.CURRENT
}

/**
 * Custom effect that only triggers on true screen entry, not during exit animations
 */
@Composable
fun OnScreenEnterEffect(
    vararg keys: Any?,
    block: suspend CoroutineScope.() -> Unit
) {
    val navigationPhase = LocalNavigationPhase.current
    
    // Only trigger when actually entering, not when exiting
    if (navigationPhase == NavigationPhase.ENTERING) {
        LaunchedEffect(*keys) {
            block()
        }
    }
}
```

## Implementation Plan

1. **Add NavigationPhase enum and CompositionLocal** to ContentLayerRender.kt
2. **Wrap screen content** with proper phase context:
   ```kotlin
   // In ReaktivAnimator
   CompositionLocalProvider(
       LocalNavigationPhase provides when {
           isEntering && shouldAnimate -> NavigationPhase.ENTERING
           !isEntering && shouldAnimate -> NavigationPhase.EXITING  
           else -> NavigationPhase.CURRENT
       }
   ) {
       screenContent(navigatable, params)
   }
   ```

3. **Update screen implementations** to use `OnScreenEnterEffect` instead of `LaunchedEffect(Unit)`

## Benefits
- ✅ Prevents double initialization during exit animations
- ✅ Declarative approach - screens know their navigation state
- ✅ Backward compatible - existing LaunchedEffect calls still work
- ✅ Minimal performance impact
- ✅ Easy to adopt incrementally

## Alternative Solutions Considered
- **Content Retention**: Complex to implement, memory overhead
- **Lifecycle Observers**: Platform-specific, more complex setup
- **Stable Key Management**: Harder to guarantee correctness

## Next Steps When Implementing
1. Add the enum and CompositionLocal definitions
2. Update ReaktivAnimator to provide navigation phase context
3. Create OnScreenEnterEffect utility function
4. Test with existing screens to ensure no regressions
5. Document migration guide for screen developers

**File Location**: `reaktiv-navigation/src/commonMain/kotlin/io/github/syrou/reaktiv/navigation/ui/ContentLayerRender.kt`

**Priority**: High - affects user experience and can cause unexpected side effects

## Related Research Links
- [Fixing LaunchedEffect Triggering Before a Screen is Visible in Jetpack Compose](https://rokiran.medium.com/fixing-launchedeffect-triggering-before-a-screen-is-visible-in-jetpack-compose-dc8f4e3eeae8)
- [Jetpack Compose Navigation Is Triggering Recomposition - Stack Overflow](https://stackoverflow.com/questions/75251606/jetpack-compose-navigation-is-triggering-recomposition)
- [Navigation between iOS compose view controllers triggers LaunchedEffect - GitHub Issue](https://github.com/JetBrains/compose-multiplatform/issues/3890)

---
**Created**: 2025-01-13  
**Context**: Animation optimization work for Reaktiv navigation system  
**Status**: Solution designed, ready for implementation