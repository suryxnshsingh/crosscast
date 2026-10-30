package dev.suryansh.airspeaker.core.audio

/*
 * Kotlin port of the ALAC decoder in Shairport Sync (alac.c), which is:
 *
 *   Copyright (c) 2005 David Hammerton. All rights reserved.
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy of this
 *   software and associated documentation files (the "Software"), to deal in the Software
 *   without restriction, including without limitation the rights to use, copy, modify, merge,
 *   publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
 *   to whom the Software is furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all copies or
 *   substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.
 *
 * Only 16-bit output is supported, which is all we advertise (ss=16). The C version reads past
 * the end of short or corrupt packets; this one treats bytes past the end as zero and throws
 * [AlacException] when the data is clearly broken, so a bad packet can only cost one packet.
 */

/** Decoder parameters, from the `a=fmtp:96 ...` line of the ANNOUNCE SDP. */
data class AlacConfig(
    val frameLength: Int = 352,
    val sampleSize: Int = 16,
    val historyMult: Int = 40,
    val initialHistory: Int = 10,
    val kModifier: Int = 14,
    val channels: Int = 2,
    val sampleRate: Int = 44100,
) {
    companion object {
        /** Parses `96 352 0 16 40 10 14 2 255 0 0 44100` (payload type first). */
        fun fromFmtp(fmtp: String): AlacConfig {
            val v = fmtp.trim().split(Regex("\\s+")).map { it.toInt() }
            require(v.size >= 12) { "short ALAC fmtp: $fmtp" }
            return AlacConfig(
                frameLength = v[1],
                sampleSize = v[3],
                historyMult = v[4],
                initialHistory = v[5],
                kModifier = v[6],
                channels = v[7],
                sampleRate = v[11],
            )
        }
    }
}

class AlacException(message: String) : Exception(message)

class AlacDecoder(private val config: AlacConfig) {
    private val maxFrames = config.frameLength

    private val errorA = IntArray(maxFrames)
    private val errorB = IntArray(maxFrames)
    private val outA = IntArray(maxFrames)
    private val outB = IntArray(maxFrames)
    private val coefA = IntArray(32)
    private val coefB = IntArray(32)

    // Bit reader state.
    private var input = ByteArray(0)
    private var pos = 0
    private var end = 0
    private var bitAcc = 0

    init {
        require(config.sampleSize == 16) { "only 16-bit ALAC is supported, got ${config.sampleSize}" }
        require(config.channels in 1..2) { "unsupported channel count ${config.channels}" }
    }

    /**
     * Decodes one ALAC packet into interleaved stereo 16-bit [output] (mono is duplicated to
     * both channels). Returns the number of frames written.
     */
    fun decode(packet: ByteArray, offset: Int, length: Int, output: ShortArray): Int {
        input = packet
        pos = offset
        end = offset + length
        bitAcc = 0

        val channels = readBits(3)
        val frames = when (channels) {
            0 -> decodeMono(output)
            1 -> decodeStereo(output)
            else -> throw AlacException("unsupported element type $channels")
        }
        if (pos > end + 2) throw AlacException("packet overrun by ${pos - end} bytes")
        return frames
    }

    private fun readHeader(): Header {
        readBits(4)
        readBits(12)
        val hasSize = readBits(1) != 0
        val uncompressedBytes = readBits(2)
        val notCompressed = readBits(1) != 0
        var frames = maxFrames
        if (hasSize) {
            frames = readBits(32)
            if (frames < 0 || frames > maxFrames) throw AlacException("frame count $frames > $maxFrames")
        }
        return Header(frames, uncompressedBytes, notCompressed)
    }

    private class Header(val frames: Int, val uncompressedBytes: Int, val notCompressed: Boolean)

    private fun decodeMono(output: ShortArray): Int {
        val h = readHeader()
        val frames = h.frames
        val readSampleSize = config.sampleSize - h.uncompressedBytes * 8
        if (!h.notCompressed) {
            readBits(8)
            readBits(8)
            val predictionType = readBits(4)
            val quant = readBits(4)
            val riceModifier = readBits(3)
            val coefNum = readBits(5)
            for (i in 0 until coefNum) coefA[i] = readBits(16).toShort().toInt()
            if (h.uncompressedBytes != 0) {
                for (i in 0 until frames) readBits(h.uncompressedBytes * 8) // only used by 24-bit
            }
            riceDecode(errorA, frames, readSampleSize, riceModifier * config.historyMult / 4)
            if (predictionType != 0) throw AlacException("unhandled prediction type $predictionType")
            predictorDecompressFirAdapt(errorA, outA, frames, readSampleSize, coefA, coefNum, quant)
        } else {
            for (i in 0 until frames) outA[i] = signExtend(readBits(config.sampleSize), config.sampleSize)
        }
        for (i in 0 until frames) {
            val s = outA[i].toShort()
            output[i * 2] = s
            output[i * 2 + 1] = s
        }
        return frames
    }

    private fun decodeStereo(output: ShortArray): Int {
        val h = readHeader()
        val frames = h.frames
        val readSampleSize = config.sampleSize - h.uncompressedBytes * 8 + 1
        var shift = 0
        var leftWeight = 0
        if (!h.notCompressed) {
            shift = readBits(8)
            leftWeight = readBits(8)

            val typeA = readBits(4)
            val quantA = readBits(4)
            val riceA = readBits(3)
            val coefNumA = readBits(5)
            for (i in 0 until coefNumA) coefA[i] = readBits(16).toShort().toInt()

            val typeB = readBits(4)
            val quantB = readBits(4)
            val riceB = readBits(3)
            val coefNumB = readBits(5)
            for (i in 0 until coefNumB) coefB[i] = readBits(16).toShort().toInt()

            if (h.uncompressedBytes != 0) {
                for (i in 0 until frames) {
                    readBits(h.uncompressedBytes * 8)
                    readBits(h.uncompressedBytes * 8)
                }
            }

            riceDecode(errorA, frames, readSampleSize, riceA * config.historyMult / 4)
            if (typeA != 0) throw AlacException("unhandled prediction type $typeA on channel 1")
            predictorDecompressFirAdapt(errorA, outA, frames, readSampleSize, coefA, coefNumA, quantA)

            riceDecode(errorB, frames, readSampleSize, riceB * config.historyMult / 4)
            if (typeB != 0) throw AlacException("unhandled prediction type $typeB on channel 2")
            predictorDecompressFirAdapt(errorB, outB, frames, readSampleSize, coefB, coefNumB, quantB)
        } else {
            for (i in 0 until frames) {
                outA[i] = signExtend(readBits(config.sampleSize), config.sampleSize)
                outB[i] = signExtend(readBits(config.sampleSize), config.sampleSize)
            }
        }

        if (leftWeight != 0) {
            for (i in 0 until frames) {
                val midRight = outA[i]
                val difference = outB[i]
                val right = midRight - ((difference * leftWeight) shr shift)
                val left = right + difference
                output[i * 2] = left.toShort()
                output[i * 2 + 1] = right.toShort()
            }
        } else {
            for (i in 0 until frames) {
                output[i * 2] = outA[i].toShort()
                output[i * 2 + 1] = outB[i].toShort()
            }
        }
        return frames
    }

    // ---- Bitstream ---------------------------------------------------------------------------

    private fun byteAt(i: Int): Int = if (i < end) input[i].toInt() and 0xFF else 0

    /** Reads 0 to 16 bits, big-endian. */
    private fun readBits16(bits: Int): Int {
        var result = (byteAt(pos) shl 16) or (byteAt(pos + 1) shl 8) or byteAt(pos + 2)
        result = result shl bitAcc
        result = result and 0x00FFFFFF
        result = result ushr (24 - bits)
        val acc = bitAcc + bits
        pos += acc shr 3
        bitAcc = acc and 7
        return result
    }

    /** Reads 0 to 32 bits, big-endian. */
    private fun readBits(bits: Int): Int {
        var b = bits
        var result = 0
        if (b > 16) {
            b -= 16
            result = readBits16(16) shl b
        }
        return result or readBits16(b)
    }

    private fun readBit(): Int {
        val result = (byteAt(pos) shl bitAcc) shr 7 and 1
        val acc = bitAcc + 1
        pos += acc / 8
        bitAcc = acc % 8
        return result
    }

    private fun unreadBits(bits: Int) {
        val acc = bitAcc - bits
        pos += acc shr 3
        bitAcc = acc and 7
    }

    // ---- Entropy decoding --------------------------------------------------------------------

    private fun entropyDecodeValue(readSampleSize: Int, k: Int, kModifierMask: Int): Int {
        var x = 0
        while (x <= RICE_THRESHOLD && readBit() != 0) x++
        if (x > RICE_THRESHOLD) {
            val value = readBits(readSampleSize)
            x = value and (-1 ushr (32 - readSampleSize))
        } else if (k != 1) {
            val extraBits = readBits(k)
            x *= ((1 shl k) - 1) and kModifierMask
            if (extraBits > 1) x += extraBits - 1 else unreadBits(1)
        }
        return x
    }

    private fun riceDecode(out: IntArray, size: Int, readSampleSize: Int, historyMult: Int) {
        val kModifier = config.kModifier
        val kModifierMask = (1 shl kModifier) - 1
        var history = config.initialHistory
        var signModifier = 0
        var i = 0
        while (i < size) {
            var k = 31 - kModifier - Integer.numberOfLeadingZeros((history shr 9) + 3)
            k = if (k < 0) k + kModifier else kModifier

            var decoded = entropyDecodeValue(readSampleSize, k, -1)
            decoded += signModifier
            var value = (decoded + 1) / 2
            if (decoded and 1 != 0) value = -value
            out[i] = value
            signModifier = 0

            history += decoded * historyMult - ((history * historyMult) shr 9)
            if (decoded > 0xFFFF) history = 0xFFFF

            if (history < 128 && i + 1 < size) {
                signModifier = 1
                k = Integer.numberOfLeadingZeros(history) + (history + 16) / 64 - 24
                val blockSize = entropyDecodeValue(16, k, kModifierMask)
                if (blockSize > 0) {
                    if (i + 1 + blockSize > out.size) throw AlacException("zero run of $blockSize overflows frame")
                    out.fill(0, i + 1, i + 1 + blockSize)
                    i += blockSize
                }
                if (blockSize > 0xFFFF) signModifier = 0
                history = 0
            }
            i++
        }
    }

    // ---- Prediction --------------------------------------------------------------------------

    private fun predictorDecompressFirAdapt(
        error: IntArray,
        out: IntArray,
        size: Int,
        readSampleSize: Int,
        coef: IntArray,
        coefNum: Int,
        quant: Int,
    ) {
        if (size == 0) return
        out[0] = error[0]
        if (coefNum == 0) {
            if (size > 1) System.arraycopy(error, 1, out, 1, size - 1)
            return
        }
        if (coefNum == 0x1F) {
            for (i in 0 until size - 1) out[i + 1] = signExtend(out[i] + error[i + 1], readSampleSize)
            return
        }

        // Warm-up samples.
        for (i in 0 until minOf(coefNum, size - 1)) out[i + 1] = signExtend(out[i] + error[i + 1], readSampleSize)

        var base = 0
        for (i in coefNum + 1 until size) {
            var sum = 0
            var errorVal = error[i]
            for (j in 0 until coefNum) sum += (out[base + coefNum - j] - out[base]) * coef[j]
            var outVal = (1 shl (quant - 1)) + sum
            outVal = outVal shr quant
            outVal = outVal + out[base] + errorVal
            out[base + coefNum + 1] = signExtend(outVal, readSampleSize)

            if (errorVal > 0) {
                var p = coefNum - 1
                while (p >= 0 && errorVal > 0) {
                    var v = out[base] - out[base + coefNum - p]
                    val sign = Integer.signum(v)
                    coef[p] = (coef[p] - sign).toShort().toInt()
                    v *= sign
                    errorVal -= (v shr quant) * (coefNum - p)
                    p--
                }
            } else if (errorVal < 0) {
                var p = coefNum - 1
                while (p >= 0 && errorVal < 0) {
                    var v = out[base] - out[base + coefNum - p]
                    val sign = -Integer.signum(v)
                    coef[p] = (coef[p] - sign).toShort().toInt()
                    v *= sign
                    errorVal -= (v shr quant) * (coefNum - p)
                    p--
                }
            }
            base++
        }
    }

    private companion object {
        const val RICE_THRESHOLD = 8

        fun signExtend(value: Int, bits: Int): Int = (value shl (32 - bits)) shr (32 - bits)
    }
}
