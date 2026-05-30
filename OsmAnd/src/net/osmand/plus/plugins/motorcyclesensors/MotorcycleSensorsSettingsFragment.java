package net.osmand.plus.plugins.motorcyclesensors;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;
import androidx.preference.EditTextPreference;

/**
 * Settings fragment for the Motorcycle Sensors Plugin.
 *
 * Allows configuration of:
 * - Sensor display (lean angle, G-force widgets)
 * - Sensor filter coefficients (smoothing)
 * - Ride recording (GPX telemetry logging, auto-start)
 * - Curvy road routing preference
 * - Crash detection sensitivity
 * - Emergency contacts (up to 3 phone numbers)
 */
public class MotorcycleSensorsSettingsFragment extends BaseSettingsFragment {

        private static final int REQUEST_SMS_PERMISSION = 1001;

        private final MotorcycleSensorsPlugin plugin = PluginsHelper.requirePlugin(MotorcycleSensorsPlugin.class);

        @Override
        protected void setupPreferences() {
                // Sensor display
                setupLeanAngleToggle();
                setupGForceToggle();
                setupAccelFilter();
                setupGyroFilter();

                // Recording
                setupRecordSensorData();
                setupAutoStartRecording();

                // Routing
                setupCurvyRoads();
                setupAvoidMotorway();

                // Crash detection
                setupCrashDetection();
                setupCrashSensitivity();
                setupEmergencyContact(plugin.EMERGENCY_CONTACT_NUMBER, R.string.motorcycle_emergency_contact_desc);
                setupEmergencyContact(plugin.EMERGENCY_CONTACT_2, R.string.motorcycle_emergency_contact_2_desc);
                setupEmergencyContact(plugin.EMERGENCY_CONTACT_3, R.string.motorcycle_emergency_contact_3_desc);

                // Auto 3D Map
                setupAuto3DMap();
                setupAuto3DSpeedThreshold();
                setupAuto3DElevationAngle();

                // Weather routing
                setupWeatherRouting();

                // Track Day
                setupTrackDay();

                // Group Riding
                setupGroupRiding();

                // Wear OS
                setupWearOs();

                // OBD2
                setupOBD2();
                setupOBD2Device();

                // Fuel Range
                setupFuelRange();
                setupFuelTankCapacity();
                setupFuelConsumption();
                setupFuelLevel();

                // Track Day UI
                setupTrackDayUI();
                setupTrackDaySectors();
        }

        // ===== Sensor Display =====

        private void setupLeanAngleToggle() {
                SwitchPreferenceEx leanAngle = findPreference(plugin.SHOW_LEAN_ANGLE_WIDGET.getId());
                if (leanAngle != null) {
                        leanAngle.setDescription(R.string.motorcycle_lean_angle_enable_desc);
                }
        }

        private void setupGForceToggle() {
                SwitchPreferenceEx gforce = findPreference(plugin.SHOW_GFORCE_WIDGET.getId());
                if (gforce != null) {
                        gforce.setDescription(R.string.motorcycle_gforce_enable_desc);
                }
        }

        private void setupAccelFilter() {
                Float[] entryValues = {0.2f, 0.4f, 0.6f, 0.8f, 0.9f, 1.0f};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        if (entryValues[i] >= 1.0f) {
                                entries[i] = getString(R.string.motorcycle_filter_no_smoothing);
                        } else {
                                entries[i] = getString(R.string.motorcycle_filter_smoothing_level,
                                                (int) ((1.0f - entryValues[i]) * 100));
                        }
                }

                ListPreferenceEx accelFilter = findPreference(plugin.ACCEL_FILTER_COEFFICIENT.getId());
                if (accelFilter != null) {
                        accelFilter.setEntries(entries);
                        accelFilter.setEntryValues(entryValues);
                        accelFilter.setDescription(R.string.motorcycle_accel_filter_desc);
                }
        }

        private void setupGyroFilter() {
                Float[] entryValues = {0.2f, 0.4f, 0.6f, 0.7f, 0.9f, 1.0f};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        if (entryValues[i] >= 1.0f) {
                                entries[i] = getString(R.string.motorcycle_filter_no_smoothing);
                        } else {
                                entries[i] = getString(R.string.motorcycle_filter_smoothing_level,
                                                (int) ((1.0f - entryValues[i]) * 100));
                        }
                }

                ListPreferenceEx gyroFilter = findPreference(plugin.GYRO_FILTER_COEFFICIENT.getId());
                if (gyroFilter != null) {
                        gyroFilter.setEntries(entries);
                        gyroFilter.setEntryValues(entryValues);
                        gyroFilter.setDescription(R.string.motorcycle_gyro_filter_desc);
                }
        }

        // ===== Recording =====

        private void setupRecordSensorData() {
                SwitchPreferenceEx recordData = findPreference(plugin.RECORD_SENSOR_DATA.getId());
                if (recordData != null) {
                        recordData.setDescription(R.string.motorcycle_record_sensor_data_desc);
                }
        }

        private void setupAutoStartRecording() {
                SwitchPreferenceEx autoStart = findPreference(plugin.AUTO_START_RECORDING.getId());
                if (autoStart != null) {
                        autoStart.setDescription(R.string.motorcycle_auto_start_recording_desc);
                }
        }

        // ===== Routing =====

        private void setupCurvyRoads() {
                SwitchPreferenceEx curvyRoads = findPreference(plugin.PREFER_CURVY_ROADS.getId());
                if (curvyRoads != null) {
                        curvyRoads.setDescription(R.string.motorcycle_curvy_roads_desc);
                }
        }

        private void setupAvoidMotorway() {
                SwitchPreferenceEx avoidMotorway = findPreference(plugin.AVOID_MOTORWAY.getId());
                if (avoidMotorway != null) {
                        avoidMotorway.setDescription(R.string.motorcycle_avoid_motorway_desc);
                }
        }

        // ===== Crash Detection =====

        private void setupCrashDetection() {
                SwitchPreferenceEx crashDet = findPreference(plugin.CRASH_DETECTION_ENABLED.getId());
                if (crashDet != null) {
                        crashDet.setDescription(R.string.motorcycle_crash_detection_desc);
                }
        }

        private void setupCrashSensitivity() {
                Integer[] entryValues = {1, 2, 3};
                String[] entries = {
                                getString(R.string.motorcycle_crash_sensitivity_low),
                                getString(R.string.motorcycle_crash_sensitivity_medium),
                                getString(R.string.motorcycle_crash_sensitivity_high)
                };

                ListPreferenceEx sensitivity = findPreference(plugin.CRASH_SENSITIVITY.getId());
                if (sensitivity != null) {
                        sensitivity.setEntries(entries);
                        sensitivity.setEntryValues(entryValues);
                        sensitivity.setDescription(R.string.motorcycle_crash_sensitivity_desc);
                }
        }

        /**
         * Setup an emergency contact EditTextPreference with summary and SMS permission request.
         * When user enters a phone number, automatically request SEND_SMS permission.
         */
        private void setupEmergencyContact(
                        net.osmand.plus.settings.backend.preferences.CommonPreference<String> contactPref,
                        int descResId) {
                EditTextPreference contactField = findPreference(contactPref.getId());
                if (contactField == null) return;

                String currentNumber = contactPref.get();
                if (currentNumber == null || currentNumber.isEmpty()) {
                        contactField.setSummary(R.string.motorcycle_emergency_contact_not_set);
                } else {
                        contactField.setSummary(getString(descResId) + "\n" + currentNumber);
                }

                contactField.setOnPreferenceChangeListener((preference, newValue) -> {
                        String number = (String) newValue;
                        if (number == null || number.trim().isEmpty()) {
                                preference.setSummary(R.string.motorcycle_emergency_contact_not_set);
                        } else {
                                preference.setSummary(getString(descResId) + "\n" + number);
                                // Request SEND_SMS permission when user sets an emergency contact
                                requestSmsPermissionIfNeeded();
                        }
                        return true;
                });
        }

        /**
         * Request SEND_SMS runtime permission if not already granted.
         * Required for emergency SMS to work on Android 6+.
         */
        private void requestSmsPermissionIfNeeded() {
                if (getActivity() == null) return;

                if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.SEND_SMS)
                                != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(getActivity(),
                                        new String[]{Manifest.permission.SEND_SMS},
                                        REQUEST_SMS_PERMISSION);
                }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                        @NonNull int[] grantResults) {
                if (requestCode == REQUEST_SMS_PERMISSION) {
                        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                                // SMS permission granted - emergency SMS will work
                        }
                }
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }

        // ===== Auto 3D Map =====

        private void setupAuto3DMap() {
                SwitchPreferenceEx auto3d = findPreference(plugin.AUTO_3D_MAP.getId());
                if (auto3d != null) {
                        auto3d.setDescription(R.string.motorcycle_auto_3d_desc);
                }
        }

        private void setupAuto3DSpeedThreshold() {
                Integer[] entryValues = {10, 15, 20, 25, 30, 40};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = entryValues[i] + " km/h";
                }

                ListPreferenceEx speedThreshold = findPreference(plugin.AUTO_3D_SPEED_THRESHOLD.getId());
                if (speedThreshold != null) {
                        speedThreshold.setEntries(entries);
                        speedThreshold.setEntryValues(entryValues);
                        speedThreshold.setDescription(R.string.motorcycle_auto_3d_speed_desc);
                }
        }

        private void setupAuto3DElevationAngle() {
                Integer[] entryValues = {25, 35, 45, 55, 65};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = entryValues[i] + "\u00B0";
                }

                ListPreferenceEx angle = findPreference(plugin.AUTO_3D_ELEVATION_ANGLE.getId());
                if (angle != null) {
                        angle.setEntries(entries);
                        angle.setEntryValues(entryValues);
                        angle.setDescription(R.string.motorcycle_auto_3d_angle_desc);
                }
        }

        // ===== Weather Routing =====

        private void setupWeatherRouting() {
                SwitchPreferenceEx weather = findPreference(plugin.WEATHER_ROUTING_ENABLED.getId());
                if (weather != null) {
                        weather.setDescription(R.string.motorcycle_weather_routing_desc);
                }
        }

        // ===== Track Day =====

        private void setupTrackDay() {
                SwitchPreferenceEx trackDay = findPreference(plugin.TRACK_DAY_ENABLED.getId());
                if (trackDay != null) {
                        trackDay.setDescription(R.string.motorcycle_trackday_desc);
                }
        }

        // ===== Group Riding =====

        private void setupGroupRiding() {
                SwitchPreferenceEx groupRiding = findPreference(plugin.GROUP_RIDING_ENABLED.getId());
                if (groupRiding != null) {
                        groupRiding.setDescription(R.string.motorcycle_group_riding_desc);
                }
        }

        // ===== Wear OS =====

        private void setupWearOs() {
                SwitchPreferenceEx wearOs = findPreference(plugin.WEAR_OS_ENABLED.getId());
                if (wearOs != null) {
                        wearOs.setDescription(R.string.motorcycle_wear_os_desc);
                }
        }

        // ===== OBD2 =====

        private void setupOBD2() {
                SwitchPreferenceEx obd2 = findPreference(plugin.OBD2_ENABLED.getId());
                if (obd2 != null) {
                        obd2.setDescription(R.string.motorcycle_obd2_desc);
                }
        }

        private void setupOBD2Device() {
                // List paired Bluetooth OBD2 devices
                ListPreferenceEx devicePref = findPreference(plugin.OBD2_DEVICE_ADDRESS.getId());
                if (devicePref != null) {
                        try {
                                var devices = plugin.obd2Helper.getOBD2Devices();
                                if (devices != null && !devices.isEmpty()) {
                                        String[] entries = new String[devices.size()];
                                        String[] values = new String[devices.size()];
                                        for (int i = 0; i < devices.size(); i++) {
                                                var d = devices.get(i);
                                                entries[i] = d.getName() != null ? d.getName() : d.getAddress();
                                                values[i] = d.getAddress();
                                        }
                                        devicePref.setEntries(entries);
                                        devicePref.setEntryValues(values);
                                } else {
                                        devicePref.setEntries(new String[]{"No OBD2 devices paired"});
                                        devicePref.setEntryValues(new String[]{""});
                                }
                        } catch (Exception e) {
                                devicePref.setEntries(new String[]{"Bluetooth not available"});
                                devicePref.setEntryValues(new String[]{""});
                        }
                        devicePref.setDescription(R.string.motorcycle_obd2_device_desc);
                }
        }

        // ===== Fuel Range =====

        private void setupFuelRange() {
                SwitchPreferenceEx fuelRange = findPreference(plugin.FUEL_RANGE_OVERLAY.getId());
                if (fuelRange != null) {
                        fuelRange.setDescription(R.string.motorcycle_fuel_range_desc);
                }
        }

        private void setupFuelTankCapacity() {
                Float[] entryValues = {10f, 12f, 14f, 15f, 17f, 19f, 20f, 22f, 25f};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = entryValues[i].intValue() + " L";
                }

                ListPreferenceEx tankCap = findPreference(plugin.FUEL_TANK_CAPACITY.getId());
                if (tankCap != null) {
                        tankCap.setEntries(entries);
                        tankCap.setEntryValues(entryValues);
                        tankCap.setDescription(R.string.motorcycle_fuel_tank_desc);
                }
        }

        private void setupFuelConsumption() {
                Float[] entryValues = {3.0f, 3.5f, 4.0f, 4.5f, 5.0f, 5.5f, 6.0f, 7.0f, 8.0f, 10.0f};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = String.format("%.1f L/100km", entryValues[i]);
                }

                ListPreferenceEx consumption = findPreference(plugin.FUEL_CONSUMPTION.getId());
                if (consumption != null) {
                        consumption.setEntries(entries);
                        consumption.setEntryValues(entryValues);
                        consumption.setDescription(R.string.motorcycle_fuel_consumption_desc);
                }
        }

        private void setupFuelLevel() {
                Float[] entryValues = {100f, 90f, 80f, 75f, 66f, 50f, 33f, 25f, 15f, 10f};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = entryValues[i].intValue() + "%";
                }

                ListPreferenceEx fuelLevel = findPreference(plugin.FUEL_LEVEL_PERCENT.getId());
                if (fuelLevel != null) {
                        fuelLevel.setEntries(entries);
                        fuelLevel.setEntryValues(entryValues);
                        fuelLevel.setDescription(R.string.motorcycle_fuel_level_desc);
                }
        }

        // ===== Track Day UI =====

        private void setupTrackDayUI() {
                SwitchPreferenceEx trackDayUI = findPreference(plugin.TRACK_DAY_UI_ENABLED.getId());
                if (trackDayUI != null) {
                        trackDayUI.setDescription(R.string.motorcycle_trackday_ui_desc);
                }
        }

        private void setupTrackDaySectors() {
                Integer[] entryValues = {2, 3, 4, 5, 6};
                String[] entries = new String[entryValues.length];
                for (int i = 0; i < entryValues.length; i++) {
                        entries[i] = entryValues[i] + " sectors";
                }

                ListPreferenceEx sectors = findPreference(plugin.TRACK_DAY_SECTORS.getId());
                if (sectors != null) {
                        sectors.setEntries(entries);
                        sectors.setEntryValues(entryValues);
                        sectors.setDescription(R.string.motorcycle_trackday_sectors_desc);
                }
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
                String key = preference.getKey();

                if (key.equals(plugin.ACCEL_FILTER_COEFFICIENT.getId())) {
                        plugin.sensorHelper.setAccelFilterCoefficient((Float) newValue);
                } else if (key.equals(plugin.GYRO_FILTER_COEFFICIENT.getId())) {
                        plugin.sensorHelper.setGyroFilterCoefficient((Float) newValue);
                } else if (key.equals(plugin.CRASH_SENSITIVITY.getId())) {
                        plugin.crashDetection.setSensitivity((Integer) newValue);
                } else if (key.equals(plugin.PREFER_CURVY_ROADS.getId()) ||
                                key.equals(plugin.AVOID_MOTORWAY.getId())) {
                        plugin.applyMotorcycleRoutingPrefs();
                } else if (key.equals(plugin.AUTO_3D_MAP.getId())) {
                        plugin.autoMap3D.setEnabled((Boolean) newValue);
                } else if (key.equals(plugin.AUTO_3D_SPEED_THRESHOLD.getId())) {
                        plugin.autoMap3D.setSpeedThresholdKmh(((Integer) newValue).floatValue());
                } else if (key.equals(plugin.AUTO_3D_ELEVATION_ANGLE.getId())) {
                        plugin.autoMap3D.setElevationAngle3D((Integer) newValue);
                }

                return super.onPreferenceChange(preference, newValue);
        }
}
