# BatchUpdate Operations Example

The `BatchUpdate` action now includes an `operations` list that tracks which navigation operations were performed. This is useful for middleware, analytics, and debugging.

## Action Dispatching Behavior

### Single Operations
When a single navigation operation is performed, a specific action is dispatched:
- `store.navigation { navigateTo("profile") }` → Dispatches `NavigationAction.Navigate`
- `store.navigation { popUpTo("home") }` → Dispatches `NavigationAction.PopUpTo`  
- `store.navigation { navigateBack() }` → Dispatches `NavigationAction.Back`
- `store.navigation { clearBackStack() }` → Dispatches `NavigationAction.ClearBackstack`

### Multiple Operations
When multiple operations are performed together, `BatchUpdate` is dispatched with the operations list:
```kotlin
store.navigation {
    popUpTo("home")
    navigateTo("profile")
}
```
This dispatches:
```kotlin
NavigationAction.BatchUpdate(
    currentEntry = finalEntry,
    backStack = finalBackStack,
    modalContexts = modalContexts,
    operations = listOf(NavigationOperation.PopUpTo, NavigationOperation.Navigate)
)
```

## Use Cases

### Middleware Filtering
```kotlin
class NavigationAnalyticsMiddleware : Middleware<NavigationState, NavigationAction> {
    override suspend fun process(
        state: NavigationState,
        action: NavigationAction,
        next: suspend (NavigationAction) -> NavigationState
    ): NavigationState {
        when (action) {
            is NavigationAction.BatchUpdate -> {
                // Track complex navigation patterns
                if (NavigationOperation.PopUpTo in action.operations &&
                    NavigationOperation.Navigate in action.operations) {
                    analytics.track("popUpTo_with_navigate", mapOf(
                        "operations" to action.operations.map { it.name }
                    ))
                }
            }
            is NavigationAction.PopUpTo -> {
                analytics.track("standalone_popUpTo")
            }
        }
        return next(action)
    }
}
```

### Debugging
```kotlin
// Log all operations performed in a batch
when (action) {
    is NavigationAction.BatchUpdate -> {
        if (action.operations.size > 1) {
            println("Batch navigation: ${action.operations.joinToString(" + ")}")
        }
    }
}
```

### Performance Monitoring
```kotlin
// Track complex navigation patterns that might be expensive
val complexOperations = listOf(NavigationOperation.PopUpTo, NavigationOperation.ClearBackStack)
if (action is NavigationAction.BatchUpdate && 
    action.operations.any { it in complexOperations }) {
    performanceMonitor.startTracking("complex_navigation")
}
```

This enhancement maintains full backward compatibility while providing rich metadata for advanced navigation handling.