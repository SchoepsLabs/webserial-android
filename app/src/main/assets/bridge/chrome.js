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
    var ARMED_POLL_MS = 1000;

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
    /*
     * The label sitting next to a slider counts as part of the slider.
     *
     * ESC Configurator renders the name and value outside .input-range — a
     * sibling inside the wrapping <label> — so suppressing selection on the
     * widget alone left "Master speed" and "Motor 1" grabbable, and a touch
     * that starts there still becomes a selection instead of a drag. Betaflight
     * puts its motor labels in the <li> around each slider for the same reason.
     *
     * Anchored on containing a slider, so this can never reach ordinary prose.
     */
    /*
     * A scroll lock the app can switch on while motors can spin.
     *
     * Betaflight already swallows wheel events over its motor test when testing
     * is armed — "so the page cannot scroll out from under the pointer while the
     * motors can spin" — but there is no touch equivalent, and on a phone touch
     * is all there is.
     *
     * Armed state cannot be detected from out here without guessing: ESC
     * Configurator's settings page also pairs checkboxes with sliders, so every
     * heuristic that caught the motor panel also killed scrolling on a page
     * where scrolling is all you do. So the app asks, and the user decides.
     */
    var SCROLL_LOCK_CSS = [
        "html.__configurator_scroll_lock, html.__configurator_scroll_lock body {",
        "  touch-action: none;",
        "  overscroll-behavior: none;",
        "}",
        "html.__configurator_scroll_lock * { touch-action: none; }",
        // The sliders themselves already take the whole gesture, so they keep
        // working; this only stops the page moving underneath them.
    ].join("\n");

    var SLIDER_ROW_CSS = [
        "label:has(.input-range), label:has([role=\"slider\"]),",
        "li:has([role=\"slider\"]), .number:has(.input-range),",
        "li:has(.input-range),",
        /*
         * The wrapper that holds a slider as a direct child, whatever the
         * library. ESC Configurator's motor control is rc-slider inside
         * <div class="slider"><h3>Motor 1</h3><slider/></div>, and the master is
         * the same shape — the heading is a sibling of the widget, so covering
         * the widget alone left "Master Speed" and "Motor 2" grabbable. With
         * motor output armed, a stray selection there is not a cosmetic problem.
         */
        ":has(> .rc-slider), :has(> .input-range), :has(> .noUi-target),",
        ":has(> .MuiSlider-root), :has(> [role=\"slider\"]), :has(> input[type=\"range\"]) {",
        "  -webkit-user-select: none;",
        "  user-select: none;",
        "  -webkit-touch-callout: none;",
        "}",
    ].join("\n");

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
        style.textContent = [SLIDER_CSS, RADIX_SLIDER_CSS, SLIDER_ROW_CSS, SCROLL_LOCK_CSS].join("\n");
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
    /*
     * Ending a slider drag the browser interrupted.
     *
     * Both slider libraries in use here end a drag only on the *normal* end
     * event, and neither listens for the cancel the browser fires when it takes
     * a gesture over for a pan:
     *
     *   react-input-range (ESC Configurator) adds document touchmove/touchend on
     *   touchstart and removes them on touchend only, so a cancelled drag leaves
     *   that listener attached for good.
     *
     *   reka-ui (Betaflight motors) pairs setPointerCapture on pointerdown with
     *   releasePointerCapture on pointerup only, so a cancelled drag leaves the
     *   slider believing it is still being dragged.
     *
     * Either way the slider keeps following later drags, which is why moving one
     * motor moved another. Synthesising the end event they are waiting for
     * restores the invariant. Scoped to slider widgets so no other page code
     * sees a duplicate end.
     */
    function endInterruptedDrag(event) {
        var target = event.target;
        if (!target || !target.closest || !target.closest(SLIDER_SELECTOR)) {
            return;
        }
        try {
            if (event.type === "pointercancel" && global.PointerEvent) {
                target.dispatchEvent(new global.PointerEvent("pointerup", {
                    bubbles: true,
                    cancelable: false,
                    pointerId: event.pointerId,
                    pointerType: event.pointerType,
                    isPrimary: event.isPrimary,
                    clientX: event.clientX,
                    clientY: event.clientY,
                }));
            } else if (event.type === "touchcancel" && global.TouchEvent) {
                target.dispatchEvent(new global.TouchEvent("touchend", {
                    bubbles: true,
                    cancelable: false,
                    touches: [],
                    targetTouches: [],
                    changedTouches: [],
                }));
            }
        } catch (e) {
            /* a browser that will not let us build one; nothing else to try */
        }
    }

    global.document.addEventListener("pointercancel", endInterruptedDrag, true);
    global.document.addEventListener("touchcancel", endInterruptedDrag, true);

    /*
     * How close to a screen edge counts as an edge gesture, in CSS pixels. Used
     * both for the exclusion rectangles and for refusing edge drags while
     * motors are armed. Android's own back-gesture zone is around 20dp; this is
     * wider because a swipe that starts just inside it still ends up scrolling.
     */
    var EDGE_BAND = 56;
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

    /*
     * Noticing when motors can actually spin.
     *
     * Two signals, both structural rather than guessed from wording:
     *
     *   ESC Configurator arms with a checkbox that carries a stable name,
     *   input[name="enable-motor-control"], unaffected by the interface
     *   language.
     *
     *   Betaflight has no such handle — its switch is an anonymous USwitch among
     *   many — but its motor sliders are the only *vertical* sliders on the page
     *   and reka-ui marks them data-disabled until testing is armed. A vertical
     *   slider that is not disabled therefore means the motors are live. ESC
     *   Configurator's settings sliders are horizontal, so they cannot trip it.
     *
     * If either upstream changes shape this stops detecting rather than
     * misfiring, and the manual lock in the menu still works.
     */
    function motorsArmed() {
        var doc = global.document;
        try {
            if (doc.querySelector('input[name="enable-motor-control"]:checked')) return true;
            if (doc.querySelector('[role="slider"][aria-orientation="vertical"]:not([data-disabled])')) return true;
        } catch (e) {
            /* a selector this engine will not parse; treat as not armed */
        }
        return false;
    }

    /*
     * Stops the motors by pressing the page's own control.
     *
     * Its own control, not our idea of one: whatever the site does to stop —
     * the MSP writes, the state cleanup — happens exactly as it would if the
     * user had reached the button themselves. Betaflight's stopMotors() only
     * sets motorsTestingEnabled to false, which is what its toolbar button and
     * its arming switch both do.
     *
     * Needed because the lock that keeps the page still can also put that
     * button off-screen, and "you cannot scroll to the stop button" is a worse
     * problem than the one the lock solves.
     *
     * @return which control was pressed, or "not-found".
     */
    global.__configuratorStopMotors = function () {
        var doc = global.document;
        var escBox = doc.querySelector('input[name="enable-motor-control"]:checked');
        if (escBox) {
            escBox.click();
            return "esc";
        }
        // Betaflight's bottom toolbar: the error-coloured button, live only
        // while motor testing is on.
        var stop = doc.querySelector('.toolbar_fixed_bottom button[class*="error"]:not([disabled])');
        if (stop) {
            stop.click();
            return "toolbar";
        }
        // Failing that, the switch nearest the live sliders — the arming one.
        var slider = doc.querySelector('[role="slider"][aria-orientation="vertical"]:not([data-disabled])');
        var node = slider ? slider.parentElement : null;
        for (var depth = 0; node && depth < 8; depth++) {
            var found = node.querySelector('[role="switch"][aria-checked="true"]');
            if (found) {
                found.click();
                return "switch";
            }
            node = node.parentElement;
        }
        return "not-found";
    };

    /*
     * A drag that starts at a screen edge does nothing while motors are armed.
     *
     * Taking the back gesture away was only half of it: Android stopped
     * navigating, and the page scrolled instead, which moves the sliders under
     * the finger exactly as leaving the page would have. Both are the same
     * failure — the screen shifting mid-adjustment.
     *
     * Only for gestures that *begin* in the edge band, so the rest of the page
     * still scrolls and the lowest motor can still be brought out from under
     * the configurator's own toolbar. The slider's own handlers see every one
     * of these events; only the browser's scrolling is refused.
     */
    var edgeGesture = false;

    function onEdgeTouchStart(event) {
        var touch = event.touches && event.touches[0];
        var width = global.innerWidth || 0;
        edgeGesture = !!(lastArmed && touch &&
            (touch.clientX <= EDGE_BAND || touch.clientX >= width - EDGE_BAND));
        /*
         * Refused at touchstart, not just at touchmove. Cancelling the move is
         * unreliable: once the browser has decided a gesture is a scroll it
         * marks the later moves non-cancelable, and on a page that scrolls an
         * inner element — which Betaflight's motor tab does — that decision can
         * be made before the first move arrives. Refusing the start settles it
         * for the whole gesture.
         *
         * This suppresses the compatibility mouse events for that touch, not
         * pointer events, so a slider reaching the edge still tracks normally.
         */
        if (edgeGesture && event.cancelable) {
            event.preventDefault();
        }
    }

    function onEdgeTouchMove(event) {
        if (edgeGesture && event.cancelable) {
            event.preventDefault();
        }
    }

    // Neither is passive: refusing the scroll is the entire point.
    global.document.addEventListener("touchstart", onEdgeTouchStart, { passive: false, capture: true });
    global.document.addEventListener("touchmove", onEdgeTouchMove, { passive: false, capture: true });

    var lastArmed = false;
    function checkArmed() {
        var armed = motorsArmed();
        if (armed === lastArmed) return;
        lastArmed = armed;
        post({ chrome: "armed", armed: armed });
    }
    global.setInterval(checkArmed, ARMED_POLL_MS);

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

    /** Called by the app when the scroll lock is switched on or off. */
    global.__configuratorSetScrollLock = function (locked) {
        var root = global.document.documentElement;
        if (!root) return false;
        if (locked) {
            root.classList.add("__configurator_scroll_lock");
        } else {
            root.classList.remove("__configurator_scroll_lock");
        }
        return locked;
    };

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
