package dev.suryansh.airspeaker.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * A rounded rectangle with Apple's continuous ("squircle") corners: curvature eases in along
 * the edge instead of jumping from straight to circular. Bézier coefficients are the widely
 * used fit of iOS 7's corners; each corner reaches [CORNER_EXTENT] × radius along its edges.
 */
class ContinuousRoundedShape(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(with(density) { radius.toPx() }, minOf(size.width, size.height) / 2 / CORNER_EXTENT)
        val path = Path()
        // Each corner: the point where it meets the incoming edge, approached along `u`, leaving along `v`.
        val corners = listOf(
            Corner(Offset(size.width, 0f), Offset(1f, 0f), Offset(0f, 1f)),
            Corner(Offset(size.width, size.height), Offset(0f, 1f), Offset(-1f, 0f)),
            Corner(Offset(0f, size.height), Offset(-1f, 0f), Offset(0f, -1f)),
            Corner(Offset(0f, 0f), Offset(0f, -1f), Offset(1f, 0f)),
        )
        path.moveTo(CORNER_EXTENT * r, 0f) // where the top-left corner meets the top edge
        for (c in corners) {
            // a = distance back along the incoming edge, b = distance out along the outgoing one.
            fun p(a: Float, b: Float) = c.at - c.u * (a * r) + c.v * (b * r)
            p(CORNER_EXTENT, 0f).let { path.lineTo(it.x, it.y) }
            path.cubic(p(1.08849323f, 0f), p(0.86840689f, 0f), p(0.66993427f, 0.06549600f))
            p(0.63149399f, 0.07491100f).let { path.lineTo(it.x, it.y) }
            path.cubic(p(0.37282392f, 0.16905899f), p(0.16906013f, 0.37282401f), p(0.07491176f, 0.63149399f))
            p(0.06549569f, 0.66993493f).let { path.lineTo(it.x, it.y) }
            path.cubic(p(0f, 0.86840701f), p(0f, 1.08849299f), p(0f, CORNER_EXTENT))
        }
        path.close()
        return Outline.Generic(path)
    }

    private class Corner(val at: Offset, val u: Offset, val v: Offset)

    private fun Path.cubic(c1: Offset, c2: Offset, to: Offset) = cubicTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y)

    override fun equals(other: Any?) = other is ContinuousRoundedShape && other.radius == radius
    override fun hashCode() = radius.hashCode()

    private companion object {
        const val CORNER_EXTENT = 1.52866483f
    }
}
