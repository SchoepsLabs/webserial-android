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

    /*
     * Stop a slider drag from turning into a text selection.
     *
     * ESC Configurator's sliders are react-input-range, which is built from divs
     * and spans rather than <input type="range">. Those spans hold the min, max
     * and current-value labels, so a touch that lands on the widget looks to the
     * WebView like a long-press on text: it starts a selection and the drag
     * never reaches the slider. On a phone that is most touches, which makes the
     * settings sliders close to unusable.
     *
     * Scoped to slider widgets only. Disabling selection page-wide would break
     * copying a CLI dump, which is the other thing people do here.
     */
    var SLIDER_CSS = [
        ".input-range, .input-range *,",
        "[role=\"slider\"],",
        ".noUi-target, .noUi-target *,",
        ".MuiSlider-root, .MuiSlider-root *,",
        ".rc-slider, .rc-slider *,",
        "input[type=\"range\"] {",
        "  -webkit-user-select: none;",
        "  user-select: none;",
        "  -webkit-touch-callout: none;",
        "}",
        /*
         * pan-y on the track keeps the page scrollable when a finger lands on a
         * slider — an ESC settings page is a tall stack of them — while still
         * handing horizontal movement to the widget. The thumb takes none, so
         * grabbing it is always a drag.
         */
        ".input-range, .noUi-target, .MuiSlider-root, .rc-slider, input[type=\"range\"] {",
        "  touch-action: pan-y;",
        "}",
        ".input-range__slider, .input-range__slider-container,",
        ".noUi-handle, .MuiSlider-thumb, .rc-slider-handle, [role=\"slider\"] {",
        "  touch-action: none;",
        "}",
    ].join("\n");

    function installSliderCss() {
        var doc = global.document;
        var root = doc.head || doc.documentElement;
        if (!root || doc.getElementById("__configurator_slider_css")) {
            return;
        }
        var style = doc.createElement("style");
        style.id = "__configurator_slider_css";
        style.textContent = SLIDER_CSS;
        root.appendChild(style);
    }

    installSliderCss();
    // At document start there is a documentElement but often no head yet, and a
    // framework that rewrites <head> can drop the tag; re-assert once the real
    // document exists.
    global.document.addEventListener("DOMContentLoaded", installSliderCss);

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
