package dev.suryansh.airspeaker.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Apple Music–style moving background: three copies of the blurred cover art, turning slowly at
 * different speeds around different points, crossfading when the artwork changes.
 *
 * They're composited in software into a small bitmap that is scaled up to the full screen, so the
 * Chromecast's GPU draws one image per frame instead of three blended full-screen layers. A faint
 * grain on top breaks up the banding a smooth 8-bit gradient would otherwise show.
 */
@Composable
fun FluidBackground(style: ArtworkStyle, motion: Boolean, modifier: Modifier = Modifier) {
    val renderer = remember { FluidRenderer() }
    var frame by remember { mutableIntStateOf(0) }
    remember(style) {
        val nowS = System.nanoTime() / 1e9
        renderer.show(style, nowS, crossfade = motion)
        renderer.render(if (motion) nowS else STILL_TIME_S)
    }
    if (motion) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= FRAME_INTERVAL_NS) {
                        last = now
                        renderer.render(now / 1e9)
                        frame++
                    }
                }
            }
        }
    }
    val grain = remember { ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated)) }

    Box(
        modifier.drawBehind {
            frame // each new frame redraws, without recomposing
            drawImage(
                renderer.current,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Low, // bilinear: smooth when scaling up
            )
            drawRect(grain)
        }
    )
}

private class FluidRenderer {
    private class Layer(val cx: Float, val cy: Float, val degreesPerS: Float, val startDegrees: Float, val alpha: Float, val mirrored: Boolean)

    private val buffers = Array(2) { Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888) }
    private val images = buffers.map { it.asImageBitmap() }
    private val canvases = buffers.map { Canvas(it) }
    private var front = 0
    val current: ImageBitmap get() = images[front]

    private var style: ArtworkStyle? = null
    private var previous: ArtworkStyle? = null
    private var crossfadeStartS = 0.0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    // Each layer is a square big enough to cover the screen at any angle around its pivot.
    private val side = hypot(W.toFloat(), H.toFloat()) * 1.6f
    private val square = RectF(-side / 2, -side / 2, side / 2, side / 2)
    private val textSide = Paint().apply {
        // A little darker on the right, behind the text.
        shader = LinearGradient(W * 0.35f, 0f, W.toFloat(), 0f, 0x00000000, 0x40000000, Shader.TileMode.CLAMP)
    }

    fun show(next: ArtworkStyle, nowS: Double, crossfade: Boolean) {
        if (next === style) return
        previous = if (crossfade) style else null
        crossfadeStartS = nowS
        style = next
    }

    fun render(nowS: Double) {
        val target = 1 - front
        val c = canvases[target]
        val s = style ?: return
        val mix = previous?.let { ((nowS - crossfadeStartS) / CROSSFADE_S).toFloat().coerceIn(0f, 1f) } ?: 1f
        if (mix >= 1f) previous = null

        c.drawColor(0xFF000000.toInt())
        val p = previous
        if (p != null) {
            drawLayers(c, p, nowS)
            c.saveLayerAlpha(null, (mix * 255).roundToInt())
            drawLayers(c, s, nowS)
            c.restore()
        } else {
            drawLayers(c, s, nowS)
        }
        val scrim = if (p != null) p.scrim + (s.scrim - p.scrim) * mix else s.scrim
        scrimPaint.color = ((scrim * 255).roundToInt() shl 24)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), scrimPaint)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), textSide)
        front = target
    }

    private fun drawLayers(c: Canvas, s: ArtworkStyle, nowS: Double) {
        for (layer in LAYERS) {
            c.save()
            c.translate(W * layer.cx, H * layer.cy)
            c.rotate(((layer.startDegrees + layer.degreesPerS * nowS) % 360).toFloat())
            if (layer.mirrored) c.scale(-1f, 1f)
            paint.alpha = (layer.alpha * 255).roundToInt()
            c.drawBitmap(s.colors, null, square, paint)
            c.restore()
        }
    }

    private companion object {
        val LAYERS = listOf(
            Layer(cx = 0.5f, cy = 0.5f, degreesPerS = 3f, startDegrees = 0f, alpha = 1f, mirrored = false),
            Layer(cx = 0.3f, cy = 0.65f, degreesPerS = -4f, startDegrees = 140f, alpha = 0.65f, mirrored = true),
            Layer(cx = 0.72f, cy = 0.3f, degreesPerS = 5f, startDegrees = 250f, alpha = 0.5f, mirrored = false),
        )
    }
}

/** 128 px of faint grey noise, tiled over the screen. */
private fun grainTile(): ImageBitmap {
    val size = 128
    val random = Random(7)
    val px = IntArray(size * size) {
        val v = random.nextInt(256)
        (GRAIN_ALPHA shl 24) or (v shl 16) or (v shl 8) or v
    }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The software canvas, 16:9. Scaled ×10 to 1920×1080 the blur hides every pixel edge. */
private const val W = 192
private const val H = 108
private const val CROSSFADE_S = 1.5
private const val FRAME_INTERVAL_NS = 33_000_000L // ~30 fps is plenty for motion this slow
private const val STILL_TIME_S = 17.0 // the pose used when motion is off (screenshot tests)
private const val GRAIN_ALPHA = 8
