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
 * GForceComplicationService - watch face complication showing G-force.
 *
 * Displays the current total G-force as a SHORT_TEXT complication:
 * - Text: "1.23G"
 * - Icon: G-force gauge icon
 * - Updates every 5 seconds
 */
class GForceComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val data = SensorDataStore.getInstance()

        val displayText = data.getGForceDisplay()

        val complicationData = when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(displayText).build(),
                contentDescription = PlainComplicationText.Builder(
                    "G-force: $displayText"
                ).build()
            )
                .setMonochromaticImage(
                    androidx.wear.watchface.complications.data.MonochromaticImage.Builder(
                        Icon.createWithResource(this, R.drawable.ic_gforce_complication)
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
            text = PlainComplicationText.Builder("1.2G").build(),
            contentDescription = PlainComplicationText.Builder("G-force").build()
        )
            .setMonochromaticImage(
                androidx.wear.watchface.complications.data.MonochromaticImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_gforce_complication)
                ).build()
            )
            .build()
    }
}
