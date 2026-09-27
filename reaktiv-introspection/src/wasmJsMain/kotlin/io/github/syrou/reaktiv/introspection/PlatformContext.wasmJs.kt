package io.github.syrou.reaktiv.introspection

public actual class PlatformContext internal constructor(internal val window: JsAny) {
    public constructor() : this(hostGlobal())
}

private fun hostGlobal(): JsAny = js("globalThis")
