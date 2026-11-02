package nodomain.freeyourgadget.gadgetbridge.devices.aicare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AicareBroadcastScaleProtocolTest {
    private val address = "01:FE:00:33:B5:6F"

    /**
     * Android gives the first two bytes of the advertisement payload as the (little-endian)
     * manufacturer id, and the rest as the value.
     */
    private fun parse(fullPayloadHex: String, deviceAddress: String = address): AicareBroadcastScaleProtocol.Reading? {
        val full = fullPayloadHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val manufacturerId = (full[0].toInt() and 0xff) or ((full[1].toInt() and 0xff) shl 8)
        return AicareBroadcastScaleProtocol.parse(manufacturerId, full.copyOfRange(2, full.size), deviceAddress)
    }

    @Test
    fun stableReadingFromFiteloScale() {
        // captured from a Fitelo smart scale ("tzc"), display showed 69.25 kg
        val reading = parse("c0a01b0d138800022501fe0033b56f")
        assertNotNull(reading)
        assertEquals(0xa0, reading!!.counter)
        assertEquals(69.25f, reading.weightKg, 0.001f)
        assertTrue(reading.stable)
        // the scale always reports 5000, which is not a body impedance
        assertNull(reading.impedanceOhm)
    }

    @Test
    fun unstableReading() {
        val reading = parse("c0c61b0d138800022401fe0033b56f")
        assertNotNull(reading)
        assertFalse(reading!!.stable)
        assertEquals(69.25f, reading.weightKg, 0.001f)
    }

    @Test
    fun secondCapturedWeight() {
        val reading = parse("c0c41adb138800022501fe0033b56f")
        assertEquals(68.75f, reading!!.weightKg, 0.001f)
    }

    @Test
    fun plausibleImpedanceIsKept() {
        // synthetic: 0x01f4 = 500 ohm
        val reading = parse("c0a01b0d01f400022501fe0033b56f")
        assertEquals(500, reading!!.impedanceOhm)
    }

    @Test
    fun units() {
        // synthetic, flag bits 3-4 select the unit
        // lb: raw / 10, 0x05f6 = 1526 -> 152.6 lb
        assertEquals(152.6f * 0.45359237f, parse("c0a005f6138800023501fe0033b56f")!!.weightKg, 0.01f)
        // jin: raw / 100, 0x3616 = 13846 -> 138.46 jin
        assertEquals(138.46f * 0.5f, parse("c0a03616138800022d01fe0033b56f")!!.weightKg, 0.01f)
        // st:lb: high byte stones, low byte tenths of a pound: 10 st 12.6 lb
        assertEquals((10 * 14 + 12.6f) * 0.45359237f, parse("c0a00a7e138800023d01fe0033b56f")!!.weightKg, 0.01f)
    }

    @Test
    fun rejectsOtherPayloads() {
        // wrong device type in bytes 6-7
        assertNull(parse("c0a01b0d138800032501fe0033b56f"))
        // MAC in the payload does not match the device address
        assertNull(parse("c0a01b0d138800022501fe0033b56f", "01:FE:00:33:B5:70"))
        // too short
        assertNull(parse("c0a01b0d1388000225"))
        // zero weight
        assertNull(parse("c0a00000138800022501fe0033b56f"))
    }
}
