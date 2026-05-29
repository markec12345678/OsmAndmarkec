package net.osmand.plus.plugins.motorcyclesensors;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.R;
import net.osmand.plus.base.BaseOsmAndDialogFragment;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.motorcyclesensors.MotorcycleSensorsPlugin;
import net.osmand.plus.plugins.motorcyclesensors.recording.MotorcycleRideStatistics;
import net.osmand.plus.plugins.motorcyclesensors.routing.RouteCurvinessStats;
import net.osmand.plus.utils.UiUtilities;

/**
 * Dialog showing a summary of the motorcycle ride after recording stops.
 *
 * Displays:
 * - Max lean angle (left/right)
 * - Max G-force (total, lateral, braking)
 * - Corner count and distribution
 * - Ride Score (0-100)
 * - Route curviness (if a route was calculated)
 */
public class MotorcycleRideSummaryDialog extends BaseOsmAndDialogFragment {

        public static final String TAG = "MotorcycleRideSummaryDialog";

        private MotorcycleSensorsPlugin plugin;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                plugin = PluginsHelper.requirePlugin(MotorcycleSensorsPlugin.class);
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
                int themeRes = isNightMode() ? R.style.OsmandDarkTheme : R.style.OsmandLightTheme;
                return new Dialog(requireContext(), themeRes);
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
                int themeRes = isNightMode() ? R.style.OsmandDarkTheme : R.style.OsmandLightTheme;
                View view = LayoutInflater.from(requireContext()).inflate(R.layout.motorcycle_ride_summary, null);

                updateRideStats(view);
                updateRouteCurviness(view);

                return view;
        }

        private void updateRideStats(View view) {
                MotorcycleRideStatistics stats = plugin.sensorRecorder.getRideStatistics();

                // Lean angle
                TextView maxLeanText = view.findViewById(R.id.max_lean_value);
                if (maxLeanText != null) {
                        maxLeanText.setText(String.format("%.0f°", stats.getMaxLeanAngleDeg()));
                }

                // Max G-force
                TextView maxGText = view.findViewById(R.id.max_gforce_value);
                if (maxGText != null) {
                        maxGText.setText(String.format("%.2fG", stats.getMaxTotalG()));
                }

                // Max braking G
                TextView maxBrakingText = view.findViewById(R.id.max_braking_value);
                if (maxBrakingText != null) {
                        maxBrakingText.setText(String.format("%.2fG", Math.abs(stats.getMaxBrakingG())));
                }

                // Max lateral G
                TextView maxLateralText = view.findViewById(R.id.max_lateral_value);
                if (maxLateralText != null) {
                        maxLateralText.setText(String.format("%.2fG", stats.getMaxLateralG()));
                }

                // Average lean
                TextView avgLeanText = view.findViewById(R.id.avg_lean_value);
                if (avgLeanText != null) {
                        avgLeanText.setText(String.format("%.1f°", stats.getAvgLeanAngleDeg()));
                }

                // Average G
                TextView avgGText = view.findViewById(R.id.avg_gforce_value);
                if (avgGText != null) {
                        avgGText.setText(String.format("%.2fG", stats.getAvgTotalG()));
                }

                // Ride score
                TextView rideScoreText = view.findViewById(R.id.ride_score_value);
                if (rideScoreText != null) {
                        int score = calculateRideScore(stats);
                        rideScoreText.setText(String.valueOf(score));
                }
        }

        private void updateRouteCurviness(View view) {
                RouteCurvinessStats curviness = plugin.lastRouteCurvinessStats;
                View curvinessSection = view.findViewById(R.id.route_curviness_section);

                if (curviness == null) {
                        if (curvinessSection != null) {
                                curvinessSection.setVisibility(View.GONE);
                        }
                        return;
                }

                if (curvinessSection != null) {
                        curvinessSection.setVisibility(View.VISIBLE);
                }

                TextView classificationText = view.findViewById(R.id.curviness_classification);
                if (classificationText != null) {
                        classificationText.setText(curviness.getClassification().getDisplayName());
                }

                TextView funScoreText = view.findViewById(R.id.fun_score_value);
                if (funScoreText != null) {
                        funScoreText.setText(String.valueOf(plugin.curvyRoadRouter.calculateFunScore(curviness)));
                }

                TextView cornersText = view.findViewById(R.id.corners_value);
                if (cornersText != null) {
                        cornersText.setText(String.format("%d (%.1f /km)", curviness.getTotalCorners(), curviness.getCornersPerKm()));
                }

                TextView twistyPctText = view.findViewById(R.id.twisty_pct_value);
                if (twistyPctText != null) {
                        twistyPctText.setText(String.format("%.0f%%", curviness.getTwistyPercentage()));
                }
        }

        /**
         * Calculate a simple ride score based on recorded statistics.
         * Full scoring is in MotorcycleSensorAnalysisHelper, this is a quick approximation.
         */
        private int calculateRideScore(MotorcycleRideStatistics stats) {
                // Lean angle score (0-40 points): max lean out of 45 degrees
                float leanScore = Math.min(stats.getMaxLeanAngleDeg() / 45f, 1f) * 40f;

                // G-force utilization score (0-30 points): max total G out of 1.5G
                float gScore = Math.min(stats.getMaxTotalG() / 1.5f, 1f) * 30f;

                // Smoothness score (0-30 points): inverse of G-force variance (approximation)
                float smoothness = Math.max(0, 1f - (stats.getAvgTotalG() / 2f)) * 30f;

                return Math.min(100, Math.round(leanScore + gScore + smoothness));
        }

        public void show(@NonNull FragmentManager manager) {
                if (!manager.isDestroyed()) {
                        show(manager, TAG);
                }
        }
}
