package com.trustedconfigurator.browser

import com.trustedconfigurator.browser.bridge.Base64Codec
import com.trustedconfigurator.browser.bridge.BridgeErrorName
import com.trustedconfigurator.browser.bridge.DeviceFilter
import com.trustedconfigurator.browser.bridge.Protocol
import com.trustedconfigurator.browser.bridge.matchesOrEmpty
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Base64CodecTest {

    @Test
    fun `round trips every byte value`() {
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, Base64Codec.decode(Base64Codec.encode(data)))
    }

    @Test
    fun `matches the reference encoding including padding`() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("TQ==", Base64Codec.encode("M".toByteArray()))
        assertEquals("TVo=", Base64Codec.encode("MZ".toByteArray()))
        assertEquals("JE08", Base64Codec.encode("\$M<".toByteArray()))
    }

    @Test
    fun `decodes what a browser btoa produced, padding and all`() {
        assertArrayEquals("M".toByteArray(), Base64Codec.decode("TQ=="))
        assertArrayEquals("MZ".toByteArray(), Base64Codec.decode("TVo="))
        assertArrayEquals(ByteArray(0), Base64Codec.decode(null))
        assertArrayEquals(ByteArray(0), Base64Codec.decode(""))
    }

    @Test
    fun `round trips a firmware-sized payload`() {
        // A real Betaflight target is a few hundred KB; a mismatch anywhere here
        // would corrupt a flash rather than fail it.
        val data = Random(7).nextBytes(512 * 1024)
        assertArrayEquals(data, Base64Codec.decode(Base64Codec.encode(data)))
    }

    @Test
    fun `ignores whitespace rather than truncating a transfer`() {
        assertArrayEquals("MZ".toByteArray(), Base64Codec.decode("T\nVo="))
    }
}

class ProtocolFramingTest {

    @Test
    fun `parses a well formed request`() {
        val request = Protocol.parseRequest("""{"id":42,"op":"serial.open","args":{"baudRate":115200}}""")
        assertEquals(42L, request!!.id)
        assertEquals("serial.open", request.op)
        assertEquals(115200, request.args.getInt("baudRate"))
    }

    @Test
    fun `defaults missing args to an empty object`() {
        val request = Protocol.parseRequest("""{"id":1,"op":"serial.getPorts"}""")
        assertEquals(0, request!!.args.length())
    }

    @Test
    fun `rejects malformed payloads instead of throwing`() {
        assertNull(Protocol.parseRequest(null))
        assertNull(Protocol.parseRequest(""))
        assertNull(Protocol.parseRequest("not json"))
        assertNull(Protocol.parseRequest("""{"op":"serial.getPorts"}"""))
        assertNull(Protocol.parseRequest("""{"id":1}"""))
    }

    @Test
    fun `success carries the id and result`() {
        val json = JSONObject(Protocol.success(7, JSONObject().put("driver", "CDC-ACM")))
        assertEquals(7, json.getInt("id"))
        assertTrue(json.getBoolean("ok"))
        assertEquals("CDC-ACM", json.getJSONObject("result").getString("driver"))
    }

    @Test
    fun `failure carries a DOMException name the configurators understand`() {
        val json = JSONObject(Protocol.failure(9, BridgeErrorName.SECURITY, "not authorised"))
        assertEquals(9, json.getInt("id"))
        assertFalse(json.getBoolean("ok"))
        assertEquals("SecurityError", json.getJSONObject("error").getString("name"))
        assertEquals("not authorised", json.getJSONObject("error").getString("message"))
    }

    @Test
    fun `events are distinguishable from responses`() {
        val json = JSONObject(Protocol.event(Protocol.Event.SERIAL_DATA, mapOf("handle" to "serial_1", "data" to "JE08")))
        assertEquals("serial.data", json.getString("event"))
        assertEquals("serial_1", json.getString("handle"))
        assertFalse(json.has("id"))
        // The polyfill routes on the presence of "event", so a response must never carry one.
        assertFalse(JSONObject(Protocol.success(1, JSONObject())).has("event"))
    }
}

class DeviceFilterTest {

    @Test
    fun `reads the Web Serial spelling of a filter`() {
        val filters = Protocol.parseFilters(
            JSONArray("""[{"usbVendorId":1155,"usbProductId":22336}]"""),
        )
        assertEquals(listOf(DeviceFilter(1155, 22336)), filters)
    }

    @Test
    fun `reads the WebUSB spelling of a filter`() {
        val filters = Protocol.parseFilters(JSONArray("""[{"vendorId":1155,"productId":57105}]"""))
        assertEquals(listOf(DeviceFilter(1155, 57105)), filters)
    }

    @Test
    fun `treats a vendor only filter as matching any product`() {
        val filters = Protocol.parseFilters(JSONArray("""[{"vendorId":1155}]"""))
        assertTrue(filters.matchesOrEmpty(1155, 22336))
        assertTrue(filters.matchesOrEmpty(1155, 57105))
        assertFalse(filters.matchesOrEmpty(1027, 24577))
    }

    @Test
    fun `an empty filter list offers every device rather than none`() {
        // Betaflight refreshes its VID/PID table over the network and ESC
        // Configurator calls requestPort() with no arguments at all, so an empty
        // list has to mean "show everything the user could pick".
        assertTrue(emptyList<DeviceFilter>().matchesOrEmpty(0x1234, 0x5678))
        assertTrue(Protocol.parseFilters(null).matchesOrEmpty(0x1234, 0x5678))
        assertTrue(Protocol.parseFilters(JSONArray("[]")).matchesOrEmpty(0x1234, 0x5678))
    }

    @Test
    fun `skips entries that carry no usable identifier`() {
        assertEquals(emptyList<DeviceFilter>(), Protocol.parseFilters(JSONArray("""[{"foo":1},"x"]""")))
    }
}
