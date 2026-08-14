import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const POLYFILL_PATH = path.resolve(HERE, "../app/src/main/assets/bridge/polyfill.js");
const BRIDGE_NAME = "AndroidConfiguratorBridge";

/**
 * Loads the shipped polyfill into a fresh V8 context with a mock native bridge.
 *
 * The tests run the exact file the APK injects — no rebuilt or trimmed copy —
 * so a change that breaks Betaflight or ESC Configurator fails here.
 */
export function createHarness({ installBridgeLate = false, preexisting = null } = {}) {
    const source = fs.readFileSync(POLYFILL_PATH, "utf8");

    /** Every message the polyfill sent to native, in order. */
    const calls = [];
    /** op -> handler(args, message) returning a result (or throwing to reject). */
    const handlers = new Map();
    let onmessage = null;

    const respond = (payload) => {
        if (!onmessage) throw new Error("The polyfill has not attached onmessage yet");
        onmessage({ data: JSON.stringify(payload) });
    };

    const bridge = {
        postMessage(text) {
            const message = JSON.parse(text);
            calls.push(message);
            const handler = handlers.get(message.op);
            if (!handler) return; // the test will reply by hand
            queueMicrotask(async () => {
                try {
                    respond({ id: message.id, ok: true, result: await handler(message.args, message) });
                } catch (error) {
                    respond({
                        id: message.id,
                        ok: false,
                        error: { name: error.name, message: error.message },
                    });
                }
            });
        },
        get onmessage() {
            return onmessage;
        },
        set onmessage(fn) {
            onmessage = fn;
        },
    };

    const navigator = {};
    const sandbox = {
        navigator,
        console,
        setTimeout,
        clearTimeout,
        queueMicrotask,
        atob,
        btoa,
        EventTarget,
        Event,
        DOMException,
        ReadableStream,
        WritableStream,
        Blob,
        File,
        TextEncoder,
        TextDecoder,
        // Pinned so `instanceof` agrees between the polyfill and the assertions.
        Uint8Array,
        ArrayBuffer,
        DataView,
        Map,
        Set,
        Promise,
        JSON,
        TypeError,
        Error,
    };

    if (!installBridgeLate) {
        sandbox[BRIDGE_NAME] = bridge;
    }

    // Simulates APIs the host already defines — an Android WebView ships a File
    // System Access API that exists but does not work.
    if (preexisting) {
        Object.assign(sandbox, preexisting);
    }

    vm.createContext(sandbox);
    vm.runInContext(source, sandbox, { filename: "polyfill.js" });

    return {
        sandbox,
        navigator,
        bridge,
        calls,
        /** Register an auto-responder for an op. */
        on(op, handler) {
            handlers.set(op, handler);
            return this;
        },
        /** Reply to a specific pending call by id. */
        reply(id, result) {
            respond({ id, ok: true, result });
        },
        fail(id, name, message) {
            respond({ id, ok: false, error: { name, message } });
        },
        /** Push a native-initiated event (device attach, incoming serial bytes). */
        emit(event) {
            respond(event);
        },
        /** Install the bridge object after the fact, for the injection-order test. */
        installBridge() {
            sandbox[BRIDGE_NAME] = bridge;
        },
        callsFor(op) {
            return calls.filter((call) => call.op === op);
        },
        lastCall(op) {
            const matching = calls.filter((call) => call.op === op);
            return matching[matching.length - 1];
        },
    };
}

/** Yields to the microtask queue and any pending timers. */
export function tick(ms = 0) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Re-materialises an object created inside the vm context in this realm.
 *
 * Values built by the polyfill carry the vm's Object.prototype, which
 * deepStrictEqual treats as a mismatch even when the structure is identical.
 */
export function plain(value) {
    return JSON.parse(JSON.stringify(value));
}

export function bytesOf(dataView) {
    return Array.from(new Uint8Array(dataView.buffer, dataView.byteOffset, dataView.byteLength));
}
