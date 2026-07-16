package io.github.syrou.reaktiv.navigation.exception

public class RouteNotFoundException(message: String) : Exception(message)
public object ClearingBackStackWithOtherOperations : Exception(
    "You can not combine clearing backstack with replaceWith or popUpTo"
)
