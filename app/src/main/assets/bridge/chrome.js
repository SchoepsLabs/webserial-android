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
        ".input-range, .noUi-target, .MuiSlider-root, .rc-slider {",
        "  touch-action: pan-y;",
        "}",
        ".input-range__slider, .input-range__slider-container,",
        ".noUi-handle, .MuiSlider-thumb, .rc-slider-handle, [role=\"slider\"] {",
        "  touch-action: none;",
        "}",
    ].join("\n");

    /*
     * Radix-style sliders, which is what Betaflight's motor tab is.
     *
     * Nuxt UI's USlider wraps reka-ui, and reka-ui sets role="slider" and
     * data-orientation but no touch-action of its own — unlike Radix's own React
     * build, which pins touch-action: none inline. Without it the browser may
     * read a drag as a page pan, so a motor slider moves sometimes and scrolls
     * the page the rest of the time.
     *
     * Those motor sliders are *vertical*, and a vertical slider cannot share the
     * vertical axis with page scrolling — it has to take the whole gesture. A
     * horizontal one only needs the horizontal axis, so the page still scrolls
     * through it.
     *
     * touch-action is not inherited, but the browser intersects the values along
     * the ancestor chain, so setting it on the root also covers the track, the
     * range and the thumb inside it.
     *
     * Kept in rules of their own: :has() landed in Chrome 105, and an unknown
     * selector invalidates the whole rule it appears in — sharing a rule with
     * the plain selectors above would take those down on an older WebView.
     */
    var RADIX_SLIDER_CSS = [
        "[data-orientation=\"horizontal\"]:has([role=\"slider\"]) { touch-action: pan-y; }",
        "[data-orientation=\"vertical\"]:has([role=\"slider\"]) { touch-action: none; }",
        "[role=\"slider\"][aria-orientation=\"vertical\"] { touch-action: none; }",
        "[data-orientation]:has([role=\"slider\"]) * {",
        "  -webkit-user-select: none;",
        "  user-select: none;",
        "  -webkit-touch-callout: none;",
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
        style.textContent = SLIDER_CSS + "\n" + RADIX_SLIDER_CSS;
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
        gestureScrolled = true;
        scheduleEdgeReport();
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

    /*
     * Bringing the bar back when nothing scrolls.
     *
     * expand is only ever posted from a scroll event. On a page that does not
     * scroll, or once the user is already at the top, no scroll event ever
     * arrives and the bar can never come back — the app has to be killed.
     * Dragging down from the top edge only pulls the phone's notification shade.
     *
     * So a downward drag that scrolls nothing is taken to mean "show me the
     * bar". Gestures starting on a slider are ignored, or dragging a motor
     * slider downwards would pop the toolbar open every time.
     */
    /*
     * Keeping Android's back gesture off the sliders that sit near a screen edge.
     *
     * ESC Configurator's round slider knobs reach close to the left edge, so a
     * drag on one is also an edge swipe: the system takes it as Back and the
     * page navigates away mid-adjustment. Android's answer is
     * View.setSystemGestureExclusionRects, so the app is told where the sliders
     * are and excludes exactly those bands.
     *
     * Only what is on screen and actually near an edge is reported — the system
     * caps exclusions at 200dp per edge, so asking for the whole page would get
     * most of it thrown away.
     */
    var EDGE_BAND = 40;
    var MAX_RECTS = 10;
    var lastExclusions = "";

    function reportEdgeSliders() {
        var width = global.innerWidth || 0;
        var height = global.innerHeight || 0;
        var found = [];
        var nodes = global.document.querySelectorAll(SLIDER_SELECTOR);
        for (var i = 0; i < nodes.length && found.length < MAX_RECTS; i++) {
            var r = nodes[i].getBoundingClientRect();
            if (!r.width || !r.height) continue;
            if (r.bottom < 0 || r.top > height) continue;
            var nearLeft = r.left <= EDGE_BAND;
            var nearRight = r.right >= width - EDGE_BAND;
            if (!nearLeft && !nearRight) continue;
            found.push([
                Math.max(0, Math.round(nearLeft ? 0 : r.left)),
                Math.max(0, Math.round(r.top)),
                Math.min(width, Math.round(nearRight ? width : r.right)),
                Math.min(height, Math.round(r.bottom)),
            ]);
        }
        var encoded = JSON.stringify(found);
        if (encoded === lastExclusions) return;
        lastExclusions = encoded;
        post({ chrome: "exclude", rects: found });
    }

    var reportPending = false;
    function scheduleEdgeReport() {
        if (reportPending) return;
        reportPending = true;
        (global.requestAnimationFrame || function (fn) { global.setTimeout(fn, 16); })(function () {
            reportPending = false;
            try { reportEdgeSliders(); } catch (e) { /* a page mid-teardown */ }
        });
    }

    var REVEAL_DISTANCE = 48;
    var SLIDER_SELECTOR = '[role="slider"], .input-range, .noUi-target, .MuiSlider-root, .rc-slider, input[type="range"]';
    var gestureStartY = null;
    var gestureScrolled = false;
    var gestureRevealed = false;

    function startsOnSlider(target) {
        return !!(target && target.closest && target.closest(SLIDER_SELECTOR));
    }

    function onTouchStart(event) {
        var touch = event.touches && event.touches[0];
        gestureStartY = touch && !startsOnSlider(event.target) ? touch.clientY : null;
        gestureScrolled = false;
        gestureRevealed = false;
    }

    function onTouchMove(event) {
        if (gestureStartY === null || gestureScrolled || gestureRevealed) {
            return;
        }
        var touch = event.touches && event.touches[0];
        if (touch && touch.clientY - gestureStartY > REVEAL_DISTANCE) {
            gestureRevealed = true;
            post({ chrome: "expand" });
        }
    }

    global.addEventListener("resize", scheduleEdgeReport, { passive: true });
    global.document.addEventListener("DOMContentLoaded", scheduleEdgeReport);
    global.setTimeout(scheduleEdgeReport, 1500);

    global.document.addEventListener("touchstart", onTouchStart, { passive: true, capture: true });
    global.document.addEventListener("touchmove", onTouchMove, { passive: true, capture: true });

    // Capture phase: scroll does not bubble, so this is the only way to see a
    // scroll that happens inside a nested container.
    global.document.addEventListener("scroll", onScroll, true);
    global.addEventListener("scroll", onScroll, { passive: true });
})(typeof globalThis !== "undefined" ? globalThis : this);
