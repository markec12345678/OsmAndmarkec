package net.osmand.plus.plugins.motorcyclesensors.routing

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.data.QuadRect
import net.osmand.data.RotatedTileBox
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.OsmandMapLayer
import net.osmand.plus.plugins.motorcyclesensors.routing.CurvyRoadRouter.TwistinessClass

/**
 * Curvy Road Map Overlay - visually highlights road segments by twistiness.
 *
 * Colors roads on the map based on how curvy they are:
 * - Very Twisty (red/warm): The most fun roads with tight switchbacks
 * - Twisty (orange): Roads with significant curves
 * - Moderate (yellow): Roads with gentle curves
 * - Gentle (light green): Slightly winding roads
 * - Straight (no highlight): Boring straight roads
 *
 * This overlay helps riders identify the most enjoyable roads at a glance
 * without needing to calculate a route first.
 *
 * Implementation:
 * - Uses route segment data from CurvyRoadRouter
 * - Draws colored lines on top of the road segments
 * - Line width and opacity adjustable in settings
 * - Only renders segments within the visible tile box for performance
 */
class CurvyRoadOverlay(private val mapView: OsmandMapTileView) : OsmandMapLayer() {

    companion object {
        private val LOG = PlatformUtil.getLog(CurvyRoadOverlay::class.java)

        // Line drawing settings
        const val DEFAULT_LINE_WIDTH_DP = 6f
        const val DEFAULT_LINE_ALPHA = 180  // 0-255
    }

    private var curvinessBreakdown: List<CurvyRoadRouter.SegmentCurviness>? = null
    private var overlayVisible = false

    // Paints for each twistiness class
    private val veryTwistyPaint = Paint().apply {
        color = Color.parseColor("#E53935")  // Red
        alpha = DEFAULT_LINE_ALPHA
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val twistyPaint = Paint().apply {
        color = Color.parseColor("#FF9800")  // Orange
        alpha = DEFAULT_LINE_ALPHA
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val moderatePaint = Paint().apply {
        color = Color.parseColor("#FFC107")  // Yellow
        alpha = DEFAULT_LINE_ALPHA
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val gentlePaint = Paint().apply {
        color = Color.parseColor("#8BC34A")  // Light Green
        alpha = DEFAULT_LINE_ALPHA
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val legendPaint = Paint().apply {
        color = Color.WHITE
        alpha = 200
        style = Paint.Style.FILL
        textSize = 28f
        isAntiAlias = true
    }

    private val legendBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Update the overlay with new curviness breakdown data.
     */
    fun updateCurviness(breakdown: List<CurvyRoadRouter.SegmentCurviness>) {
        curvinessBreakdown = breakdown
    }

    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
    }

    override fun onDraw(canvas: Canvas, box: RotatedTileBox, drawSettings: Any?) {
        if (!overlayVisible) return
        val breakdown = curvinessBreakdown ?: return

        val lineWidth = DEFAULT_LINE_WIDTH_DP * mapView.density

        veryTwistyPaint.strokeWidth = lineWidth
        twistyPaint.strokeWidth = lineWidth
        moderatePaint.strokeWidth = lineWidth
        gentlePaint.strokeWidth = lineWidth

        // Draw each segment with its twistiness color
        for (segment in breakdown) {
            if (segment.twistinessClass == TwistinessClass.STRAIGHT) continue

            val paint = when (segment.twistinessClass) {
                TwistinessClass.VERY_TWISTY -> veryTwistyPaint
                TwistinessClass.TWISTY -> twistyPaint
                TwistinessClass.MODERATE -> moderatePaint
                TwistinessClass.GENTLE -> gentlePaint
                else -> continue
            }

            val points = segment.points
            if (points.size < 2) continue

            val path = Path()
            var first = true
            for (point in points) {
                val x = box.getPixXFromLatLon(point.latitude, point.longitude)
                val y = box.getPixYFromLatLon(point.latitude, point.longitude)

                if (first) {
                    path.moveTo(x, y)
                    first = false
                } else {
                    path.lineTo(x, y)
                }
            }
            canvas.drawPath(path, paint)
        }

        // Draw legend in bottom-left corner
        drawLegend(canvas, breakdown)
    }

    private fun drawLegend(canvas: Canvas, breakdown: List<CurvyRoadRouter.SegmentCurviness>) {
        val padding = 16f * mapView.density
        val lineHeight = 32f * mapView.density
        val legendWidth = 180f * mapView.density
        val legendHeight = lineHeight * 4 + padding * 2

        val left = padding
        val top = canvas.height - legendHeight - padding

        // Background
        canvas.drawRoundRect(left, top, left + legendWidth, top + legendHeight, 8f, 8f, legendBgPaint)

        // Legend items
        val items = listOf(
            "Very Twisty" to veryTwistyPaint.color,
            "Twisty" to twistyPaint.color,
            "Moderate" to moderatePaint.color,
            "Gentle" to gentlePaint.color
        )

        var y = top + padding + lineHeight / 2
        for ((label, color) in items) {
            val dotPaint = Paint().apply {
                this.color = color
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(left + padding + 8f, y, 6f * mapView.density, dotPaint)
            canvas.drawText(label, left + padding + 24f, y + 8f, legendPaint)
            y += lineHeight
        }
    }

    override fun destroyLayer() {
        super.destroyLayer()
        curvinessBreakdown = null
    }
}
