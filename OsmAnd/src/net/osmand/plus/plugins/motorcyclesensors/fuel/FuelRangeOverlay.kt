package net.osmand.plus.plugins.motorcyclesensors.fuel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import net.osmand.PlatformUtil
import net.osmand.data.LatLon
import net.osmand.data.RotatedTileBox
import net.osmand.plus.views.OsmandMapTileView
import net.osmand.plus.views.layers.BaseMapLayer

/**
 * FuelRangeOverlay - draws the remaining fuel range as a semi-transparent
 * polygon on the map.
 *
 * The polygon shows the estimated maximum distance the motorcycle can
 * travel before running out of fuel, centered at the current position.
 *
 * Visual design:
 * - Green polygon: >50% fuel remaining (safe range)
 * - Yellow polygon: 25-50% fuel (moderate range)
 * - Orange polygon: 15-25% fuel (low fuel)
 * - Red polygon: <15% fuel (reserve / critical)
 * - Pulsing animation when below reserve threshold
 *
 * The polygon is drawn as a filled shape with a semi-transparent fill
 * and a solid border. The border color matches the fill but is opaque.
 */
class FuelRangeOverlay(private val mapTileView: OsmandMapTileView) :
    BaseMapLayer(mapTileView.context) {

    companion object {
        private val LOG = PlatformUtil.getLog(FuelRangeOverlay::class.java)
    }

    private val fillPaint = Paint()
    private val borderPaint = Paint()
    private val textPaint = Paint()
    private val path = Path()

    // Current polygon points (in lat/lon)
    private var polygonPoints: List<LatLon> = emptyList()
    private var fuelLevelPercent: Float = 100f
    private var rangeKm: Float = 0f
    private var isVisible = false

    init {
        fillPaint.style = Paint.Style.FILL
        fillPaint.isAntiAlias = true

        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = 2f
        borderPaint.isAntiAlias = true

        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 28f
        textPaint.isAntiAlias = true
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
    }

    /**
     * Update the fuel range polygon data.
     *
     * @param points List of LatLon points forming the range polygon
     * @param fuelLevel Current fuel level percentage (0-100)
     * @param range Remaining range in kilometers
     */
    fun updateRange(points: List<LatLon>, fuelLevel: Float, range: Float) {
        polygonPoints = points
        fuelLevelPercent = fuelLevel
        rangeKm = range

        // Update colors based on fuel level
        val (fillColor, borderColor) = getColorsForFuelLevel(fuelLevel)

        fillPaint.color = fillColor
        borderPaint.color = borderColor
    }

    /**
     * Show or hide the fuel range overlay.
     */
    fun setOverlayVisible(visible: Boolean) {
        isVisible = visible
        mapTileView.refreshMap()
    }

    override fun onDraw(canvas: Canvas, box: RotatedTileBox, drawSettings: Any?) {
        if (!isVisible || polygonPoints.isEmpty()) return

        // Convert LatLon points to screen coordinates
        path.reset()
        var first = true
        for (latLon in polygonPoints) {
            val point = PointF()
            mapTileView.mapView.mapPositionHelper.getPointFromLatLon(
                latLon.latitude, latLon.longitude, box, point
            )

            // Account for map rotation and zoom
            val px = box.getPixXFromLatLon(latLon.latitude, latLon.longitude)
            val py = box.getPixYFromLatLon(latLon.latitude, latLon.longitude)

            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
        }

        // Draw filled polygon
        canvas.drawPath(path, fillPaint)

        // Draw border
        canvas.drawPath(path, borderPaint)

        // Draw range text at center
        if (rangeKm > 0) {
            val centerPx = box.centerPixelX
            val centerPy = box.centerPixelY
            canvas.drawText("${"%.0f".format(rangeKm)} km", centerPx, centerPy - 20f, textPaint)
        }
    }

    /**
     * Get fill and border colors based on fuel level.
     */
    private fun getColorsForFuelLevel(fuelLevel: Float): Pair<Int, Int> {
        return when {
            fuelLevel > 50 -> {
                // Green: plenty of fuel
                Pair(Color.argb(40, 76, 175, 80), Color.argb(180, 76, 175, 80))
            }
            fuelLevel > 25 -> {
                // Yellow: moderate fuel
                Pair(Color.argb(40, 255, 193, 7), Color.argb(180, 255, 193, 7))
            }
            fuelLevel > 15 -> {
                // Orange: low fuel
                Pair(Color.argb(40, 255, 152, 0), Color.argb(180, 255, 152, 0))
            }
            else -> {
                // Red: reserve / critical
                Pair(Color.argb(50, 244, 67, 54), Color.argb(200, 244, 67, 54))
            }
        }
    }

    override fun destroyLayer() {
        isVisible = false
    }
}
