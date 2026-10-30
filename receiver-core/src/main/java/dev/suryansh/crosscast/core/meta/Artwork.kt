package dev.suryansh.crosscast.core.meta

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object Artwork {
    /** Decodes JPEG/PNG cover art, scaled down so neither side exceeds [maxSize]. Null if undecodable. */
    fun decode(bytes: ByteArray, maxSize: Int = 1080): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxSize) return decoded
        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}
