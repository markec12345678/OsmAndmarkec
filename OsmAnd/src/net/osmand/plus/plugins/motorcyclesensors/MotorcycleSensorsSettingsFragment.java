package net.osmand.plus.plugins.motorcyclesensors;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import net.osmand.plus.R;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.settings.fragments.BaseSettingsFragment;
import net.osmand.plus.settings.preferences.ListPreferenceEx;
import net.osmand.plus.settings.preferences.SwitchPreferenceEx;

/**
 * Settings fragment for the Motorcycle Sensors Plugin.
 *
 * Allows configuration of:
 * - Sensor display (lean angle, G-force widgets)
 * - Sensor filter coefficients (smoothing)
 * - Ride recording (GPX telemetry logging, auto-start)
 * - Curvy road routing preference
 * - Crash detection sensitivity
 */
public class MotorcycleSensorsSettingsFragment extends BaseSettingsFragment {

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
                // Smoothing values from 0.1 (very smooth, high latency) to 1.0 (no smoothing, noisy)
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
                // Sensitivity levels: 1=low (fewer false positives), 2=medium, 3=high (more responsive)
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

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
                String key = preference.getKey();

                // When filter coefficients change, apply them to the sensor helper immediately
                if (key.equals(plugin.ACCEL_FILTER_COEFFICIENT.getId())) {
                        plugin.sensorHelper.setAccelFilterCoefficient((Float) newValue);
                } else if (key.equals(plugin.GYRO_FILTER_COEFFICIENT.getId())) {
                        plugin.sensorHelper.setGyroFilterCoefficient((Float) newValue);
                } else if (key.equals(plugin.CRASH_SENSITIVITY.getId())) {
                        plugin.crashDetection.setSensitivity((Integer) newValue);
                } else if (key.equals(plugin.PREFER_CURVY_ROADS.getId()) ||
                                key.equals(plugin.AVOID_MOTORWAY.getId())) {
                        // Re-apply routing preferences when curvy roads or motorway avoidance changes
                        plugin.applyMotorcycleRoutingPrefs();
                }

                return super.onPreferenceChange(preference, newValue);
        }
}
