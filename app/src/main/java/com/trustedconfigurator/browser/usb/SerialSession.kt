package com.trustedconfigurator.browser.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.trustedconfigurator.browser.drivers.ModemSignals
import com.trustedconfigurator.browser.drivers.SerialDriver
import com.trustedconfigurator.browser.drivers.SerialDriverFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One open Web Serial port: a claimed USB-serial device plus the reader thread
 * that feeds `port.readable`.
 */
class SerialSession(
    val handle: String,
    val origin: String,
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {

    private val driver: SerialDriver = SerialDriverFactory.create(device, connection)
    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null

    val driverName: String get() = driver.name
    val supportsControlSignals: Boolean get() = driver.supportsControlSignals
    fun claimedInterfaces(): List<Int> = driver.claimedInterfaces()

    fun open(baudRate: Int, dataBits: Int, stopBits: Int, parity: String, onData: (ByteArray) -> Unit) {
        driver.open()
        driver.setParameters(baudRate, dataBits, stopBits, parity)
        // Betaflight's MSP handshake needs DTR asserted on boards that gate their
        // CDC output on it; a fresh port therefore starts with DTR/RTS raised.
        runCatching { driver.setControlSignals(dataTerminalReady = true, requestToSend = true, breakSignal = null) }

        running.set(true)
        readerThread = Thread({ readLoop(onData) }, "serial-reader-$handle").apply {
            isDaemon = true
            start()
        }

        TransferLog.record(
            TransferKind.EVENT,
            origin,
            device.productName ?: device.deviceName,
            "Serial port opened via $driverName at $baudRate baud",
        )
    }

    private fun readLoop(onData: (ByteArray) -> Unit) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (running.get()) {
            val count = try {
                driver.read(buffer, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                if (running.get()) {
                    TransferLog.record(
                        TransferKind.ERROR,
                        origin,
                        device.productName ?: device.deviceName,
                        "Read failed: ${e.message}",
                    )
                }
                break
            }
            if (count > 0) {
                val chunk = buffer.copyOf(count)
                TransferLog.record(
                    TransferKind.BULK_IN,
                    origin,
                    device.productName ?: device.deviceName,
                    "serial read",
                    count,
                )
                onData(chunk)
            }
        }
    }

    fun write(data: ByteArray): Int {
        val written = driver.write(data, WRITE_TIMEOUT_MS)
        TransferLog.record(
            TransferKind.BULK_OUT,
            origin,
            device.productName ?: device.deviceName,
            "serial write",
            written,
        )
        return written
    }

    fun setSignals(dataTerminalReady: Boolean?, requestToSend: Boolean?, breakSignal: Boolean?) {
        driver.setControlSignals(dataTerminalReady, requestToSend, breakSignal)
        TransferLog.record(
            TransferKind.CONTROL_OUT,
            origin,
            device.productName ?: device.deviceName,
            "setSignals DTR=$dataTerminalReady RTS=$requestToSend break=$breakSignal",
        )
    }

    fun getSignals(): ModemSignals = driver.readSignals()

    fun close() {
        running.set(false)
        readerThread?.let { thread ->
            thread.interrupt()
            // Bounded: the reader can be parked in a bulkTransfer for up to
            // READ_TIMEOUT_MS, and close() must not block the bridge thread.
            runCatching { thread.join(READ_TIMEOUT_MS * 2L) }
        }
        readerThread = null
        runCatching { driver.close() }
        runCatching { connection.close() }
        TransferLog.record(
            TransferKind.EVENT,
            origin,
            device.productName ?: device.deviceName,
            "Serial port closed",
        )
    }

    private companion object {
        const val READ_BUFFER_BYTES = 4096
        const val READ_TIMEOUT_MS = 100
        const val WRITE_TIMEOUT_MS = 2000
    }
}
