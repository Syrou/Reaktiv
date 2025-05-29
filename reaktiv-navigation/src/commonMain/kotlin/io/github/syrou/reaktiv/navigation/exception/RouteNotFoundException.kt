package io.github.syrou.reaktiv.navigation.exception

class RouteNotFoundException(message: String) : Exception(message)
object ClearingBackStackWithOtherOperations : Exception(
    "You can not combine clearing backstack with replaceWith or popUpTo"
)