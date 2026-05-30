package net.osmand.wear.complication

import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import net.osmand.wear.R
import net.osmand.wear.SensorDataStore

/**
 * LeanAngleComplicationService - watch face complication showing lean angle.
 *
 * Displays the current lean angle as a SHORT_TEXT complication:
 * - Text: "42R" (angle + direction)
 * - Icon: motorcycle lean angle icon
 * - Updates every 5 seconds
 *
 * This allows riders to see their lean angle directly on the watch face
 * without opening the app — critical for quick glances while riding.
 */
class LeanAngleComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val data = SensorDataStore.getInstance()

        val displayText = data.getLeanAngleDisplay()

        val complicationData = when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(displayText).build(),
                contentDescription = PlainComplicationText.Builder(
                    "Lean angle: $displayText"
                ).build()
            )
                .setMonochromaticImage(
                    androidx.wear.watchface.complications.data.MonochromaticImage.Builder(
                        Icon.createWithResource(this, R.drawable.ic_lean_angle_complication)
                    ).build()
                )
                .build()

            else -> null
        }

        if (complicationData != null) {
            listener.onComplicationData(complicationData)
        } else {
            listener.onComplicationData(ComplicationData.EMPTY)
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("42R").build(),
            contentDescription = PlainComplicationText.Builder("Lean angle").build()
        )
            .setMonochromaticImage(
                androidx.wear.watchface.complications.data.MonochromaticImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_lean_angle_complication)
                ).build()
            )
            .build()
    }
}
