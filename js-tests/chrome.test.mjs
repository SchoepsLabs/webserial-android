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
function loadChrome({ head = true } = {}) {
    const source = fs.readFileSync(CHROME_PATH, "utf8");
    const appended = [];
    const listeners = new Map();
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
        addEventListener: (type, handler) => listeners.set(type, handler),
    };

    const sandbox = { document, addEventListener() {}, WeakMap };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);

    return {
        sandbox,
        appended,
        style: () => appended.find((node) => node.id === "__configurator_slider_css"),
        fire: (type) => listeners.get(type)?.(),
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
    const text = loadChrome().style().textContent;
    for (const universal of ["*{", "* {", "body {", "html {", ":root {"]) {
        assert.ok(!text.includes(universal), `stylesheet applies to ${universal}`);
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
