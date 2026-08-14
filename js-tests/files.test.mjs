import assert from "node:assert/strict";
import test from "node:test";
import { createHarness } from "./harness.mjs";

/**
 * The File System Access shims.
 *
 * A WebView defines showSaveFilePicker but it never resolves, and it drops
 * `blob:` downloads, so without these every save button in both configurators is
 * a silent no-op — presets, CLI diffs, blackbox dumps, ESC's dump.hex.
 */

function saveHarness() {
    const harness = createHarness();
    const written = [];
    harness
        .on("file.pickSave", (args) => ({ token: "w1", name: args.suggestedName }))
        .on("file.write", (args) => {
            written.push(Buffer.from(args.data, "base64"));
            return {};
        })
        .on("file.endSave", () => ({}));
    harness.written = () => Buffer.concat(written);
    return harness;
}

test("showSaveFilePicker and showOpenFilePicker are installed", () => {
    const { sandbox } = createHarness();
    assert.equal(typeof sandbox.showSaveFilePicker, "function");
    assert.equal(typeof sandbox.showOpenFilePicker, "function");
});

test("a host implementation that already exists is replaced, not deferred to", async () => {
    /*
     * The Android WebView defines showSaveFilePicker and showOpenFilePicker
     * already. showOpenFilePicker works; showSaveFilePicker resolves never and
     * throws nothing, so every save button silently did nothing while the
     * polyfill politely stood aside. Detecting the API by existence is the wrong
     * test — it has to be replaced unconditionally.
     */
    const deadBuiltIn = () => new Promise(() => {});
    const harness = createHarness({
        preexisting: { showSaveFilePicker: deadBuiltIn, showOpenFilePicker: deadBuiltIn },
    });
    harness.on("file.pickSave", (args) => ({ token: "w1", name: args.suggestedName }));

    assert.notEqual(harness.sandbox.showSaveFilePicker, deadBuiltIn, "save picker was not replaced");
    assert.notEqual(harness.sandbox.showOpenFilePicker, deadBuiltIn, "open picker was not replaced");

    // And the replacement actually reaches native rather than hanging.
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "diff.txt" });
    assert.equal(handle.name, "diff.txt");
    assert.equal(harness.callsFor("file.pickSave").length, 1);
});

test("a save handle reports the granted permissions Betaflight checks", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "preset.txt" });

    assert.equal(handle.name, "preset.txt");
    assert.equal(handle.kind, "file");
    // FileSystem.verifyPermission() throws unless both of these say "granted".
    assert.equal(await handle.queryPermission({ mode: "readwrite" }), "granted");
    assert.equal(await handle.requestPermission({ mode: "readwrite" }), "granted");
});

test("writing a string through createWritable reaches native byte-exact", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "diff.txt" });

    const writable = await handle.createWritable();
    await writable.write("# diff\nset foo = 1\n");
    await writable.close();

    assert.equal(harness.written().toString("utf8"), "# diff\nset foo = 1\n");
    assert.equal(harness.callsFor("file.endSave").length, 1);
});

test("writing a Blob works, which is what the blackbox stream hands over", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "log.bbl" });

    const writable = await handle.createWritable();
    // FileSystem.writeChunck() passes a Blob per chunk.
    await writable.write(new Blob([new Uint8Array([1, 2, 3])]));
    await writable.write(new Blob([new Uint8Array([4, 5])]));
    await writable.close();

    assert.deepEqual(Array.from(harness.written()), [1, 2, 3, 4, 5]);
});

test("a WriteParams record is unwrapped rather than serialised as an object", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "x.txt" });

    const writable = await handle.createWritable();
    await writable.write({ type: "write", data: "hello" });
    await writable.close();

    assert.equal(harness.written().toString("utf8"), "hello");
});

test("a large write is split into chunks and reassembles exactly", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "big.bbl" });

    // Bigger than one chunk, so a blackbox log cannot become one oversized message.
    const payload = new Uint8Array(1_500_000).map((_, index) => index % 251);
    const writable = await handle.createWritable();
    await writable.write(payload);
    await writable.close();

    assert.ok(harness.callsFor("file.write").length > 1, "expected the write to be chunked");
    assert.ok(harness.written().equals(Buffer.from(payload)));
});

test("writing after close is refused instead of silently dropped", async () => {
    const harness = saveHarness();
    const handle = await harness.sandbox.showSaveFilePicker({ suggestedName: "x.txt" });
    const writable = await handle.createWritable();
    await writable.close();

    await assert.rejects(() => writable.write("late"), (error) => {
        assert.equal(error.name, "InvalidStateError");
        return true;
    });
});

test("cancelling the save dialog raises AbortError, as the spec requires", async () => {
    const harness = createHarness();
    harness.on("file.pickSave", () => null);

    // FileSystem.js treats AbortError as "user changed their mind", anything
    // else as a real failure worth reporting.
    await assert.rejects(() => harness.sandbox.showSaveFilePicker({ suggestedName: "x.txt" }), (error) => {
        assert.equal(error.name, "AbortError");
        return true;
    });
});

test("the picker is told a sensible mime type for the suggested name", async () => {
    const harness = saveHarness();
    await harness.sandbox.showSaveFilePicker({ suggestedName: "preset.json" });
    assert.equal(harness.lastCall("file.pickSave").args.mimeType, "application/json");

    await harness.sandbox.showSaveFilePicker({ suggestedName: "dump.hex" });
    assert.equal(harness.lastCall("file.pickSave").args.mimeType, "text/plain");
});

test("an explicit accept type overrides the guess", async () => {
    const harness = saveHarness();
    await harness.sandbox.showSaveFilePicker({
        suggestedName: "log",
        types: [{ description: "Text", accept: { "text/csv": [".csv"] } }],
    });
    assert.equal(harness.lastCall("file.pickSave").args.mimeType, "text/csv");
});

test("showOpenFilePicker reads a file back through getFile()", async () => {
    const harness = createHarness();
    const contents = Buffer.from("set foo = 1\n", "utf8");
    harness
        .on("file.pickOpen", () => ({ token: "r1", name: "preset.txt", size: contents.length }))
        .on("file.read", (args) => ({
            data: contents.subarray(args.offset, args.offset + args.length).toString("base64"),
        }));

    const [handle] = await harness.sandbox.showOpenFilePicker({});
    assert.equal(handle.name, "preset.txt");

    const file = await handle.getFile();
    assert.equal(await file.text(), "set foo = 1\n");
});

test("a file larger than one chunk is read fully", async () => {
    const harness = createHarness();
    const contents = Buffer.alloc(1_200_000, 7);
    harness
        .on("file.pickOpen", () => ({ token: "r1", name: "big.bbl", size: contents.length }))
        .on("file.read", (args) => ({
            data: contents.subarray(args.offset, args.offset + args.length).toString("base64"),
        }));

    const [handle] = await harness.sandbox.showOpenFilePicker({});
    const file = await handle.getFile();

    assert.equal(file.size, contents.length);
    assert.ok(harness.callsFor("file.read").length > 1, "expected the read to be chunked");
});

test("a read stops at end of file when the size is unknown", async () => {
    const harness = createHarness();
    const contents = Buffer.from("abc", "utf8");
    harness
        .on("file.pickOpen", () => ({ token: "r1", name: "x.txt", size: -1 }))
        .on("file.read", (args) => ({
            data: contents.subarray(args.offset, args.offset + args.length).toString("base64"),
        }));

    const [handle] = await harness.sandbox.showOpenFilePicker({});
    assert.equal(await (await handle.getFile()).text(), "abc");
});

test("cancelling the open dialog raises AbortError", async () => {
    const harness = createHarness();
    harness.on("file.pickOpen", () => null);

    await assert.rejects(() => harness.sandbox.showOpenFilePicker({}), (error) => {
        assert.equal(error.name, "AbortError");
        return true;
    });
});

test("a read handle refuses to be written and a save handle refuses to be read", async () => {
    const harness = saveHarness();
    harness.on("file.pickOpen", () => ({ token: "r1", name: "x.txt", size: 0 }));

    const saveHandle = await harness.sandbox.showSaveFilePicker({ suggestedName: "x.txt" });
    await assert.rejects(() => saveHandle.getFile());

    const [openHandle] = await harness.sandbox.showOpenFilePicker({});
    await assert.rejects(() => openHandle.createWritable());
});
