package dev.suryansh.crosscast.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small symbols in the spirit of SF Symbols, drawn on a 24-unit grid. Tint them when drawing. */
object Glyphs {
    /** Crosscast's mark at small sizes: waves cast along each arm of an X (see tools/brand/brand.py). */
    val Cast = glyph(
        "cast",
        stroked = listOf(
            "M16.35,13.5 A4.6,4.6 0 0 1 13.5,16.35",
            "M10.5,16.35 A4.6,4.6 0 0 1 7.65,13.5",
            "M7.65,10.5 A4.6,4.6 0 0 1 10.5,7.65",
            "M13.5,7.65 A4.6,4.6 0 0 1 16.35,10.5",
            "M19.79,15.63 A8.6,8.6 0 0 1 15.63,19.79",
            "M8.37,19.79 A8.6,8.6 0 0 1 4.21,15.63",
            "M4.21,8.37 A8.6,8.6 0 0 1 8.37,4.21",
            "M15.63,4.21 A8.6,8.6 0 0 1 19.79,8.37",
        ),
        filled = listOf("M10,12 A2,2 0 1 0 14,12 A2,2 0 1 0 10,12 Z"),
        strokeWidth = 1.9f,
    )

    val Speaker = glyph(
        "speaker",
        filled = listOf("M3,9.2 H6.6 L11.2,5 V19 L6.6,14.8 H3 Z"),
        stroked = listOf("M14.6,9.4 A3.6,3.6 0 0 1 14.6,14.6", "M17,7 A7,7 0 0 1 17,17"),
        strokeWidth = 1.9f,
    )

    val SpeakerMuted = glyph(
        "speaker_muted",
        filled = listOf("M3,9.2 H6.6 L11.2,5 V19 L6.6,14.8 H3 Z"),
        stroked = listOf("M15,9.5 L20,14.5", "M20,9.5 L15,14.5"),
        strokeWidth = 1.9f,
    )

    val Pause = glyph(
        "pause",
        filled = listOf(
            "M7.5,5 H9.5 A1,1 0 0 1 10.5,6 V18 A1,1 0 0 1 9.5,19 H7.5 A1,1 0 0 1 6.5,18 V6 A1,1 0 0 1 7.5,5 Z",
            "M14.5,5 H16.5 A1,1 0 0 1 17.5,6 V18 A1,1 0 0 1 16.5,19 H14.5 A1,1 0 0 1 13.5,18 V6 A1,1 0 0 1 14.5,5 Z",
        ),
    )

    val PlayPause = glyph(
        "play_pause",
        filled = listOf(
            "M3,6.6 V17.4 L11,12 Z",
            "M14,6 H15.6 V18 H14 Z",
            "M18.4,6 H20 V18 H18.4 Z",
        ),
        rounded = true,
    )

    val Forward = glyph("forward", filled = listOf("M2.5,6.5 V17.5 L11,12 Z", "M12.5,6.5 V17.5 L21,12 Z"), rounded = true)

    val Backward = glyph("backward", filled = listOf("M21.5,6.5 V17.5 L13,12 Z", "M11.5,6.5 V17.5 L3,12 Z"), rounded = true)

    val MusicNote = glyph(
        "music_note",
        filled = listOf(
            "M5.2,18 A3.3,3.3 0 1 0 11.8,18 A3.3,3.3 0 1 0 5.2,18 Z",
            "M9.8,18 V4.4 L19.6,2 V6.6 L11.8,8.6 V18 Z",
        ),
        rounded = true,
    )

    private fun glyph(
        name: String,
        filled: List<String> = emptyList(),
        stroked: List<String> = emptyList(),
        strokeWidth: Float = 0f,
        rounded: Boolean = false,
    ): ImageVector {
        val white = SolidColor(Color.White)
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in filled) {
            builder.addPath(
                addPathNodes(d),
                fill = white,
                // A thin stroke in the fill colour rounds off sharp triangle tips.
                stroke = if (rounded) white else null,
                strokeLineWidth = if (rounded) 1.2f else 0f,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        for (d in stroked) {
            builder.addPath(addPathNodes(d), stroke = white, strokeLineWidth = strokeWidth, strokeLineCap = StrokeCap.Round)
        }
        return builder.build()
    }
}
