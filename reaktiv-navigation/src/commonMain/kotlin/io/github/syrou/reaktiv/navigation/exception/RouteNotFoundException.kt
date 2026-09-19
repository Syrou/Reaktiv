package io.github.syrou.reaktiv.navigation.exception

public open class RouteNotFoundException(message: String) : Exception(message)

public class PopUpToTargetNotInBackStackException(
    public val targetRoute: String,
    public val backStackPaths: List<String>
) : RouteNotFoundException(
    "popUpTo target '$targetRoute' has no entry on the back stack $backStackPaths. " +
        "The route exists in the graph. Pass a fallback to popUpTo if the entry can be absent."
)

public object ClearingBackStackWithOtherOperations : Exception(
    "You can not combine clearing backstack with replaceWith or popUpTo"
)
