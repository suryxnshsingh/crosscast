package dev.suryansh.airspeaker.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.palette.graphics.Palette

/** Colours and backdrop derived from the cover art, computed once per artwork. */
class ArtworkStyle(val accent: Color, val backdrop: ImageBitmap?) {
    companion object {
        val Default = ArtworkStyle(AirSpeakerColors.Accent, null)

        fun from(artwork: Bitmap?): ArtworkStyle {
            if (artwork == null) return Default
            return ArtworkStyle(accentOf(artwork), blurredBackdrop(artwork))
        }

        /** The most vibrant colour, lifted until it reads clearly on a dark background. */
        private fun accentOf(artwork: Bitmap): Color {
            val palette = Palette.from(artwork).maximumColorCount(16).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
            var color = swatch?.let { Color(it.rgb) } ?: return AirSpeakerColors.Accent
            var steps = 0
            while (color.luminance() < 0.3f && steps++ < 8) color = lerp(color, Color.White, 0.2f)
            return color
        }

        /**
         * A heavily blurred copy for the background, made in software from a 40 px thumbnail so
         * drawing it full-screen costs nothing on the Chromecast's GPU.
         */
        private fun blurredBackdrop(artwork: Bitmap): ImageBitmap {
            val size = 40
            val small = Bitmap.createScaledBitmap(artwork, size, size, true)
            val pixels = IntArray(size * size)
            small.getPixels(pixels, 0, size, 0, 0, size, size)
            if (small !== artwork) small.recycle()
            repeat(3) { boxBlur(pixels, size, radius = 3) }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
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
