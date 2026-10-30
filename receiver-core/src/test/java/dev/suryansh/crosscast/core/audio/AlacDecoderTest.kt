package dev.suryansh.crosscast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixtures in `src/test/resources/alac` were made with ffmpeg from a 1.45 s mix of sweep, pink
 * noise, digital silence and a very quiet tone (see docs/notes.md → ALAC fixtures):
 * `level0` is ffmpeg's verbatim (uncompressed) mode and `level2` its adaptive-prediction mode.
 * ALAC is lossless, so both must decode to exactly `source.pcm`.
 */
class AlacDecoderTest {
    // ffmpeg's encoder writes 4096-frame packets with the same Rice parameters RAOP uses.
    private val config = AlacConfig(frameLength = 4096)

    @Test
    fun `adaptive prediction decodes bit-exact`() = assertDecodesToSource("level2")

    @Test
    fun `verbatim frames decode bit-exact`() = assertDecodesToSource("level0")

    @Test
    fun `fmtp line from ANNOUNCE parses`() {
        assertEquals(
            AlacConfig(frameLength = 352, sampleSize = 16, historyMult = 40, initialHistory = 10, kModifier = 14, channels = 2, sampleRate = 44100),
            AlacConfig.fromFmtp("96 352 0 16 40 10 14 2 255 0 0 44100"),
        )
    }

    @Test
    fun `garbage never escapes as anything but AlacException`() {
        val decoder = AlacDecoder(AlacConfig())
        val out = ShortArray(352 * 2)
        val random = java.util.Random(1)
        repeat(2000) {
            val packet = ByteArray(random.nextInt(64) + 1).also(random::nextBytes)
            try {
                decoder.decode(packet, 0, packet.size, out)
            } catch (e: AlacException) {
                // expected for most inputs
            } catch (e: Exception) {
                fail("unexpected ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    private fun assertDecodesToSource(name: String) {
        val packets = resource("$name.packets")
        val sizes = String(resource("$name.sizes")).lines().filter { it.isNotBlank() }.map { it.trim().toInt() }
        val expected = ByteBuffer.wrap(resource("source.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        val decoder = AlacDecoder(config)
        val out = ShortArray(config.frameLength * 2)
        var offset = 0
        var sample = 0
        for ((index, size) in sizes.withIndex()) {
            val frames = decoder.decode(packets, offset, size, out)
            for (i in 0 until frames * 2) {
                val want = expected.get(sample + i)
                if (out[i] != want) fail("$name packet $index sample ${sample + i}: got ${out[i]}, want $want")
            }
            sample += frames * 2
            offset += size
        }
        assertEquals(expected.limit(), sample)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/alac/$name")) { "missing fixture $name" }.readBytes()
}
