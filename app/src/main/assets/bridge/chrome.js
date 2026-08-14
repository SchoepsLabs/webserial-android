/*
 * Reports scroll direction to the app so the address bar can hide as you read.
 *
 * The WebView's own scrollY is useless for this: Betaflight, the blackbox
 * explorer and ESC Configurator all scroll an inner element, so the WebView
 * never scrolls and neither AppBarLayout's scroll flags nor a View scroll
 * listener ever fire.
 *
 * This channel is deliberately separate from the USB bridge and is injected on
 * every origin. All it can do is ask the app to show or hide its own toolbar.
 */
(function (global) {
    "use strict";

    var CHANNEL = "AndroidBrowserChrome";
    var SLOP = 12;

    if (global.__configuratorChromeInstalled) {
        return;
    }
    global.__configuratorChromeInstalled = true;

    var lastOffsets = new WeakMap();
    var lastWindowOffset = 0;

    function post(message) {
        var channel = global[CHANNEL];
        if (channel && typeof channel.postMessage === "function") {
            try {
                channel.postMessage(JSON.stringify(message));
            } catch (e) {
                /* the app is not listening; nothing to do */
            }
        }
    }

    function offsetOf(target) {
        if (!target || target === global.document || target === global) {
            return global.scrollY || (global.document.documentElement || {}).scrollTop || 0;
        }
        return target.scrollTop || 0;
    }

    function previousOffsetOf(target, current) {
        if (!target || target === global.document || target === global) {
            var previous = lastWindowOffset;
            lastWindowOffset = current;
            return previous;
        }
        var stored = lastOffsets.get(target);
        lastOffsets.set(target, current);
        return stored === undefined ? current : stored;
    }

    function onScroll(event) {
        var target = event.target;
        var current = offsetOf(target);
        var previous = previousOffsetOf(target, current);
        var delta = current - previous;

        if (current <= SLOP) {
            post({ chrome: "expand" });
            return;
        }
        if (delta > SLOP) {
            post({ chrome: "collapse" });
        } else if (delta < -SLOP) {
            post({ chrome: "expand" });
        }
    }

    // Capture phase: scroll does not bubble, so this is the only way to see a
    // scroll that happens inside a nested container.
    global.document.addEventListener("scroll", onScroll, true);
    global.addEventListener("scroll", onScroll, { passive: true });
})(typeof globalThis !== "undefined" ? globalThis : this);
