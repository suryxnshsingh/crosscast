package dev.suryansh.crosscast.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/** The cover art prepared once per track for the moving background. */
class ArtworkStyle private constructor(
    /** A tiny, saturated, heavily blurred copy of the cover. */
    val colors: Bitmap,
    /** How much to darken the background (0..1) so white text stays legible over bright covers. */
    val scrim: Float,
) {
    companion object {
        private const val SIZE = 48
        private const val SATURATION = 1.5f

        /** Cool graphite, for tracks without artwork. */
        val Default: ArtworkStyle by lazy { create(placeholder()) }

        fun from(artwork: Bitmap?): ArtworkStyle = if (artwork == null) Default else create(artwork)

        private fun create(source: Bitmap): ArtworkStyle {
            val small = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
            val px = IntArray(SIZE * SIZE)
            small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            if (small !== source) small.recycle()
            saturate(px, SATURATION)
            repeat(3) { boxBlur(px, SIZE, radius = 4) }
            // Darker scrims for brighter covers: what's left behind the text stays under ~35 % luminance.
            val luminance = px.sumOf { luma(it).toDouble() }.toFloat() / px.size / 255f
            val scrim = (0.18f + 0.5f * luminance).coerceIn(0.2f, 0.62f)
            return ArtworkStyle(Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888), scrim)
        }

        private fun placeholder(): Bitmap {
            val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                    intArrayOf(0xFF3E4A62.toInt(), 0xFF4A3F63.toInt(), 0xFF1F232B.toInt()), null, Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
            return bitmap
        }

        private fun luma(p: Int): Int =
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000

        private fun saturate(px: IntArray, amount: Float) {
            for (i in px.indices) {
                val p = px[i]
                val l = luma(p)
                fun ch(c: Int) = (l + (c - l) * amount).toInt().coerceIn(0, 255)
                px[i] = (0xFF shl 24) or (ch((p shr 16) and 0xFF) shl 16) or (ch((p shr 8) and 0xFF) shl 8) or ch(p and 0xFF)
            }
        }

        private fun boxBlur(px: IntArray, size: Int, radius: Int) {
            val tmp = IntArray(px.size)
            fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
                for (a in 0 until size) for (b in 0 until size) {
                    var r = 0
                    var g = 0
                    var bl = 0
                    var n = 0
                    for (k in -radius..radius) {
                        val c = (b + k).coerceIn(0, size - 1)
                        val p = if (horizontal) src[a * size + c] else src[c * size + a]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        bl += p and 0xFF
                        n++
                    }
                    val i = if (horizontal) a * size + b else b * size + a
                    dst[i] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
                }
            }
            pass(px, tmp, horizontal = true)
            pass(tmp, px, horizontal = false)
        }
    }
}
