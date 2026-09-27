package io.github.syrou.reaktiv.devtools.client

internal fun readServerUrl(win: JsAny, parameter: String): String? =
    js("""
        (function(win, parameter) {
            var key = 'reaktiv.devtools.serverUrl';
            var storage = null;
            try {
                storage = win.sessionStorage || null;
            } catch (ignored) {
                storage = null;
            }
            var location = win.location;
            var requested = null;
            if (location && typeof location.search === 'string' && typeof URLSearchParams === 'function') {
                requested = new URLSearchParams(location.search).get(parameter);
            }
            if (requested === null) {
                try {
                    return storage ? storage.getItem(key) : null;
                } catch (ignored) {
                    return null;
                }
            }
            var value = requested.trim();
            if (value.length > 0 && value.indexOf('://') < 0) {
                var secure = location && location.protocol === 'https:';
                value = (secure ? 'wss://' : 'ws://') + value;
            }
            if (value.length > 0 && !/^[a-z]+:\/\/[^\/]+\/./i.test(value)) {
                value = value.replace(/\/?$/, '/ws');
            }
            try {
                if (storage) {
                    if (value.length > 0) storage.setItem(key, value);
                    else storage.removeItem(key);
                }
            } catch (ignored) {
            }
            return value.length > 0 ? value : null;
        })(win, parameter)
    """)

private fun hostWindow(): JsAny = js("globalThis")

public fun devToolsServerUrlFromPage(parameter: String = "devtools"): String? =
    readServerUrl(hostWindow(), parameter)
