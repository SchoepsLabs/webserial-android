import assert from "node:assert/strict";
import test from "node:test";
import { bytesOf, createHarness, plain, tick } from "./harness.mjs";

/** An STM32 board sitting in DFU mode, as Betaflight's DFU transport expects it. */
const DFU_DEVICE = {
    handle: "usb_1",
    vendorId: 0x0483,
    productId: 0xdf11,
    deviceClass: 0,
    deviceSubclass: 0,
    deviceProtocol: 0,
    deviceVersionMajor: 2,
    deviceVersionMinor: 2,
    deviceVersionSubminor: 0,
    manufacturerName: "STMicroelectronics",
    productName: "STM32 BOOTLOADER",
    serialNumber: "356E36713234",
    configurations: [
        {
            configurationValue: 1,
            configurationName: null,
            interfaces: [
                {
                    interfaceNumber: 0,
                    alternates: [
                        {
                            alternateSetting: 0,
                            interfaceClass: 0xfe,
                            interfaceSubclass: 0x01,
                            interfaceProtocol: 0x02,
                            interfaceName: "@Internal Flash /0x08000000/04*016Kg",
                            endpoints: [],
                        },
                        {
                            alternateSetting: 1,
                            interfaceClass: 0xfe,
                            interfaceSubclass: 0x01,
                            interfaceProtocol: 0x02,
                            interfaceName: "@Option Bytes /0x1FFFC000/01*016Ke",
                            endpoints: [],
                        },
                    ],
                },
            ],
        },
    ],
};

function usbHarness() {
    const harness = createHarness();
    harness
        .on("usb.getDevices", () => ({ devices: [DFU_DEVICE] }))
        .on("usb.requestDevice", () => DFU_DEVICE)
        .on("usb.open", () => ({}))
        .on("usb.close", () => ({}))
        .on("usb.selectConfiguration", () => ({}))
        .on("usb.claimInterface", () => ({}))
        .on("usb.releaseInterface", () => ({}))
        .on("usb.selectAlternateInterface", () => ({}))
        .on("usb.clearHalt", () => ({}))
        .on("usb.reset", () => ({}));
    return harness;
}

test("navigator.usb exposes getDevices and requestDevice", () => {
    const { navigator } = createHarness();
    assert.ok("usb" in navigator);
    assert.equal(typeof navigator.usb.getDevices, "function");
    assert.equal(typeof navigator.usb.requestDevice, "function");
});

test("getDevices returns USBDevice objects with descriptors and configurations", async () => {
    const { navigator } = usbHarness();
    const [device] = await navigator.usb.getDevices();

    assert.equal(device.vendorId, 0x0483);
    assert.equal(device.productId, 0xdf11);
    assert.equal(device.productName, "STM32 BOOTLOADER");
    assert.equal(device.manufacturerName, "STMicroelectronics");
    assert.equal(device.serialNumber, "356E36713234");
    assert.equal(device.deviceVersionMajor, 2);
    assert.equal(device.deviceVersionMinor, 2);
    assert.equal(device.deviceVersionSubminor, 0);

    assert.equal(device.configurations.length, 1);
    assert.equal(device.configurations[0].interfaces.length, 1);
    assert.equal(device.configurations[0].interfaces[0].alternates.length, 2);
    assert.equal(device.configurations[0].interfaces[0].alternates[1].interfaceClass, 0xfe);
});

test("device.configuration starts null so Betaflight's open() calls selectConfiguration(1)", async () => {
    const harness = usbHarness();
    const [device] = await harness.navigator.usb.getDevices();

    // Mirrors WebUsbDfuTransport.open() exactly.
    await device.open();
    assert.equal(device.opened, true);
    assert.equal(device.configuration, null);

    await device.selectConfiguration(1);
    assert.equal(device.configuration.configurationValue, 1);
    assert.equal(harness.lastCall("usb.selectConfiguration").args.configurationValue, 1);
});

test("claimInterface and releaseInterface flip the claimed flag", async () => {
    const { navigator } = usbHarness();
    const [device] = await navigator.usb.getDevices();
    await device.open();
    await device.selectConfiguration(1);

    await device.claimInterface(0);
    assert.equal(device.configuration.interfaces[0].claimed, true);

    await device.releaseInterface(0);
    assert.equal(device.configuration.interfaces[0].claimed, false);
});

test("selectAlternateInterface switches the active alternate", async () => {
    const { navigator } = usbHarness();
    const [device] = await navigator.usb.getDevices();
    await device.open();
    await device.selectConfiguration(1);

    assert.equal(device.configuration.interfaces[0].alternate.alternateSetting, 0);
    await device.selectAlternateInterface(0, 1);
    assert.equal(device.configuration.interfaces[0].alternate.alternateSetting, 1);
    assert.match(device.configuration.interfaces[0].alternate.interfaceName, /Option Bytes/);
});

test("close() clears the configuration and every claimed flag", async () => {
    const { navigator } = usbHarness();
    const [device] = await navigator.usb.getDevices();
    await device.open();
    await device.selectConfiguration(1);
    await device.claimInterface(0);

    await device.close();

    assert.equal(device.opened, false);
    assert.equal(device.configuration, null);
    assert.equal(device.configurations[0].interfaces[0].claimed, false);
});

test("controlTransferIn returns a DataView and forwards the setup packet verbatim", async () => {
    const harness = usbHarness();
    harness.on("usb.controlTransferIn", () => ({
        status: "ok",
        data: Buffer.from([0x12, 0x01, 0x00, 0x02]).toString("base64"),
    }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    const result = await device.controlTransferIn(
        { requestType: "standard", recipient: "device", request: 0x06, value: 0x0100, index: 0x0000 },
        4,
    );

    assert.equal(result.status, "ok");
    assert.ok(result.data instanceof DataView);
    assert.equal(result.data.byteLength, 4);
    assert.equal(result.data.getUint8(0), 0x12);
    assert.deepEqual(bytesOf(result.data), [0x12, 0x01, 0x00, 0x02]);

    const { args } = harness.lastCall("usb.controlTransferIn");
    assert.deepEqual(args.setup, {
        requestType: "standard",
        recipient: "device",
        request: 0x06,
        value: 0x0100,
        index: 0x0000,
    });
    assert.equal(args.length, 4);
});

test("a stalled controlTransferIn resolves with status 'stall' rather than rejecting", async () => {
    const harness = usbHarness();
    harness.on("usb.controlTransferIn", () => ({ status: "stall", data: "" }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    // Betaflight's descriptor layer decides for itself what a stall means — an
    // unsupported LANGID read is recoverable — so a rejection here would abort
    // descriptor probing on a perfectly healthy board.
    const result = await device.controlTransferIn(
        { requestType: "standard", recipient: "device", request: 0x06, value: 0x0300, index: 0 },
        255,
    );

    assert.equal(result.status, "stall");
    assert.ok(result.data instanceof DataView);
    assert.equal(result.data.byteLength, 0);
});

test("controlTransferIn accepts an interface-recipient class request (DFU_GETSTATUS)", async () => {
    const harness = usbHarness();
    harness.on("usb.controlTransferIn", () => ({
        status: "ok",
        data: Buffer.from([0x00, 0x05, 0x00, 0x00, 0x02, 0x00]).toString("base64"),
    }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    const result = await device.controlTransferIn(
        { requestType: "class", recipient: "interface", request: 0x03, value: 0, index: 0 },
        6,
    );

    assert.equal(result.data.byteLength, 6);
    assert.equal(harness.lastCall("usb.controlTransferIn").args.setup.requestType, "class");
    assert.equal(harness.lastCall("usb.controlTransferIn").args.setup.recipient, "interface");
});

test("controlTransferOut base64-encodes its payload and reports bytesWritten", async () => {
    const harness = usbHarness();
    harness.on("usb.controlTransferOut", () => ({ status: "ok", bytesWritten: 3 }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    const result = await device.controlTransferOut(
        { requestType: "class", recipient: "interface", request: 0x01, value: 2, index: 0 },
        new Uint8Array([0x21, 0x00, 0x00]),
    );

    assert.equal(result.status, "ok");
    assert.equal(result.bytesWritten, 3);
    const sent = Buffer.from(harness.lastCall("usb.controlTransferOut").args.data, "base64");
    assert.deepEqual(Array.from(sent), [0x21, 0x00, 0x00]);
});

test("controlTransferOut with no payload sends an empty transfer", async () => {
    const harness = usbHarness();
    harness.on("usb.controlTransferOut", () => ({ status: "ok", bytesWritten: 0 }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    await device.controlTransferOut({
        requestType: "class",
        recipient: "interface",
        request: 0x04,
        value: 0,
        index: 0,
    });

    assert.equal(harness.lastCall("usb.controlTransferOut").args.data, "");
});

test("transferIn and transferOut carry bulk payloads both ways", async () => {
    const harness = usbHarness();
    harness
        .on("usb.transferIn", () => ({ status: "ok", data: Buffer.from([1, 2, 3]).toString("base64") }))
        .on("usb.transferOut", () => ({ status: "ok", bytesWritten: 2 }));

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    const incoming = await device.transferIn(1, 64);
    assert.deepEqual(bytesOf(incoming.data), [1, 2, 3]);

    const outgoing = await device.transferOut(2, new Uint8Array([0xaa, 0xbb]));
    assert.equal(outgoing.bytesWritten, 2);
    assert.deepEqual(
        Array.from(Buffer.from(harness.lastCall("usb.transferOut").args.data, "base64")),
        [0xaa, 0xbb],
    );
});

test("device.reset() resolves even when the native side fails it", async () => {
    const harness = usbHarness();
    harness.on("usb.reset", () => {
        throw Object.assign(new Error("no reset on Android"), { name: "NotSupportedError" });
    });

    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    // usbdfu.js calls reset() as the final step of a flash; rejecting there
    // would report a failure for firmware that has already been written.
    await device.reset();
});

test("a usb connect event carries the device on event.device", async () => {
    const harness = usbHarness();

    let observed = null;
    harness.navigator.usb.addEventListener("connect", (event) => {
        observed = event;
    });

    harness.emit({ event: "usb.connect", device: DFU_DEVICE });
    await tick();

    assert.ok(observed, "the usb connect listener never fired");
    // WebUsbDfuTransport reads e.device, not e.target.
    assert.ok(observed.device);
    assert.equal(observed.device.vendorId, 0x0483);
    assert.equal(observed.device.productId, 0xdf11);
});

test("a usb disconnect event carries the device and marks it closed", async () => {
    const harness = usbHarness();
    const [device] = await harness.navigator.usb.getDevices();
    await device.open();

    let observed = null;
    harness.navigator.usb.addEventListener("disconnect", (event) => {
        observed = event;
    });

    harness.emit({ event: "usb.disconnect", device: { handle: "usb_1" } });
    await tick();

    assert.ok(observed);
    assert.equal(observed.device, device);
    assert.equal(device.opened, false);
});

test("the DFU re-enumeration poll sees the bootloader appear through getDevices()", async () => {
    const harness = createHarness();

    // Betaflight's waitForDfuDevice() polls getDevices() rather than waiting on
    // an event, so a newly authorised bootloader has to show up there.
    let attached = false;
    harness.on("usb.getDevices", () => ({ devices: attached ? [DFU_DEVICE] : [] }));

    assert.equal((await harness.navigator.usb.getDevices()).length, 0);

    attached = true;
    const [device] = await harness.navigator.usb.getDevices();
    assert.equal(device.productId, 0xdf11);
});

test("requestDevice forwards filters and rejects with NotFoundError when nothing is picked", async () => {
    const harness = usbHarness();
    await harness.navigator.usb.requestDevice({ filters: [{ vendorId: 0x0483, productId: 0xdf11 }] });
    assert.deepEqual(harness.lastCall("usb.requestDevice").args.filters, [
        { vendorId: 0x0483, productId: 0xdf11 },
    ]);

    const empty = createHarness();
    empty.on("usb.requestDevice", () => null);
    await assert.rejects(() => empty.navigator.usb.requestDevice({ filters: [] }), (error) => {
        assert.equal(error.name, "NotFoundError");
        return true;
    });
});

test("a native error is surfaced as a DOMException-shaped rejection", async () => {
    const harness = usbHarness();
    harness.on("usb.open", () => {
        throw Object.assign(new Error("This origin is not authorised for that device"), {
            name: "SecurityError",
        });
    });

    const [device] = await harness.navigator.usb.getDevices();
    await assert.rejects(() => device.open(), (error) => {
        assert.equal(error.name, "SecurityError");
        assert.match(error.message, /not authorised/);
        return true;
    });
});

test("the same handle always yields the same USBDevice object", async () => {
    const { navigator } = usbHarness();
    const [first] = await navigator.usb.getDevices();
    const [second] = await navigator.usb.getDevices();
    assert.equal(first, second);
});
