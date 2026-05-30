package net.osmand.plus.plugins.motorcyclesensors.safety

import net.osmand.Location
import net.osmand.PlatformUtil
import net.osmand.plus.settings.backend.OsmandSettings
import net.osmand.plus.settings.backend.preferences.CommonPreference

/**
 * CustomSmsTemplate - user-configurable emergency SMS message.
 *
 * Allows users to customize the emergency SMS sent by CrashAlertDialog.
 * Supports template variables that are filled in at send time:
 *
 * Template variables:
 * - {name} - Rider's name
 * - {lat} - Latitude of crash location
 * - {lon} - Longitude of crash location
 * - {maps_url} - Google Maps link to crash location
 * - {speed} - Speed at time of crash (km/h)
 * - {gforce} - G-force at impact
 * - {time} - Time of crash
 * - {date} - Date of crash
 * - {address} - Approximate address (if available)
 *
 * Default template:
 * "EMERGENCY: {name} may have crashed! Location: {maps_url} Speed: {speed} km/h Impact: {gforce}G"
 *
 * The template is stored as a plugin preference and can be edited
 * in the motorcycle plugin settings.
 */
class CustomSmsTemplate {

    companion object {
        private val LOG = PlatformUtil.getLog(CustomSmsTemplate::class.java)

        // Default template
        const val DEFAULT_TEMPLATE = "EMERGENCY: {name} may have crashed! " +
            "Location: {maps_url} Speed: {speed} km/h Impact: {gforce}G " +
            "Time: {time} Date: {date}"

        // Template variable patterns
        val VARIABLE_PATTERN = Regex("""\{(\w+)\}""")
    }

    /**
     * Fill in a template with actual values.
     *
     * @param template The template string with {variable} placeholders
     * @param name Rider's name
     * @param location Crash location (may be null)
     * @param speedKmh Speed at crash
     * @param gForce G-force at impact
     * @return The filled-in message ready to send
     */
    fun fillTemplate(
        template: String,
        name: String = "Rider",
        location: Location? = null,
        speedKmh: Float = 0f,
        gForce: Float = 0f
    ): String {
        var result = template

        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val mapsUrl = if (lat != 0.0 || lon != 0.0) {
            "https://maps.google.com/?q=$lat,$lon"
        } else {
            "Location unavailable"
        }

        val now = java.util.Calendar.getInstance()
        val time = String.format("%02d:%02d", now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        val date = String.format("%04d-%02d-%02d",
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH))

        // Replace all template variables
        result = result.replace("{name}", name)
        result = result.replace("{lat}", String.format("%.6f", lat))
        result = result.replace("{lon}", String.format("%.6f", lon))
        result = result.replace("{maps_url}", mapsUrl)
        result = result.replace("{speed}", String.format("%.0f", speedKmh))
        result = result.replace("{gforce}", String.format("%.2f", gForce))
        result = result.replace("{time}", time)
        result = result.replace("{date}", date)
        result = result.replace("{address}", "Address lookup unavailable")

        return result
    }

    /**
     * Get list of available template variables with descriptions.
     */
    fun getAvailableVariables(): List<TemplateVariable> {
        return listOf(
            TemplateVariable("name", "Rider's name"),
            TemplateVariable("lat", "Latitude of crash location"),
            TemplateVariable("lon", "Longitude of crash location"),
            TemplateVariable("maps_url", "Google Maps link to location"),
            TemplateVariable("speed", "Speed at crash (km/h)"),
            TemplateVariable("gforce", "G-force at impact"),
            TemplateVariable("time", "Time of crash (HH:MM)"),
            TemplateVariable("date", "Date of crash (YYYY-MM-DD)"),
            TemplateVariable("address", "Approximate address")
        )
    }

    /**
     * Validate a template string.
     * Returns list of invalid variable names found in the template.
     */
    fun validateTemplate(template: String): List<String> {
        val validVariables = getAvailableVariables().map { it.name }.toSet()
        val foundVariables = VARIABLE_PATTERN.findAll(template).map { it.groupValues[1] }.toList()
        return foundVariables.filter { it !in validVariables }
    }

    data class TemplateVariable(
        val name: String,
        val description: String
    )
}
