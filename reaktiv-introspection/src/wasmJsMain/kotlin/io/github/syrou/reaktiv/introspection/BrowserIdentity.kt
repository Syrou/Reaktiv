package io.github.syrou.reaktiv.introspection

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class BrowserIdentity(
    val clientId: String,
    val browser: String,
    val os: String,
    val locale: String
) {
    val clientName: String
        get() = if (os.isEmpty()) browser else "$browser on $os"
}

private fun describeBrowser(win: JsAny, freshId: String): String =
    js("""
        (function(win, freshId) {
            var nav = win.navigator || {};
            var ua = String(nav.userAgent || '');
            var data = nav.userAgentData;
            var browser = null;
            if (data && data.brands && data.brands.length) {
                var names = { 'Microsoft Edge': 'Edge', 'Opera': 'Opera', 'Brave': 'Brave', 'Google Chrome': 'Chrome', 'Chromium': 'Chromium' };
                var order = ['Microsoft Edge', 'Opera', 'Brave', 'Google Chrome', 'Chromium'];
                for (var i = 0; i < order.length && !browser; i++) {
                    for (var j = 0; j < data.brands.length; j++) {
                        if (data.brands[j].brand === order[i]) {
                            browser = names[order[i]] + ' ' + data.brands[j].version;
                            break;
                        }
                    }
                }
            }
            if (!browser) {
                var match;
                if ((match = ua.match(/Edg\/(\d+)/))) browser = 'Edge ' + match[1];
                else if ((match = ua.match(/OPR\/(\d+)/))) browser = 'Opera ' + match[1];
                else if ((match = ua.match(/Firefox\/(\d+)/))) browser = 'Firefox ' + match[1];
                else if ((match = ua.match(/Chrome\/(\d+)/))) browser = 'Chrome ' + match[1];
                else if ((match = ua.match(/Version\/(\d+)[\d.]*.*Safari/))) browser = 'Safari ' + match[1];
                else browser = 'Browser';
            }
            var os = '';
            if (data && data.platform) os = String(data.platform);
            else if (/Android/.test(ua)) os = 'Android';
            else if (/iPhone|iPad|iPod/.test(ua)) os = 'iOS';
            else if (/Windows/.test(ua)) os = 'Windows';
            else if (/CrOS/.test(ua)) os = 'ChromeOS';
            else if (/Mac OS X/.test(ua)) os = 'macOS';
            else if (/Linux/.test(ua)) os = 'Linux';
            var id = null;
            try {
                var key = 'reaktiv.introspection.clientId';
                var storage = win.sessionStorage;
                var perf = win.performance;
                var entries = perf && typeof perf.getEntriesByType === 'function'
                    ? perf.getEntriesByType('navigation') : [];
                var reloaded = entries.length > 0 && entries[0].type === 'reload';
                if (storage) {
                    id = reloaded ? storage.getItem(key) : null;
                    if (!id) id = freshId;
                    storage.setItem(key, id);
                }
            } catch (ignored) {
                id = null;
            }
            return [id || freshId, browser, os, String(nav.language || '')].join('\n');
        })(win, freshId)
    """)

@OptIn(ExperimentalUuidApi::class)
internal fun browserIdentity(win: JsAny): BrowserIdentity {
    val parts = describeBrowser(win, Uuid.random().toString()).split('\n')
    return BrowserIdentity(
        clientId = parts[0],
        browser = parts[1],
        os = parts[2],
        locale = parts[3]
    )
}

public fun browserIntrospectionConfig(appVersion: String? = null): IntrospectionConfig {
    val identity = browserIdentity(PlatformContext().window)
    return IntrospectionConfig(
        clientId = identity.clientId,
        clientName = identity.clientName,
        platform = "Web",
        clientMetadata = ClientMetadata(
            appVersion = appVersion,
            osVersion = identity.os.ifEmpty { null },
            locale = identity.locale.ifEmpty { null }
        )
    )
}
