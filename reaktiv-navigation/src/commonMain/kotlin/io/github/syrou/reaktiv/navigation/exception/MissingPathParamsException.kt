package io.github.syrou.reaktiv.navigation.exception

public class MissingPathParamsException(
    public val path: String,
    public val missingParams: List<String>
) : IllegalArgumentException(
    "'$path' has no value for its path param${if (missingParams.size == 1) "" else "s"} " +
        "${missingParams.joinToString { "'$it'" }}, so it has no location to navigate to. " +
        "Pass every path param, for example navigateTo(screen, \"${missingParams.first()}\" to value)."
)
