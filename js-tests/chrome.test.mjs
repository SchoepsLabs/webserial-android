import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CHROME_PATH = path.resolve(HERE, "../app/src/main/assets/bridge/chrome.js");

/**
 * The toolbar/scroll script, which also carries the slider fix.
 *
 * ESC Configurator's sliders are react-input-range: divs and spans, not
 * <input type="range">. The spans hold the min, max and value labels, so a
 * touch reads as a long-press on text and starts a selection instead of a
 * drag. These tests run the file the APK actually injects.
 */

/** A document mock with just enough surface for the script to install itself. */
function loadChrome({ head = true, sliders = [] } = {}) {
    const source = fs.readFileSync(CHROME_PATH, "utf8");
    const appended = [];
    const listeners = new Map();
    const posted = [];
    const byId = new Map();

    const container = () => ({
        appendChild(node) {
            appended.push(node);
            if (node.id) byId.set(node.id, node);
        },
    });

    const document = {
        documentElement: container(),
        head: head ? container() : null,
        createElement: () => ({ id: "", textContent: "" }),
        getElementById: (id) => byId.get(id) ?? null,
        // The edge-slider report walks these looking for ones near a screen edge.
        querySelectorAll: () => sliders,
        addEventListener: (type, handler) => {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(handler);
        },
    };

    const channel = { postMessage: (text) => posted.push(JSON.parse(text)) };
    const timers = [];
    class FakeEvent {
        constructor(type, init = {}) { Object.assign(this, init); this.type = type; }
    }
    const sandbox = {
        PointerEvent: FakeEvent,
        TouchEvent: FakeEvent,
        document,
        addEventListener() {},
        WeakMap,
        JSON,
        Math,
        innerWidth: 400,
        innerHeight: 800,
        // Run frame callbacks straight away so a test sees the report it triggered.
        requestAnimationFrame: (fn) => fn(),
        setTimeout: (fn) => { timers.push(fn); return timers.length; },
        AndroidBrowserChrome: channel,
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);

    return {
        sandbox,
        appended,
        style: () => appended.find((node) => node.id === "__configurator_slider_css"),
        fire: (type, event) => (listeners.get(type) ?? []).forEach((handler) => handler(event)),
        runTimers: () => timers.splice(0).forEach((fn) => fn()),
        posted,
    };
}

test("a stylesheet is injected that suppresses selection on slider widgets", () => {
    const css = loadChrome().style();
    assert.ok(css, "no stylesheet was appended");
    assert.match(css.textContent, /user-select:\s*none/);
    assert.match(css.textContent, /-webkit-touch-callout:\s*none/);
});

test("react-input-range is covered, which is what ESC Configurator renders", () => {
    // Not <input type=range>: the labels are spans, so selection wins the touch.
    const text = loadChrome().style().textContent;
    assert.match(text, /\.input-range, \.input-range \*/);
    assert.match(text, /\.input-range__slider/);
});

test("the page still scrolls when a finger lands on a slider", () => {
    /*
     * An ESC settings page is a tall stack of sliders. touch-action: none on the
     * track would make the page unscrollable there, so the track takes pan-y and
     * only the thumb takes none.
     */
    const text = loadChrome().style().textContent;
    assert.match(text, /\.input-range,[^{]*\{\s*touch-action: pan-y/);
    assert.match(text, /\.input-range__slider[^{]*\{[^}]*touch-action: none/s);
});

test("selection is not disabled page-wide, so a CLI dump stays copyable", () => {
    /*
     * Every selector has to be anchored on a slider. A bare *, body, html or
     * :root would kill selection across the whole page and take copying a CLI
     * dump with it. Descendant rules like ".input-range *" are fine — they are
     * still rooted in a slider.
     */
    const text = loadChrome().style().textContent;
    const selectors = text
        .split("}")
        .map((rule) => rule.slice(rule.lastIndexOf("{") === -1 ? 0 : 0, rule.indexOf("{")))
        .filter((part) => part.trim())
        .flatMap((part) => part.split(","))
        .map((part) => part.trim())
        .filter(Boolean);

    assert.ok(selectors.length > 0, "no selectors were parsed");
    for (const selector of selectors) {
        assert.ok(
            !["*", "body", "html", ":root", "div", "span"].includes(selector),
            `unanchored selector: ${selector}`,
        );
    }
});

test("the style survives a document that has no head yet at document start", () => {
    // addDocumentStartJavaScript runs before <head> exists on some pages.
    assert.ok(loadChrome({ head: false }).style(), "nothing was appended to documentElement");
});

test("re-asserting on DOMContentLoaded does not append a second copy", () => {
    // A framework that rewrites <head> can drop the tag, so it is re-applied —
    // but only when it is actually gone.
    const chrome = loadChrome();
    chrome.fire("DOMContentLoaded");
    const styles = chrome.appended.filter((node) => node.id === "__configurator_slider_css");
    assert.equal(styles.length, 1);
});

test("a vertical Radix slider takes the whole gesture, not a page pan", () => {
    /*
     * Betaflight's motor tab is Nuxt UI's USlider (reka-ui) with
     * orientation="vertical". reka-ui sets role="slider" and data-orientation
     * but no touch-action, so the browser was free to read a drag down the
     * slider as a page scroll — it moved sometimes and scrolled the rest.
     *
     * A vertical slider cannot share the vertical axis with the page, so it has
     * to be none, not pan-y.
     */
    const text = loadChrome().style().textContent;
    assert.match(text, /\[data-orientation="vertical"\]:has\(\[role="slider"\]\) \{ touch-action: none; \}/);
    assert.match(text, /\[data-orientation="horizontal"\]:has\(\[role="slider"\]\) \{ touch-action: pan-y; \}/);
});

test("native range inputs keep the browser's own touch handling", () => {
    /*
     * touch-action: pan-y here would break a vertical <input type=range> the
     * same way it broke the motor sliders, and Chromium already drags a native
     * range correctly while letting the page scroll past it. Selection is still
     * suppressed; only the touch-action is withheld.
     */
    const text = loadChrome().style().textContent;
    const panYRule = text.slice(0, text.indexOf("touch-action: pan-y"));
    const selector = panYRule.slice(panYRule.lastIndexOf("}") + 1);
    assert.ok(!selector.includes('input[type="range"]'), "a native range was given a touch-action");
    assert.ok(text.includes('input[type="range"]'), "a native range lost its selection rule");
});

test("the :has() rules stand alone so an old WebView cannot drop the plain ones", () => {
    /*
     * An unknown selector invalidates the whole rule it appears in, and :has()
     * only landed in Chrome 105. A rule may therefore be all-:has() — it simply
     * does nothing on an older WebView — but it must never mix the two, or the
     * plain react-input-range selectors would go down with it.
     */
    const text = loadChrome().style().textContent;
    for (const line of text.split("\n")) {
        const selectors = line.split("{")[0];
        if (!selectors.includes(":has(")) continue;
        for (const part of selectors.split(/,(?![^()]*\))/)) {
            const selector = part.trim();
            if (!selector) continue;
            assert.ok(
                selector.includes(":has("),
                `a plain selector shares a rule with :has(): ${selector}`,
            );
        }
    }
});

/** A touch event shaped the way the reveal handler reads it. */
function touch(y, target = { closest: () => null }) {
    return { touches: [{ clientY: y }], target };
}

test("a downward drag that scrolls nothing brings the address bar back", () => {
    /*
     * expand used to be posted only from a scroll event, so on a page that does
     * not scroll — or once you are already at the top — the bar could never come
     * back and the app had to be killed. Dragging down from the top edge just
     * pulls the phone's notification shade instead.
     */
    const chrome = loadChrome();
    chrome.fire("touchstart", touch(300));
    chrome.fire("touchmove", touch(400));

    assert.deepEqual(chrome.posted, [{ chrome: "expand" }]);
});

test("a drag shorter than the threshold is not treated as a reveal", () => {
    const chrome = loadChrome();
    chrome.fire("touchstart", touch(300));
    chrome.fire("touchmove", touch(320));

    assert.deepEqual(chrome.posted, []);
});

test("a drag that does scroll is left to the scroll handler", () => {
    // Otherwise every downward scroll would fight the collapse logic.
    const chrome = loadChrome();
    chrome.fire("touchstart", touch(300));
    chrome.fire("scroll", { target: { scrollTop: 500 } });
    chrome.posted.length = 0;
    chrome.fire("touchmove", touch(400));

    assert.deepEqual(chrome.posted, []);
});

test("dragging a slider downwards does not pop the toolbar open", () => {
    // A motor slider is dragged down constantly; the bar must stay put.
    const chrome = loadChrome();
    const onSlider = { closest: (selector) => (selector.includes("slider") ? {} : null) };
    chrome.fire("touchstart", touch(300, onSlider));
    chrome.fire("touchmove", touch(500, onSlider));

    assert.deepEqual(chrome.posted, []);
});

test("one reveal per gesture, not one per touchmove", () => {
    const chrome = loadChrome();
    chrome.fire("touchstart", touch(300));
    chrome.fire("touchmove", touch(400));
    chrome.fire("touchmove", touch(500));
    chrome.fire("touchmove", touch(600));

    assert.equal(chrome.posted.length, 1);
});

/** A slider element positioned by its viewport rectangle. */
function sliderAt(left, top, right, bottom) {
    return { getBoundingClientRect: () => ({ left, top, right, bottom, width: right - left, height: bottom - top }) };
}

test("a slider hugging the left edge is reported for gesture exclusion", () => {
    /*
     * ESC Configurator's round knobs sit close to the left edge, so a drag on
     * one was also an edge swipe and Android took it as Back — the page
     * navigated away mid-adjustment. The app excludes those bands from the
     * system gesture, which needs to know where they are.
     */
    const chrome = loadChrome({ sliders: [sliderAt(4, 200, 60, 240)] });
    chrome.runTimers();

    const report = chrome.posted.find((m) => m.chrome === "exclude");
    assert.ok(report, "nothing was reported");
    // Widened to the edge itself, so the whole gesture strip is covered.
    assert.deepEqual(report.rects, [[0, 200, 60, 240]]);
});

test("a slider in the middle of the page is left alone", () => {
    // Excluding it would spend the system's 200dp-per-edge budget for nothing.
    const chrome = loadChrome({ sliders: [sliderAt(150, 200, 260, 240)] });
    chrome.runTimers();

    assert.deepEqual(chrome.posted.find((m) => m.chrome === "exclude")?.rects, []);
});

test("a slider scrolled off screen is not reported", () => {
    const chrome = loadChrome({ sliders: [sliderAt(4, -300, 60, -260)] });
    chrome.runTimers();

    assert.deepEqual(chrome.posted.find((m) => m.chrome === "exclude")?.rects, []);
});

test("a right-edge slider is widened to the right edge", () => {
    const chrome = loadChrome({ sliders: [sliderAt(340, 100, 396, 140)] });
    chrome.runTimers();

    assert.deepEqual(chrome.posted.find((m) => m.chrome === "exclude").rects, [[340, 100, 400, 140]]);
});

test("an unchanged set of sliders is not re-sent on every scroll", () => {
    // Scroll fires constantly; re-posting identical rectangles would be noise.
    const chrome = loadChrome({ sliders: [sliderAt(4, 200, 60, 240)] });
    chrome.runTimers();
    chrome.fire("scroll", { target: { scrollTop: 10 } });
    chrome.fire("scroll", { target: { scrollTop: 20 } });

    assert.equal(chrome.posted.filter((m) => m.chrome === "exclude").length, 1);
});

/** An event whose target sits inside (or outside) a slider and records dispatches. */
function cancelEvent(type, insideSlider, extra = {}) {
    const dispatched = [];
    const target = {
        closest: (selector) => (insideSlider && selector.includes("slider") ? {} : null),
        dispatchEvent: (event) => dispatched.push(event),
    };
    return { event: { type, target, ...extra }, dispatched };
}

test("a cancelled pointer drag is ended so the slider stops following later drags", () => {
    /*
     * reka-ui pairs setPointerCapture on pointerdown with releasePointerCapture
     * on pointerup only, and never listens for pointercancel. When the browser
     * cancels a gesture to pan, the slider keeps believing it is being dragged
     * and follows every later drag — move one motor, another moves. The end
     * event it is waiting for has to be supplied.
     */
    const { event, dispatched } = cancelEvent("pointercancel", true, { pointerId: 7, pointerType: "touch" });
    const chrome = loadChrome();
    chrome.fire("pointercancel", event);

    assert.equal(dispatched.length, 1);
    assert.equal(dispatched[0].type, "pointerup");
    assert.equal(dispatched[0].pointerId, 7, "the synthesised end must match the pointer that was cancelled");
});

test("a cancelled touch drag is ended too, which is ESC Configurator's case", () => {
    // react-input-range removes its document touchmove listener on touchend only.
    const { event, dispatched } = cancelEvent("touchcancel", true);
    const chrome = loadChrome();
    chrome.fire("touchcancel", event);

    assert.equal(dispatched.length, 1);
    assert.equal(dispatched[0].type, "touchend");
    assert.equal(dispatched[0].touches.length, 0, "the synthesised end must report no remaining touches");
});

test("a cancel outside a slider is left alone", () => {
    // Other page code may handle cancel properly; a duplicate end would be a bug.
    const { event, dispatched } = cancelEvent("pointercancel", false, { pointerId: 1 });
    const chrome = loadChrome();
    chrome.fire("pointercancel", event);

    assert.equal(dispatched.length, 0);
});

test("the label beside a slider is covered too, not just the widget", () => {
    /*
     * ESC Configurator renders the name and value outside .input-range — a
     * sibling inside the wrapping <label> — so suppressing selection on the
     * widget alone left "Master speed" and "Motor 1" grabbable, and a touch
     * starting there still became a selection instead of a drag. Betaflight
     * puts its motor labels in the <li> around each slider.
     */
    const text = loadChrome().style().textContent;
    assert.match(text, /label:has\(\.input-range\)/);
    assert.match(text, /label:has\(\[role="slider"\]\)/);
    assert.match(text, /li:has\(\[role="slider"\]\)/);
});

test("the row rules are still anchored on containing a slider", () => {
    // A bare label or li rule would strip selection from half the page.
    const text = loadChrome().style().textContent;
    for (const line of text.split("\n")) {
        const selectors = line.split("{")[0];
        for (const part of selectors.split(",")) {
            const selector = part.trim();
            if (!selector || selector.startsWith("-") || selector.includes(":")) continue;
            assert.ok(
                !["label", "li", "div", "span", "p"].includes(selector),
                `unanchored container selector: ${selector}`,
            );
        }
    }
});
