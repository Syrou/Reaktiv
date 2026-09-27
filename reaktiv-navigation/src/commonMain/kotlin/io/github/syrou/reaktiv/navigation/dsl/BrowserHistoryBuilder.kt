package io.github.syrou.reaktiv.navigation.dsl

import io.github.syrou.reaktiv.navigation.history.BrowserHistoryMode
import io.github.syrou.reaktiv.navigation.history.UrlStyle

public class BrowserHistoryBuilder internal constructor() {
    public var urlStyle: UrlStyle = UrlStyle.Path
    public var basePath: String? = null
    public var mode: BrowserHistoryMode = BrowserHistoryMode.TopLevelOnly
    public var documentTitle: (String?) -> String? = { it }
}
