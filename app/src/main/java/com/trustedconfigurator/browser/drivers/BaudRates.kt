package com.trustedconfigurator.browser.drivers

/**
 * Baud-rate divisor encoding for the vendor UART bridges.
 *
 * Pulled out of the drivers because it is the part most likely to be wrong and
 * the only part that can be tested without hardware: it is pure integer maths
 * with known-good values published for common rates. The first version of both
 * of these was transcribed by hand and both were wrong.
 *
 * Encodings follow mik3y's usb-serial-for-android (MIT), which matches the
 * Linux ch341 and ftdi_sio drivers.
 */

/** Register words for a CH340/CH341 baud rate. */
data class Ch34xDivisor(val divisorRegister: Int, val factorRegister: Int)

object Ch34xBaud {

    private const val BAUDBASE_FACTOR = 1_532_620_800L
    private const val BAUDBASE_DIVMAX = 3L

    /**
     * @return the words for registers 0x1312 and 0x0F2C.
     * @throws SerialDriverException when the chip cannot reach [baudRate].
     */
    fun encode(baudRate: Int): Ch34xDivisor {
        if (baudRate <= 0) {
            throw SerialDriverException("CH34x cannot produce baud rate $baudRate")
        }

        val factor: Long
        var divisor: Long

        if (baudRate == 921600) {
            // The generic path cannot express this one; the chip has a dedicated
            // constant for it.
            divisor = 7
            factor = 0xF300
        } else {
            var candidate = BAUDBASE_FACTOR / baudRate
            divisor = BAUDBASE_DIVMAX
            while (candidate > 0xFFF0 && divisor > 0) {
                candidate = candidate shr 3
                divisor--
            }
            if (candidate > 0xFFF0) {
                throw SerialDriverException("CH34x cannot produce baud rate $baudRate")
            }
            factor = 0x10000 - candidate
        }

        // Without bit 7 the CH341A holds bytes until its buffer fills, which
        // stalls any request/response protocol.
        divisor = divisor or 0x0080

        return Ch34xDivisor(
            divisorRegister = ((factor and 0xFF00) or divisor).toInt(),
            factorRegister = (factor and 0xFF).toInt(),
        )
    }
}

/** `wValue`/`wIndex` for an FTDI SET_BAUD_RATE request. */
data class FtdiDivisor(val value: Int, val index: Int)

object FtdiBaud {

    /**
     * The FT232R derives its rate from a 3 MHz clock divided by a divisor with
     * three fractional bits. Two of those bits live in the top of `wValue` and
     * one in the bottom of `wIndex`, and the mapping is not sequential — 1 means
     * an eighth, 4 means a half — so it has to be a table.
     */
    fun encode(baudRate: Int): FtdiDivisor {
        if (baudRate <= 0 || baudRate > 3_000_000) {
            throw SerialDriverException("FTDI cannot produce baud rate $baudRate")
        }
        if (baudRate >= 2_500_000) return FtdiDivisor(0, 0) // divisor 0 == 3 MBaud
        if (baudRate >= 1_750_000) return FtdiDivisor(1, 0) // divisor 1 == 2 MBaud

        var eighths = (24_000_000 shl 1) / baudRate
        eighths = (eighths + 1) shr 1 // round to the nearest eighth
        val subDivisor = eighths and 0x07
        val divisor = eighths shr 3
        if (divisor > 0x3FFF) {
            throw SerialDriverException("FTDI cannot produce baud rate $baudRate")
        }

        var value = divisor and 0x3FFF
        var index = 0
        when (subDivisor) {
            0 -> Unit                                   // /0
            4 -> value = value or 0x4000                // /0.5
            2 -> value = value or 0x8000                // /0.25
            1 -> value = value or 0xC000                // /0.125
            3 -> index = 1                              // /0.375
            5 -> { value = value or 0x4000; index = 1 } // /0.625
            6 -> { value = value or 0x8000; index = 1 } // /0.75
            7 -> { value = value or 0xC000; index = 1 } // /0.875
        }
        return FtdiDivisor(value, index)
    }
}
