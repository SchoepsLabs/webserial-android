import assert from "node:assert/strict";
import test from "node:test";
import { createHarness, plain, tick } from "./harness.mjs";

const PORT_A = { handle: "serial_1", usbVendorId: 0x0483, usbProductId: 0x5740 };
const PORT_B = { handle: "serial_2", usbVendorId: 0x1a86, usbProductId: 0x7523 };

function serialHarness() {
    const harness = createHarness();
    harness
        .on("serial.getPorts", () => ({ ports: [PORT_A] }))
        .on("serial.requestPort", () => PORT_A)
        .on("serial.open", () => ({ driver: "CDC-ACM" }))
        .on("serial.close", () => ({}))
        .on("serial.write", () => ({ bytesWritten: 0 }))
        .on("serial.flush", () => ({}))
        .on("serial.setSignals", () => ({}))
        .on("serial.getSignals", () => ({
            dataCarrierDetect: true,
            clearToSend: false,
            ringIndicator: false,
            dataSetReady: true,
        }));
    return harness;
}

test("navigator.serial is a real own property, as ESC Configurator's `'serial' in navigator` check requires", () => {
    const { navigator } = createHarness();
    assert.ok("serial" in navigator);
    assert.ok(navigator.serial);
    assert.equal(typeof navigator.serial.requestPort, "function");
    assert.equal(typeof navigator.serial.getPorts, "function");
});

test("getPorts returns SerialPort objects exposing getInfo() with USB VID/PID", async () => {
    const { navigator } = serialHarness();
    const ports = await navigator.serial.getPorts();

    assert.equal(ports.length, 1);
    assert.deepEqual(plain(ports[0].getInfo()), { usbVendorId: 0x0483, usbProductId: 0x5740 });
});

test("getInfo() returns a fresh object each call so callers can mutate it", async () => {
    const { navigator } = serialHarness();
    const [port] = await navigator.serial.getPorts();

    const first = port.getInfo();
    first.usbVendorId = 0;
    assert.equal(port.getInfo().usbVendorId, 0x0483);
});

test("the same handle always yields the same SerialPort object", async () => {
    const { navigator } = serialHarness();
    const [first] = await navigator.serial.getPorts();
    const [second] = await navigator.serial.getPorts();

    // Betaflight keys a WeakMap on the port object and matches removed devices
    // by identity, so a second enumeration must not produce a new object.
    assert.equal(first, second);
});

test("requestPort() works with no arguments (ESC Configurator) and with filters (Betaflight)", async () => {
    const harness = serialHarness();
    const { navigator } = harness;

    await navigator.serial.requestPort();
    assert.deepEqual(harness.lastCall("serial.requestPort").args.filters, []);

    await navigator.serial.requestPort({ filters: [{ usbVendorId: 0x0483, usbProductId: 0x5740 }] });
    assert.deepEqual(harness.lastCall("serial.requestPort").args.filters, [
        { usbVendorId: 0x0483, usbProductId: 0x5740 },
    ]);

    await navigator.serial.requestPort({});
    assert.deepEqual(harness.lastCall("serial.requestPort").args.filters, []);
});

test("requestPort rejects with NotFoundError when the user picks nothing", async () => {
    const harness = createHarness();
    harness.on("serial.requestPort", () => null);

    await assert.rejects(() => harness.navigator.serial.requestPort(), (error) => {
        assert.equal(error.name, "NotFoundError");
        return true;
    });
});

test("open() forwards the baud rate and exposes readable/writable streams", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();

    assert.equal(port.readable, null);
    assert.equal(port.writable, null);

    await port.open({ baudRate: 115200 });

    assert.equal(harness.lastCall("serial.open").args.baudRate, 115200);
    assert.ok(port.readable);
    assert.ok(port.writable);
});

test("open() defaults to 115200 8N1 when called with no options", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open();

    const { args } = harness.lastCall("serial.open");
    assert.equal(args.baudRate, 115200);
    assert.equal(args.dataBits, 8);
    assert.equal(args.stopBits, 1);
    assert.equal(args.parity, "none");
});

test("open() rejects with InvalidStateError when the port is already open", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    await assert.rejects(() => port.open({ baudRate: 115200 }), (error) => {
        assert.equal(error.name, "InvalidStateError");
        return true;
    });
});

test("bytes pushed from native arrive through port.readable", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    const reader = port.readable.getReader();
    // "$M<" — the start of an MSP frame.
    harness.emit({ event: "serial.data", handle: "serial_1", data: btoa("$M<") });

    const { value, done } = await reader.read();
    assert.equal(done, false);
    assert.deepEqual(Array.from(value), [0x24, 0x4d, 0x3c]);
});

test("writes reach native base64-encoded and byte-exact", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    const writer = port.writable.getWriter();
    await writer.write(new Uint8Array([0x24, 0x4d, 0x3c, 0x00, 0xff, 0xfe]));

    const encoded = harness.lastCall("serial.write").args.data;
    assert.deepEqual(Array.from(Buffer.from(encoded, "base64")), [0x24, 0x4d, 0x3c, 0x00, 0xff, 0xfe]);
});

test("a firmware-sized write survives base64 chunking intact", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    const payload = new Uint8Array(200_000).map((_, index) => index % 256);
    const writer = port.writable.getWriter();
    await writer.write(payload);

    const decoded = Buffer.from(harness.lastCall("serial.write").args.data, "base64");
    assert.equal(decoded.length, payload.length);
    assert.ok(decoded.equals(Buffer.from(payload)));
});

test("the ESC Configurator teardown order works: cancel, releaseLock, releaseLock, close", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    const reader = port.readable.getReader();
    const writer = port.writable.getWriter();

    await reader.cancel();
    reader.releaseLock();
    writer.releaseLock();
    await port.close();

    assert.equal(harness.callsFor("serial.close").length, 1);
});

test("setSignals maps DTR/RTS and renames `break` to a JSON-safe key", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    await port.setSignals({ dataTerminalReady: true, requestToSend: false, break: true });

    const { args } = harness.lastCall("serial.setSignals");
    assert.equal(args.dataTerminalReady, true);
    assert.equal(args.requestToSend, false);
    assert.equal(args.brk, true);
});

test("setSignals sends only the signals the caller specified", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    await port.setSignals({ dataTerminalReady: false });

    const { args } = harness.lastCall("serial.setSignals");
    assert.equal(args.dataTerminalReady, false);
    assert.ok(!("requestToSend" in args));
    assert.ok(!("brk" in args));
});

test("getSignals returns the four spec-defined booleans", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    assert.deepEqual(plain(await port.getSignals()), {
        dataCarrierDetect: true,
        clearToSend: false,
        ringIndicator: false,
        dataSetReady: true,
    });
});

test("a serial connect event has event.target set to the port itself", async () => {
    const harness = serialHarness();
    const { navigator } = harness;

    let observed = null;
    navigator.serial.addEventListener("connect", (event) => {
        observed = event;
    });

    harness.emit({ event: "serial.connect", port: PORT_B });
    await tick();

    assert.ok(observed, "the connect listener never fired");
    // Betaflight's handleRemovedDevice matches on e.target identity; without
    // this the removed device is never found and the port list goes stale.
    assert.equal(observed.type, "connect");
    assert.deepEqual(plain(observed.target.getInfo()), { usbVendorId: 0x1a86, usbProductId: 0x7523 });

    const [portFromEvent] = [observed.target];
    harness.emit({ event: "serial.connect", port: PORT_B });
    await tick();
    assert.equal(observed.target, portFromEvent);
});

test("a serial disconnect event reaches both navigator.serial and the port's own listener", async () => {
    const harness = serialHarness();
    const { navigator } = harness;

    const [port] = await navigator.serial.getPorts();

    let navigatorEvent = null;
    let portEvent = null;
    navigator.serial.addEventListener("disconnect", (event) => {
        navigatorEvent = event;
    });
    // Betaflight registers this directly on the open port (WebSerial.js connect()).
    port.addEventListener("disconnect", (event) => {
        portEvent = event;
    });

    harness.emit({ event: "serial.disconnect", port: { handle: "serial_1" } });
    await tick();

    assert.ok(portEvent, "the port-level disconnect listener never fired");
    assert.ok(navigatorEvent, "the navigator-level disconnect listener never fired");
    assert.equal(navigatorEvent.target, port);
});

test("removeEventListener detaches a navigator.serial connect listener", async () => {
    const harness = serialHarness();
    const { navigator } = harness;

    let count = 0;
    const listener = () => {
        count += 1;
    };
    navigator.serial.addEventListener("connect", listener);
    harness.emit({ event: "serial.connect", port: PORT_B });
    await tick();

    navigator.serial.removeEventListener("connect", listener);
    harness.emit({ event: "serial.connect", port: PORT_A });
    await tick();

    assert.equal(count, 1);
});

test("a disconnect closes the readable stream instead of leaving a reader hanging", async () => {
    const harness = serialHarness();
    const [port] = await harness.navigator.serial.getPorts();
    await port.open({ baudRate: 115200 });

    const reader = port.readable.getReader();
    harness.emit({ event: "serial.disconnect", port: { handle: "serial_1" } });

    const { done } = await reader.read();
    assert.equal(done, true);
});

test("calls made before the bridge object exists are queued, not lost", async () => {
    const harness = createHarness({ installBridgeLate: true });
    harness.on("serial.getPorts", () => ({ ports: [PORT_A] }));

    // addDocumentStartJavaScript and addWebMessageListener have no guaranteed
    // ordering, so the polyfill has to tolerate the object showing up late.
    const pending = harness.navigator.serial.getPorts();
    harness.installBridge();

    const ports = await pending;
    assert.equal(ports.length, 1);
});
