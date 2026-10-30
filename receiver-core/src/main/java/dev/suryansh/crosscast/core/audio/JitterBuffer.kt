package dev.suryansh.crosscast.core.audio

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Decoded packets keyed by RTP sequence number, waiting for their play time. Network threads
 * [put]; the player thread [take]s in sequence order. About 6 s of 352-frame packets fit.
 */
class JitterBuffer(
    private val framesPerPacket: Int,
    private val capacity: Int = 1024,
    private val time: () -> Long = System::nanoTime,
) {
    private class Slot(val pcm: ShortArray) {
        var seq = -1
        var rtp = 0
        var frames = 0
    }

    class Head(val seq: Int, val rtp: Int, val receivedAtNs: Long)

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val slots = Array(capacity) { Slot(ShortArray(framesPerPacket * 2)) }
    private var head: Head? = null

    fun put(seq: Int, rtp: Int, pcm: ShortArray, frames: Int) = lock.withLock {
        val slot = slots[seq % capacity]
        pcm.copyInto(slot.pcm, 0, 0, frames * 2)
        slot.seq = seq
        slot.rtp = rtp
        slot.frames = frames
        val h = head
        if (h == null || seqBefore(seq, h.seq)) {
            head = Head(seq, rtp, time())
            arrived.signalAll()
        }
    }

    /** Copies packet [seq] into [out] and frees its slot. Returns its frame count, or -1 if missing. */
    fun take(seq: Int, out: ShortArray): Int = lock.withLock {
        val slot = slots[seq % capacity]
        if (slot.seq != seq) return -1
        slot.pcm.copyInto(out, 0, 0, slot.frames * 2)
        slot.seq = -1
        slot.frames
    }

    /** The earliest packet received since the last [clear], waiting up to [timeoutMs] for one. */
    fun awaitHead(timeoutMs: Long): Head? = lock.withLock {
        if (head == null) arrived.await(timeoutMs, TimeUnit.MILLISECONDS)
        head
    }

    fun clear() = lock.withLock {
        slots.forEach { it.seq = -1 }
        head = null
    }

    companion object {
        /** True if 16-bit sequence number [a] comes before [b], allowing for wrap-around. */
        fun seqBefore(a: Int, b: Int): Boolean = ((a - b).toShort()) < 0
    }
}
