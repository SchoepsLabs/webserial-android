/*
 * Web Serial + WebUSB polyfills for the Android System WebView, backed by the
 * Android USB Host API through an origin-scoped WebMessageListener bridge.
 *
 * Injected with WebViewCompat.addDocumentStartJavaScript() and only ever
 * delivered to the allow-listed configurator origins. This is also the exact
 * artifact exercised by js-tests/ (loaded into a node:vm context), so it must
 * not depend on anything beyond standard web platform globals plus the injected
 * bridge object.
 *
 * addDocumentStartJavaScript requires WebView 106+, so modern syntax is safe.
 *
 * Shapes that real hardware depends on, and that the tests pin:
 *   - navigator.serial "connect"/"disconnect" events have event.target === port
 *     (Betaflight's WebSerial.js matches the removed device by identity).
 *   - A SerialPort is itself an EventTarget and receives its own "disconnect".
 *   - navigator.usb events carry event.device.
 *   - controlTransferIn resolves with {status, data} on a stall; it must not
 *     reject, because the DFU descriptor layer treats some stalls as expected.
 */
(function (global) {
    "use strict";

    const BRIDGE_NAME = "AndroidConfiguratorBridge";

    if (global.__trustedConfiguratorBridgeInstalled) {
        return;
    }
    global.__trustedConfiguratorBridgeInstalled = true;

    // ---------------------------------------------------------------- utils

    function toUint8(data) {
        if (data == null) {
            return new Uint8Array(0);
        }
        if (data instanceof Uint8Array) {
            return data;
        }
        if (data instanceof ArrayBuffer) {
            return new Uint8Array(data);
        }
        if (ArrayBuffer.isView(data)) {
            return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
        }
        if (Array.isArray(data)) {
            return new Uint8Array(data);
        }
        throw new TypeError("Unsupported buffer source");
    }

    function encodeBase64(data) {
        const bytes = toUint8(data);
        // Chunked so a firmware-sized write does not exceed the argument limit.
        const CHUNK = 0x8000;
        let binary = "";
        for (let i = 0; i < bytes.length; i += CHUNK) {
            binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
        }
        return global.btoa(binary);
    }

    function decodeBase64(text) {
        if (!text) {
            return new Uint8Array(0);
        }
        const binary = global.atob(text);
        const out = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) {
            out[i] = binary.charCodeAt(i);
        }
        return out;
    }

    function toDataView(bytes) {
        return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    }

    function domError(name, message) {
        // DOMException is not constructible in every host the tests run in; the
        // fallback keeps `error.name` meaningful either way.
        try {
            return new global.DOMException(message, name);
        } catch (e) {
            const error = new Error(message);
            error.name = name;
            return error;
        }
    }

    function invoke(listener, thisArg, event) {
        try {
            if (typeof listener === "function") {
                listener.call(thisArg, event);
            } else if (listener && typeof listener.handleEvent === "function") {
                listener.handleEvent(event);
            }
        } catch (e) {
            console.error("[configurator-bridge] listener failed", e);
        }
    }

    // --------------------------------------------------------------- bridge

    let nextRequestId = 1;
    const pending = new Map();
    const eventHandlers = Object.create(null);
    let bridgeReady = null;

    function onBridgeEvent(type, handler) {
        (eventHandlers[type] || (eventHandlers[type] = [])).push(handler);
    }

    function handleBridgeMessage(raw) {
        let message;
        try {
            message = typeof raw === "string" ? JSON.parse(raw) : raw;
        } catch (e) {
            console.error("[configurator-bridge] malformed message", e);
            return;
        }
        if (message == null) {
            return;
        }

        if (message.event) {
            const handlers = eventHandlers[message.event];
            if (handlers) {
                for (const handler of handlers) {
                    try {
                        handler(message);
                    } catch (e) {
                        console.error("[configurator-bridge] event handler failed", e);
                    }
                }
            }
            return;
        }

        const entry = pending.get(message.id);
        if (!entry) {
            return;
        }
        pending.delete(message.id);
        if (message.ok) {
            entry.resolve(message.result);
        } else {
            const info = message.error || {};
            entry.reject(domError(info.name || "NetworkError", info.message || "USB bridge call failed"));
        }
    }

    /*
     * addDocumentStartJavaScript and addWebMessageListener are independent
     * injections with no ordering guarantee between them, so calls queue here
     * until the bridge object actually appears.
     */
    function whenBridgeReady() {
        if (bridgeReady) {
            return bridgeReady;
        }
        bridgeReady = new Promise((resolve, reject) => {
            let waited = 0;
            const attempt = () => {
                const bridge = global[BRIDGE_NAME];
                if (bridge && typeof bridge.postMessage === "function") {
                    bridge.onmessage = (event) => {
                        handleBridgeMessage(event && event.data !== undefined ? event.data : event);
                    };
                    resolve(bridge);
                    return;
                }
                waited += 20;
                if (waited > 10000) {
                    reject(domError("NotSupportedError", "Native USB bridge is unavailable on this origin"));
                    return;
                }
                global.setTimeout(attempt, 20);
            };
            attempt();
        });
        return bridgeReady;
    }

    function rpc(op, args) {
        return whenBridgeReady().then(
            (bridge) =>
                new Promise((resolve, reject) => {
                    const id = nextRequestId++;
                    pending.set(id, { resolve, reject });
                    try {
                        bridge.postMessage(JSON.stringify({ id, op, args: args || {} }));
                    } catch (e) {
                        pending.delete(id);
                        reject(e);
                    }
                }),
        );
    }

    // ------------------------------------------------- Web Serial: SerialPort

    const portsByHandle = new Map();

    class SerialPortImpl extends EventTarget {
        constructor(descriptor) {
            super();
            this._handle = descriptor.handle;
            this._info = {
                usbVendorId: descriptor.usbVendorId,
                usbProductId: descriptor.usbProductId,
            };
            this._open = false;
            this._readable = null;
            this._writable = null;
            this._readController = null;
            this.onconnect = null;
            this.ondisconnect = null;
        }

        getInfo() {
            // A fresh object each call: callers cache and mutate what they get.
            return { usbVendorId: this._info.usbVendorId, usbProductId: this._info.usbProductId };
        }

        get readable() {
            return this._readable;
        }

        get writable() {
            return this._writable;
        }

        open(options) {
            const opts = options || {};
            if (this._open) {
                return Promise.reject(domError("InvalidStateError", "The port is already open"));
            }
            return rpc("serial.open", {
                handle: this._handle,
                baudRate: opts.baudRate === undefined ? 115200 : opts.baudRate,
                dataBits: opts.dataBits === undefined ? 8 : opts.dataBits,
                stopBits: opts.stopBits === undefined ? 1 : opts.stopBits,
                parity: opts.parity || "none",
                bufferSize: opts.bufferSize === undefined ? 65536 : opts.bufferSize,
                flowControl: opts.flowControl || "none",
            }).then(() => {
                this._open = true;
                this._installStreams();
            });
        }

        _installStreams() {
            this._readable = new global.ReadableStream({
                start: (controller) => {
                    this._readController = controller;
                },
                cancel: () => {
                    this._readController = null;
                    // Best effort: by now the port may already be gone.
                    return rpc("serial.flush", { handle: this._handle }).catch(() => {});
                },
            });

            this._writable = new global.WritableStream({
                write: (chunk) => rpc("serial.write", { handle: this._handle, data: encodeBase64(chunk) }),
            });
        }

        _pushData(bytes) {
            if (!this._readController) {
                return;
            }
            try {
                this._readController.enqueue(bytes);
            } catch (e) {
                // The consumer cancelled between the native push and this enqueue.
                this._readController = null;
            }
        }

        _teardownStreams(reason) {
            const controller = this._readController;
            this._readController = null;
            if (controller) {
                try {
                    if (reason) {
                        controller.error(reason);
                    } else {
                        controller.close();
                    }
                } catch (e) {
                    /* already closed or errored */
                }
            }
            this._readable = null;
            this._writable = null;
        }

        close() {
            if (!this._open) {
                return Promise.resolve();
            }
            this._open = false;
            this._teardownStreams(null);
            return rpc("serial.close", { handle: this._handle }).then(() => {});
        }

        setSignals(signals) {
            const payload = { handle: this._handle };
            const input = signals || {};
            if (input.dataTerminalReady !== undefined) {
                payload.dataTerminalReady = !!input.dataTerminalReady;
            }
            if (input.requestToSend !== undefined) {
                payload.requestToSend = !!input.requestToSend;
            }
            if (input["break"] !== undefined) {
                payload.brk = !!input["break"];
            }
            return rpc("serial.setSignals", payload).then(() => {});
        }

        getSignals() {
            return rpc("serial.getSignals", { handle: this._handle }).then((result) => ({
                dataCarrierDetect: !!(result && result.dataCarrierDetect),
                clearToSend: !!(result && result.clearToSend),
                ringIndicator: !!(result && result.ringIndicator),
                dataSetReady: !!(result && result.dataSetReady),
            }));
        }

        forget() {
            return rpc("serial.forget", { handle: this._handle }).then(() => {
                this._open = false;
                this._teardownStreams(null);
                portsByHandle.delete(this._handle);
            });
        }
    }

    function serialPortFor(descriptor) {
        const existing = portsByHandle.get(descriptor.handle);
        if (existing) {
            return existing;
        }
        const port = new SerialPortImpl(descriptor);
        portsByHandle.set(descriptor.handle, port);
        return port;
    }

    // ----------------------------------------------------- Web Serial: Serial

    class SerialImpl extends EventTarget {
        constructor() {
            super();
            /*
             * connect/disconnect listeners are tracked here rather than going
             * through EventTarget dispatch. The event has to be dispatched on the
             * *port* so that event.target is the port — Betaflight identifies the
             * removed device with `port.port === e.target` — and an Event can
             * only ever carry one target.
             */
            this._listeners = { connect: [], disconnect: [] };
            this.onconnect = null;
            this.ondisconnect = null;
        }

        addEventListener(type, listener, options) {
            if ((type === "connect" || type === "disconnect") && listener) {
                this._listeners[type].push(listener);
                return;
            }
            super.addEventListener(type, listener, options);
        }

        removeEventListener(type, listener, options) {
            if (type === "connect" || type === "disconnect") {
                const list = this._listeners[type];
                const index = list.indexOf(listener);
                if (index >= 0) {
                    list.splice(index, 1);
                }
                return;
            }
            super.removeEventListener(type, listener, options);
        }

        getPorts() {
            return rpc("serial.getPorts", {}).then((result) =>
                (result && result.ports ? result.ports : []).map(serialPortFor),
            );
        }

        requestPort(options) {
            // Betaflight passes {filters:[...]} or {}; ESC Configurator passes nothing.
            const filters = options && Array.isArray(options.filters) ? options.filters : [];
            return rpc("serial.requestPort", { filters }).then((descriptor) => {
                if (!descriptor) {
                    throw domError("NotFoundError", "No port selected by the user");
                }
                return serialPortFor(descriptor);
            });
        }
    }

    const serialImpl = new SerialImpl();

    function dispatchSerialConnection(type, port) {
        const event = new global.Event(type);
        // Dispatching on the port sets event.target to the port, and target
        // stays set once dispatch completes — which is what the navigator-level
        // listeners below then read.
        port.dispatchEvent(event);
        invoke(port["on" + type], port, event);

        for (const listener of serialImpl._listeners[type].slice()) {
            invoke(listener, serialImpl, event);
        }
        invoke(serialImpl["on" + type], serialImpl, event);
    }

    onBridgeEvent("serial.data", (message) => {
        const port = portsByHandle.get(message.handle);
        if (port) {
            port._pushData(decodeBase64(message.data));
        }
    });

    onBridgeEvent("serial.connect", (message) => {
        dispatchSerialConnection("connect", serialPortFor(message.port));
    });

    onBridgeEvent("serial.disconnect", (message) => {
        const port = portsByHandle.get(message.port.handle);
        if (!port) {
            return;
        }
        port._open = false;
        port._teardownStreams(null);
        portsByHandle.delete(message.port.handle);
        dispatchSerialConnection("disconnect", port);
    });

    // --------------------------------------------------- WebUSB: USBDevice

    const devicesByHandle = new Map();

    function buildAlternate(raw) {
        return {
            alternateSetting: raw.alternateSetting,
            interfaceClass: raw.interfaceClass,
            interfaceSubclass: raw.interfaceSubclass,
            interfaceProtocol: raw.interfaceProtocol,
            interfaceName: raw.interfaceName === undefined ? null : raw.interfaceName,
            endpoints: (raw.endpoints || []).map((endpoint) => ({
                endpointNumber: endpoint.endpointNumber,
                direction: endpoint.direction,
                type: endpoint.type,
                packetSize: endpoint.packetSize,
            })),
        };
    }

    function buildConfiguration(raw) {
        if (!raw) {
            return null;
        }
        return {
            configurationValue: raw.configurationValue,
            configurationName: raw.configurationName === undefined ? null : raw.configurationName,
            interfaces: (raw.interfaces || []).map((iface) => {
                const alternates = (iface.alternates || []).map(buildAlternate);
                return {
                    interfaceNumber: iface.interfaceNumber,
                    alternate: alternates[0] || null,
                    alternates,
                    claimed: false,
                };
            }),
        };
    }

    class USBDeviceImpl {
        constructor(descriptor) {
            this._handle = descriptor.handle;
            this.usbVersionMajor = descriptor.usbVersionMajor || 0;
            this.usbVersionMinor = descriptor.usbVersionMinor || 0;
            this.usbVersionSubminor = descriptor.usbVersionSubminor || 0;
            this.deviceClass = descriptor.deviceClass || 0;
            this.deviceSubclass = descriptor.deviceSubclass || 0;
            this.deviceProtocol = descriptor.deviceProtocol || 0;
            this.vendorId = descriptor.vendorId;
            this.productId = descriptor.productId;
            this.deviceVersionMajor = descriptor.deviceVersionMajor || 0;
            this.deviceVersionMinor = descriptor.deviceVersionMinor || 0;
            this.deviceVersionSubminor = descriptor.deviceVersionSubminor || 0;
            this.manufacturerName = descriptor.manufacturerName || null;
            this.productName = descriptor.productName || null;
            this.serialNumber = descriptor.serialNumber || null;
            this.configurations = (descriptor.configurations || []).map(buildConfiguration);
            // Betaflight's DFU open() checks `configuration === null` and calls
            // selectConfiguration(1), so this must start null.
            this.configuration = null;
            this.opened = false;
        }

        _selectConfigurationLocally(value) {
            const match = this.configurations.find((config) => config.configurationValue === value);
            this.configuration = match || this.configurations[0] || null;
        }

        _findInterface(interfaceNumber) {
            if (!this.configuration) {
                return null;
            }
            return this.configuration.interfaces.find((iface) => iface.interfaceNumber === interfaceNumber) || null;
        }

        open() {
            return rpc("usb.open", { handle: this._handle }).then(() => {
                this.opened = true;
            });
        }

        close() {
            return rpc("usb.close", { handle: this._handle }).then(() => {
                this.opened = false;
                this.configuration = null;
                for (const config of this.configurations) {
                    for (const iface of config.interfaces) {
                        iface.claimed = false;
                    }
                }
            });
        }

        selectConfiguration(configurationValue) {
            return rpc("usb.selectConfiguration", {
                handle: this._handle,
                configurationValue,
            }).then(() => {
                this._selectConfigurationLocally(configurationValue);
            });
        }

        claimInterface(interfaceNumber) {
            return rpc("usb.claimInterface", { handle: this._handle, interfaceNumber }).then(() => {
                const iface = this._findInterface(interfaceNumber);
                if (iface) {
                    iface.claimed = true;
                }
            });
        }

        releaseInterface(interfaceNumber) {
            return rpc("usb.releaseInterface", { handle: this._handle, interfaceNumber }).then(() => {
                const iface = this._findInterface(interfaceNumber);
                if (iface) {
                    iface.claimed = false;
                }
            });
        }

        selectAlternateInterface(interfaceNumber, alternateSetting) {
            return rpc("usb.selectAlternateInterface", {
                handle: this._handle,
                interfaceNumber,
                alternateSetting,
            }).then(() => {
                const iface = this._findInterface(interfaceNumber);
                if (!iface) {
                    return;
                }
                const match = iface.alternates.find((alt) => alt.alternateSetting === alternateSetting);
                if (match) {
                    iface.alternate = match;
                }
            });
        }

        controlTransferIn(setup, length) {
            return rpc("usb.controlTransferIn", {
                handle: this._handle,
                setup: normalizeSetup(setup),
                length,
            }).then((result) => ({
                // "stall" is a reported status here, never a rejection: the DFU
                // descriptor probe inspects it and treats some stalls as expected.
                status: (result && result.status) || "ok",
                data: toDataView(decodeBase64(result && result.data)),
            }));
        }

        controlTransferOut(setup, data) {
            return rpc("usb.controlTransferOut", {
                handle: this._handle,
                setup: normalizeSetup(setup),
                data: encodeBase64(data),
            }).then((result) => ({
                status: (result && result.status) || "ok",
                bytesWritten: result && result.bytesWritten !== undefined ? result.bytesWritten : 0,
            }));
        }

        transferIn(endpointNumber, length) {
            return rpc("usb.transferIn", { handle: this._handle, endpointNumber, length }).then((result) => ({
                status: (result && result.status) || "ok",
                data: toDataView(decodeBase64(result && result.data)),
            }));
        }

        transferOut(endpointNumber, data) {
            return rpc("usb.transferOut", {
                handle: this._handle,
                endpointNumber,
                data: encodeBase64(data),
            }).then((result) => ({
                status: (result && result.status) || "ok",
                bytesWritten: result && result.bytesWritten !== undefined ? result.bytesWritten : 0,
            }));
        }

        clearHalt(direction, endpointNumber) {
            return rpc("usb.clearHalt", { handle: this._handle, direction, endpointNumber }).then(() => {});
        }

        reset() {
            // Android has no libusb-style port reset, so the native side does a
            // best-effort reopen. A failure must not fail a flash that has
            // otherwise finished, so this always resolves.
            return rpc("usb.reset", { handle: this._handle }).then(
                () => {},
                () => {},
            );
        }

        forget() {
            return rpc("usb.forget", { handle: this._handle }).then(() => {
                devicesByHandle.delete(this._handle);
            });
        }
    }

    function normalizeSetup(setup) {
        return {
            requestType: setup.requestType,
            recipient: setup.recipient,
            request: setup.request,
            value: setup.value,
            index: setup.index,
        };
    }

    function usbDeviceFor(descriptor) {
        const existing = devicesByHandle.get(descriptor.handle);
        if (existing) {
            return existing;
        }
        const device = new USBDeviceImpl(descriptor);
        devicesByHandle.set(descriptor.handle, device);
        return device;
    }

    // -------------------------------------------------------- WebUSB: USB

    class USBImpl extends EventTarget {
        constructor() {
            super();
            this.onconnect = null;
            this.ondisconnect = null;
        }

        getDevices() {
            return rpc("usb.getDevices", {}).then((result) =>
                (result && result.devices ? result.devices : []).map(usbDeviceFor),
            );
        }

        requestDevice(options) {
            const filters = options && Array.isArray(options.filters) ? options.filters : [];
            return rpc("usb.requestDevice", { filters }).then((descriptor) => {
                if (!descriptor) {
                    throw domError("NotFoundError", "No device selected by the user");
                }
                return usbDeviceFor(descriptor);
            });
        }
    }

    const usbImpl = new USBImpl();

    function dispatchUsbConnection(type, device) {
        const event = new global.Event(type);
        // USBConnectionEvent carries the device on the event itself; Betaflight's
        // DFU transport reads e.device rather than e.target.
        event.device = device;
        usbImpl.dispatchEvent(event);
        invoke(usbImpl["on" + type], usbImpl, event);
    }

    onBridgeEvent("usb.connect", (message) => {
        dispatchUsbConnection("connect", usbDeviceFor(message.device));
    });

    onBridgeEvent("usb.disconnect", (message) => {
        const device = devicesByHandle.get(message.device.handle) || usbDeviceFor(message.device);
        device.opened = false;
        devicesByHandle.delete(message.device.handle);
        dispatchUsbConnection("disconnect", device);
    });

    // ------------------------------------------- File System Access API

    /*
     * A WebView has no showSaveFilePicker/showOpenFilePicker, and it silently
     * drops `blob:` downloads triggered by <a download>. Between them those are
     * every save button in both configurators — presets, CLI diffs, blackbox
     * dumps, ESC's dump.hex. These shims route all of it through the Storage
     * Access Framework so the user gets the normal Android save dialog.
     */

    var WRITE_CHUNK = 512 * 1024;

    var MIME_BY_EXTENSION = {
        txt: "text/plain",
        json: "application/json",
        hex: "text/plain",
        csv: "text/csv",
        bbl: "application/octet-stream",
        bfl: "application/octet-stream",
        log: "text/plain",
        xml: "application/xml",
        zip: "application/zip",
    };

    function mimeForName(name, fallback) {
        var match = /\.([A-Za-z0-9]+)$/.exec(name || "");
        if (match) {
            var known = MIME_BY_EXTENSION[match[1].toLowerCase()];
            if (known) {
                return known;
            }
        }
        return fallback || "application/octet-stream";
    }

    async function toBytes(data) {
        // A write() argument can be a WriteParams record, a Blob, a string, or
        // any buffer source.
        if (data && typeof data === "object" && "type" in data && "data" in data && typeof data.type === "string") {
            return toBytes(data.data);
        }
        if (data == null) {
            return new Uint8Array(0);
        }
        if (typeof data === "string") {
            return new TextEncoder().encode(data);
        }
        if (typeof global.Blob !== "undefined" && data instanceof global.Blob) {
            return new Uint8Array(await data.arrayBuffer());
        }
        return toUint8(data);
    }

    class ConfiguratorWritableStream {
        constructor(token) {
            this._token = token;
            this._closed = false;
        }

        async write(data) {
            if (this._closed) {
                throw domError("InvalidStateError", "The file has already been closed");
            }
            const bytes = await toBytes(data);
            // Chunked so a multi-megabyte blackbox log never becomes one
            // oversized bridge message.
            for (let offset = 0; offset < bytes.length; offset += WRITE_CHUNK) {
                await rpc("file.write", {
                    token: this._token,
                    data: encodeBase64(bytes.subarray(offset, offset + WRITE_CHUNK)),
                });
            }
        }

        async close() {
            if (this._closed) {
                return;
            }
            this._closed = true;
            await rpc("file.endSave", { token: this._token });
        }

        async abort() {
            return this.close();
        }

        // Not supported by the streaming SAF writer; present so feature checks
        // and stray calls do not throw.
        async seek() {}
        async truncate() {}
    }

    class ConfiguratorFileHandle {
        constructor(descriptor) {
            this.kind = "file";
            this.name = descriptor.name;
            this._saveToken = descriptor.saveToken || null;
            this._readToken = descriptor.readToken || null;
            this._size = descriptor.size === undefined ? -1 : descriptor.size;
        }

        // The Android save dialog *is* the permission grant, so both of these
        // are already settled by the time a handle exists.
        async queryPermission() {
            return "granted";
        }

        async requestPermission() {
            return "granted";
        }

        async createWritable() {
            if (!this._saveToken) {
                throw domError("NotAllowedError", "This file was opened for reading");
            }
            return new ConfiguratorWritableStream(this._saveToken);
        }

        async getFile() {
            if (!this._readToken) {
                throw domError("NotAllowedError", "This file was opened for writing");
            }
            const parts = [];
            let offset = 0;
            for (;;) {
                const result = await rpc("file.read", {
                    token: this._readToken,
                    offset,
                    length: WRITE_CHUNK,
                });
                const bytes = decodeBase64(result && result.data);
                if (bytes.length === 0) {
                    break;
                }
                parts.push(bytes);
                offset += bytes.length;
                if (this._size >= 0 && offset >= this._size) {
                    break;
                }
            }
            const blob = new global.Blob(parts, { type: mimeForName(this.name) });
            // File carries the name that callers log and display.
            try {
                return new global.File(parts, this.name, { type: blob.type });
            } catch (e) {
                blob.name = this.name;
                return blob;
            }
        }
    }

    function acceptExtensions(options) {
        const types = (options && options.types) || [];
        const mimeTypes = [];
        for (const type of types) {
            for (const key of Object.keys(type.accept || {})) {
                mimeTypes.push(key);
            }
        }
        return mimeTypes;
    }

    async function showSaveFilePicker(options) {
        const opts = options || {};
        const suggestedName = opts.suggestedName || "download";
        const result = await rpc("file.pickSave", {
            suggestedName,
            mimeType: acceptExtensions(opts)[0] || mimeForName(suggestedName),
        });
        if (!result) {
            throw domError("AbortError", "The user aborted a request.");
        }
        return new ConfiguratorFileHandle({ name: result.name, saveToken: result.token });
    }

    async function showOpenFilePicker(options) {
        const result = await rpc("file.pickOpen", { mimeTypes: acceptExtensions(options || {}) });
        if (!result) {
            throw domError("AbortError", "The user aborted a request.");
        }
        return [new ConfiguratorFileHandle({ name: result.name, readToken: result.token, size: result.size })];
    }

    /** Streams a Blob to a user-chosen file. Used by the download interception. */
    async function saveBlob(name, blob) {
        const picked = await rpc("file.pickSave", {
            suggestedName: name,
            mimeType: blob.type || mimeForName(name),
        });
        if (!picked) {
            return false; // the user cancelled; not an error
        }
        const writable = new ConfiguratorWritableStream(picked.token);
        await writable.write(blob);
        await writable.close();
        return true;
    }

    function isInterceptableDownload(anchor) {
        if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute("download")) {
            return false;
        }
        const href = anchor.getAttribute("href") || "";
        return /^(blob:|data:)/i.test(href);
    }

    function handleAnchorDownload(anchor) {
        const href = anchor.getAttribute("href");
        const name = anchor.getAttribute("download") || "download";
        // fetch() is started synchronously, before the caller's
        // URL.revokeObjectURL() runs on the next line.
        const pending = fetch(href).then((response) => response.blob());
        pending
            .then((blob) => saveBlob(name, blob))
            .catch((error) => {
                console.error("[configurator-bridge] download failed", error);
            });
    }

    function installDownloadInterception() {
        const AnchorPrototype = global.HTMLAnchorElement && global.HTMLAnchorElement.prototype;
        if (AnchorPrototype && !AnchorPrototype.__configuratorDownloadPatched) {
            const originalClick = AnchorPrototype.click;
            AnchorPrototype.click = function () {
                // Both configurators save by building a detached-or-appended
                // anchor and calling click() on it, so patching click is what
                // catches every case; a document listener alone would miss the
                // anchors that are never inserted into the page.
                if (isInterceptableDownload(this)) {
                    handleAnchorDownload(this);
                    return;
                }
                return originalClick.apply(this, arguments);
            };
            AnchorPrototype.__configuratorDownloadPatched = true;
        }

        // Genuine user taps on a download link never go through click().
        global.document.addEventListener(
            "click",
            (event) => {
                const anchor = event.target && event.target.closest && event.target.closest("a[download]");
                if (isInterceptableDownload(anchor)) {
                    event.preventDefault();
                    event.stopPropagation();
                    handleAnchorDownload(anchor);
                }
            },
            true,
        );
    }

    // ------------------------------------------------------------- install

    function define(target, name, value) {
        Object.defineProperty(target, name, {
            value,
            writable: false,
            enumerable: true,
            configurable: true,
        });
    }

    // ESC Configurator feature-detects with `'serial' in navigator`, so these
    // have to be real own properties of navigator.
    if (!global.navigator.serial) {
        define(global.navigator, "serial", serialImpl);
    }
    if (!global.navigator.usb) {
        define(global.navigator, "usb", usbImpl);
    }

    // Betaflight prefers these over its <a download> fallback, which gives the
    // user a real "where do you want this?" dialog instead of a silent drop.
    if (!global.showSaveFilePicker) {
        define(global, "showSaveFilePicker", showSaveFilePicker);
    }
    if (!global.showOpenFilePicker) {
        define(global, "showOpenFilePicker", showOpenFilePicker);
    }

    if (global.document) {
        if (global.document.readyState === "loading") {
            global.document.addEventListener("DOMContentLoaded", installDownloadInterception, { once: true });
        } else {
            installDownloadInterception();
        }
    }

    global.SerialPort = SerialPortImpl;
    global.USBDevice = USBDeviceImpl;

    /*
     * Attach to the bridge now rather than lazily on the first call. A board
     * plugged in before the page calls getPorts() would otherwise have its
     * connect event delivered to an onmessage handler that does not exist yet.
     */
    whenBridgeReady().catch((error) => {
        console.warn("[configurator-bridge] native bridge unavailable", error);
    });

    // Surfaced for the native diagnostic screen and for the test harness.
    global.__trustedConfiguratorBridge = {
        rpc,
        handleBridgeMessage,
        serial: serialImpl,
        usb: usbImpl,
        portsByHandle,
        devicesByHandle,
    };
})(typeof globalThis !== "undefined" ? globalThis : this);
