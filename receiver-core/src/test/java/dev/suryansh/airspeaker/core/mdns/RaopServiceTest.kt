package dev.suryansh.airspeaker.core.mdns

import dev.suryansh.airspeaker.core.DeviceIds
import dev.suryansh.airspeaker.core.ReceiverIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RaopServiceTest {
    private val id = "0A1B2C3D4E5F"

    @Test
    fun `service name is id at friendly name`() {
        assertEquals("0A1B2C3D4E5F@Room Speaker", RaopService.serviceName(ReceiverIdentity(id, "Room Speaker")))
    }

    @Test
    fun `service name fits one DNS label without splitting characters`() {
        val long = "Suryansh’s very long living-room speaker name 🔊🔊🔊"
        val name = RaopService.serviceName(ReceiverIdentity(id, long))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 63)
        assertTrue(name.startsWith("$id@Suryansh’s"))
        assertTrue(long.startsWith(name.removePrefix("$id@")))
    }

    @Test
    fun `txt records match classic Shairport Sync with UDP only`() {
        val txt = RaopService.txtRecords
        assertEquals(
            listOf("txtvers", "ch", "cn", "et", "ek", "sr", "ss", "tp", "vn", "vs", "md", "am", "da", "sv", "sf", "fv", "pw"),
            txt.keys.toList(),
        )
        assertEquals("0,1", txt["cn"])
        assertEquals("0,1", txt["et"])
        assertEquals("UDP", txt["tp"])
        assertEquals("0,1,2", txt["md"])
        assertEquals("false", txt["pw"])
    }

    @Test
    fun `generated device ids are valid locally administered unicast MACs`() {
        val random = Random(42)
        repeat(100) {
            val generated = DeviceIds.generate(random)
            assertTrue(DeviceIds.isValid(generated))
            val first = generated.substring(0, 2).toInt(16)
            assertEquals(0x02, first and 0x03)
        }
    }

    @Test
    fun `hardware address decodes the id`() {
        assertArrayEquals(
            byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F),
            ReceiverIdentity(id, "x").hardwareAddress,
        )
    }
}
