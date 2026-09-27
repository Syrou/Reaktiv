package io.github.syrou.reaktiv.navigation.history

internal fun currentWindow(): JsAny? = js("(typeof window !== 'undefined') ? window : null")

internal fun isTopLevelWindow(win: JsAny): Boolean = js("""
    (function(w) {
        try {
            return w.top === w;
        } catch (e) {
            return false;
        }
    })(win)
""")

internal fun hasNavigationApi(win: JsAny): Boolean = js("""
    (function(w) {
        return !!(w.navigation && typeof w.navigation.addEventListener === 'function');
    })(win)
""")

internal fun documentBasePath(win: JsAny): String = js("""
    (function(w) {
        try {
            return new URL(w.document.baseURI).pathname;
        } catch (e) {
            return '/';
        }
    })(win)
""")

internal fun locationPart(win: JsAny, part: String): String = js("""
    (function(w, p) {
        var value = w.location[p];
        return value == null ? '' : String(value);
    })(win, part)
""")

internal fun historyStateText(win: JsAny): String? = js("""
    (function(w) {
        var state = w.history.state;
        return (typeof state === 'string') ? state : null;
    })(win)
""")

internal fun historyWrite(win: JsAny, replace: Boolean, state: String, url: String): String = js("""
    (function(w, rep, st, u) {
        try {
            var target = u.charAt(0) === '#' ? w.location.pathname + w.location.search + u : u;
            if (rep) {
                w.history.replaceState(st, '', target);
            } else {
                w.history.pushState(st, '', target);
            }
            return 'written';
        } catch (e) {
            var name = e && e.name ? e.name : '';
            if (name === 'SecurityError') return 'throttled';
            if (name === 'DataCloneError' || name === 'QuotaExceededError' || name === 'NS_ERROR_ILLEGAL_VALUE') {
                return 'too-large';
            }
            return 'failed';
        }
    })(win, replace, state, url)
""")

internal fun historyGo(win: JsAny, delta: Int): Unit = js("win.history.go(delta)")

internal fun sessionGet(win: JsAny, key: String): String? = js("""
    (function(w, k) {
        try {
            return w.sessionStorage ? w.sessionStorage.getItem(k) : null;
        } catch (e) {
            return null;
        }
    })(win, key)
""")

internal fun sessionSet(win: JsAny, key: String, value: String?): Unit = js("""
    (function(w, k, v) {
        try {
            if (!w.sessionStorage) return;
            if (v == null) {
                w.sessionStorage.removeItem(k);
            } else {
                w.sessionStorage.setItem(k, v);
            }
        } catch (e) {
        }
    })(win, key, value)
""")

internal fun useManualScrollRestoration(win: JsAny): Unit = js("""
    (function(w) {
        try {
            if ('scrollRestoration' in w.history) w.history.scrollRestoration = 'manual';
        } catch (e) {
        }
    })(win)
""")

internal fun installHistoryListeners(
    win: JsAny,
    onLanded: (Boolean) -> Unit,
    onIntent: (Int, Boolean) -> Boolean,
    onActivation: () -> Unit
): Unit = js("""
    (function(w, landed, intent, activated) {
        var lastPopHref = null;
        var pendingUa = false;
        w.addEventListener('popstate', function(e) {
            lastPopHref = w.location.href;
            var ua = (typeof e.hasUAVisualTransition === 'boolean') ? e.hasUAVisualTransition : pendingUa;
            pendingUa = false;
            landed(ua);
        });
        w.addEventListener('hashchange', function() {
            var seen = lastPopHref === w.location.href;
            lastPopHref = null;
            if (!seen) landed(false);
        });
        w.addEventListener('pointerdown', function() { activated(); }, true);
        w.addEventListener('keydown', function() { activated(); }, true);
        var nav = w.navigation;
        if (nav && typeof nav.addEventListener === 'function') {
            nav.addEventListener('navigate', function(e) {
                if (e.navigationType !== 'traverse' || !e.userInitiated) return;
                var from = nav.currentEntry ? nav.currentEntry.index : -1;
                var destination = e.destination;
                var to = destination ? destination.index : -1;
                if (from < 0 || to < 0 || !destination.sameDocument) return;
                pendingUa = !!e.hasUAVisualTransition;
                if (intent(to - from, !!e.cancelable)) e.preventDefault();
            });
        }
    })(win, onLanded, onIntent, onActivation)
""")
