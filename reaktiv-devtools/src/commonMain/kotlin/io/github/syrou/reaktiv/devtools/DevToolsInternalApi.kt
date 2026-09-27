package io.github.syrou.reaktiv.devtools

@RequiresOptIn(
    message = "Part of the DevTools wire protocol, client or analysis internals. It becomes internal in the next release.",
    level = RequiresOptIn.Level.WARNING
)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS
)
public annotation class DevToolsInternalApi
