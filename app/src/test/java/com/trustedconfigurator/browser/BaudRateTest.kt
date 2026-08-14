package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.drivers.Ch34xBaud
import com.trustedconfigurator.browser.drivers.FtdiBaud
import com.trustedconfigurator.browser.drivers.SerialDriverException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The first hand-written version of both encoders was wrong, in ways nothing
 * short of real hardware would have caught. These pin them to the published
 * values so a future edit cannot quietly reintroduce the same class of bug.
 */
class FtdiBaudTest {

    @Test
    fun `115200 is the classic divisor 26 with no fraction`() {
        val divisor = FtdiBaud.encode(115200)
        assertEquals(26, divisor.value)
        assertEquals(0, divisor.index)
    }

    @Test
    fun `9600 is divisor 312 and a half`() {
        // 0x4138 == 312 | 0x4000; the 0x4000 is the half-step sub-divisor.
        val divisor = FtdiBaud.encode(9600)
        assertEquals(0x4138, divisor.value)
        assertEquals(0, divisor.index)
    }

    @Test
    fun `921600 is divisor 3 and a quarter`() {
        val divisor = FtdiBaud.encode(921600)
        assertEquals(0x8003, divisor.value)
        assertEquals(0, divisor.index)
    }

    @Test
    fun `the two fast rates use their dedicated divisors`() {
        assertEquals(0, FtdiBaud.encode(3_000_000).value)
        assertEquals(1, FtdiBaud.encode(2_000_000).value)
    }

    @Test
    fun `the sub-divisor table is not sequential`() {
        // 1 means an eighth and 4 means a half. Encoding them in numeric order
        // is the mistake that silently shifts the baud rate.
        assertEquals(0xC000, FtdiBaud.encode(3_000_000 * 8 / 25).value and 0xC000)
    }

    @Test
    fun `the index bit is bit 0, not bit 8`() {
        // 14400 lands on sub-divisor 3 (0.375), one of the four that set wIndex.
        // Writing 0x100 there — or OR-ing in an interface number, which is what
        // the first version did — lands on the wrong field and shifts the rate.
        val divisor = FtdiBaud.encode(14400)
        assertEquals(208, divisor.value)
        assertEquals(1, divisor.index)
    }

    @Test
    fun `wIndex is only ever 0 or 1 across the common rates`() {
        listOf(1_000_000, 500_000, 230_400, 115_200, 57_600, 38_400, 19_200, 14_400, 9_600, 4_800, 2_400, 1_200)
            .forEach { baud ->
                val index = FtdiBaud.encode(baud).index
                assertEquals("wIndex out of range for baud $baud", true, index == 0 || index == 1)
            }
    }

    @Test
    fun `rates the chip cannot reach are refused`() {
        assertThrows(SerialDriverException::class.java) { FtdiBaud.encode(0) }
        assertThrows(SerialDriverException::class.java) { FtdiBaud.encode(-1) }
        assertThrows(SerialDriverException::class.java) { FtdiBaud.encode(4_000_000) }
        assertThrows(SerialDriverException::class.java) { FtdiBaud.encode(100) }
    }
}

class Ch34xBaudTest {

    @Test
    fun `115200 divides exactly, so no prescaler shifting happens`() {
        // 1532620800 / 115200 == 13304 exactly; 0x10000 - 13304 == 0xCC08.
        // Prescaler stays at 3, and bit 7 is set to stop the chip buffering.
        val divisor = Ch34xBaud.encode(115200)
        assertEquals(0xCC83, divisor.divisorRegister)
        assertEquals(0x08, divisor.factorRegister)
    }

    @Test
    fun `9600 needs one prescaler step`() {
        // 1532620800 / 9600 == 159648, which exceeds 0xFFF0, so it shifts once
        // and the prescaler drops from 3 to 2.
        val divisor = Ch34xBaud.encode(9600)
        assertEquals(0xB282, divisor.divisorRegister)
        assertEquals(0x0C, divisor.factorRegister)
    }

    @Test
    fun `921600 uses the chip's dedicated constant`() {
        // The generic path cannot express this rate at all.
        val divisor = Ch34xBaud.encode(921600)
        assertEquals(0xF387, divisor.divisorRegister)
        assertEquals(0x00, divisor.factorRegister)
    }

    @Test
    fun `the anti-buffering bit is always set`() {
        listOf(1200, 9600, 19200, 57600, 115200, 230400, 460800, 921600).forEach { baud ->
            val divisor = Ch34xBaud.encode(baud)
            assertEquals(
                "bit 7 of the prescaler byte must be set for baud $baud",
                0x80,
                divisor.divisorRegister and 0x80,
            )
        }
    }

    @Test
    fun `every common rate encodes into 16 bits`() {
        listOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600).forEach { baud ->
            val divisor = Ch34xBaud.encode(baud)
            assertEquals("register must fit 16 bits for baud $baud", 0, divisor.divisorRegister and 0xFFFF.inv())
            assertEquals("factor must fit 8 bits for baud $baud", 0, divisor.factorRegister and 0xFF.inv())
        }
    }

    @Test
    fun `rates the chip cannot reach are refused`() {
        assertThrows(SerialDriverException::class.java) { Ch34xBaud.encode(0) }
        assertThrows(SerialDriverException::class.java) { Ch34xBaud.encode(-1) }
    }
}
