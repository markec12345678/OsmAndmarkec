# OsmAnd Motorcycle Edition — MotoTrack

> The best open-source motorcycle navigation app — a fork of [OsmAnd](https://osmand.net/) with dedicated motorcycle telemetry, curvy road routing, crash detection, sensor calibration, ride analytics, and diagnostics.

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![OpenStreetMap](https://img.shields.io/badge/Data-OpenStreetMap-green.svg)](https://www.openstreetmap.org/)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%2FJava-orange.svg)](https://kotlinlang.org/)

---

## Why This Fork?

OsmAnd is the most powerful open-source navigation app, but it treats motorcycles like cars with a different icon. No lean angle tracking, no G-force monitoring, no twisty road preference, no crash detection. **This fork changes that.** We add motorcycle-specific features that no other open-source navigation app offers, making this the definitive tool for riders who want data-driven insights about their rides.

---

## Features

### Real-Time Motorcycle Telemetry

| Widget | Description |
|--------|-------------|
| **Lean Angle (Nagib)** | Real-time lean angle in degrees with L/R direction indicator (complementary filter fusion of gyroscope + rotation vector + accelerometer) |
| **Total G-Force** | Gravity-compensated total G-force with dynamic gauge display |
| **Lateral G-Force** | Left/right G-force (cornering force) with direction label |
| **Longitudinal G-Force (Pospešek)** | Acceleration/braking G-force with "ACC"/"BRK" labels |

**Sensor fusion pipeline:**
```
Android Sensors (50Hz, low-pass filtered)
  → DeviceSensorHelper
    → LeanAngleCalculator (complementary filter: α=0.02 gyro trust)
    → GForceCalculator (gravity compensation + moving average)
      → SensorDataProcessor (unified sensor pipeline)
        → Map Widgets (real-time display)
        → MotorcycleSensorRecorder (10Hz GPX recording)
        → CrashDetectionHelper (multi-signal crash alert)
        → RideAnalyticsEngine (real-time ride aggregation)
        → SensorCalibrationHelper (30s calibration ride)
        → SensorDiagnosticsHelper (noise & drift monitoring)
```

### Sensor Calibration

**30-second calibration ride** eliminates sensor bias for accurate readings:

1. Start calibration from plugin settings
2. Ride in a straight line at steady speed for 30 seconds
3. The system collects gyroscope and accelerometer samples
4. Computes bias corrections for each sensor axis
5. Applies corrections to all subsequent sensor readings

**Calibration data tracked:**
- Gyroscope bias (X, Y, Z axes) — removes drift offset
- Accelerometer bias (X, Y, Z axes) — removes gravity estimation error
- Calibration quality score — indicates reliability of calibration
- Timestamp — tracks when last calibration was performed

### Sensor Diagnostics

**Real-time sensor health monitoring** with ring buffer analysis:

- **Noise floor estimation** — Tracks RMS noise levels for each sensor axis
- **Drift detection** — Monitors gyroscope and accelerometer drift over time
- **Ring buffer** — Stores last 30 seconds of raw sensor data for analysis
- **Health indicators** — Visual status for each sensor (OK / Warning / Error)
- **Auto-validation** — Warns if sensor data quality is too low for reliable lean angle or G-force readings

### Curvy Road Routing (Unique Feature!)

**No other open-source navigation app has this.** Our Curvy Road Router analyzes route segments for "twistiness" — measured in degrees of direction change per 100 meters — and can prefer twisty roads over highways when you want a fun ride instead of the fastest commute.

| Twistiness Class | Range | Description |
|-----------------|-------|-------------|
| Gentle | < 5 deg/100m | Straight or gently curving roads |
| Moderate | 5–15 deg/100m | Pleasant winding roads |
| Twisty | 15–30 deg/100m | Engaging curves and switchbacks |
| Very Twisty | > 30 deg/100m | Tight hairpins and mountain passes |

**Fun Score (0–100):** Quantifies how enjoyable a route is for riding:
- 40% — Percentage of twisty+ segments
- 30% — Percentage of very twisty segments
- 20% — Corner density (corners per km)
- 10% — Average curviness

**Road class priorities in curvy mode:**

| Road Class | Weight | Rationale |
|-----------|--------|-----------|
| Tertiary | 1.0 | Best twisty roads |
| Secondary | 0.9 | Good curves |
| Unclassified | 0.85 | Hidden gems |
| Track | 0.8 | Adventure roads |
| Primary | 0.7 | Mixed |
| Residential | 0.6 | Short connectors |
| Trunk | 0.5 | Avoid |
| Service | 0.4 | Too short |
| Motorway | 0.3 | Avoid entirely |

**Routing Sanity Guard** — Prevents the curvy road router from suggesting dangerous or impractical routes:
- Validates route segments against sanity thresholds
- Rejects routes with excessive detour ratios (>3x optimal distance)
- Ensures curvy preference doesn't route through impassable roads
- Logs warnings for rejected route segments

### Crash Detection

Multi-signal crash detection using a **2-of-3 signal fusion** within a 3-second window:

1. **High G-force:** Total G >= 2.5G (with minimum speed 18 km/h)
2. **High rotation rate:** Gyroscope magnitude >= 300 deg/s (with minimum speed)
3. **Speed drop:** GPS speed drops below 7.2 km/h after high-G event

**Anti-false-positive safeguards:**
- 30-second cooldown between detections
- Minimum speed threshold prevents false triggers when stationary
- Time-windowed signal evaluation with automatic expiry
- Single ultra-high G event (>3.75G) triggers immediate alert

**Dynamic sensitivity thresholds** — Crash detection adapts to riding conditions:
- Configurable sensitivity levels (Low / Medium / High)
- Auto-adjusts thresholds based on current riding mode
- Different thresholds for city vs. highway riding

**Crash Alert Dialog** — Full-screen emergency UI when a crash is detected:
- Large "I'm Okay" button to dismiss false alerts
- **Emergency SMS** automatically sent to configured contact when countdown expires
- 10-second countdown timer — if not dismissed, sends SMS with GPS location
- **Emergency contact** configurable in plugin settings (phone number with phone keyboard input)
- Persistent crash event log with timestamps, GPS coordinates, and sensor readings

### Plugin Settings UI

Dedicated settings screen accessible from **Profile → Motorcycle → Plugin settings**:

- **Sensor data recording** — Enable/disable GPX recording of motorcycle telemetry
- **Accelerometer filter coefficient** — 0.0–1.0 (default 0.8) — Controls low-pass filter aggressiveness
- **Gyroscope filter coefficient** — 0.0–1.0 (default 0.7) — Controls gyroscope smoothing
- **Curvy road preference** — Enable/disable twisty road routing
- **Avoid motorway** — Skip highways in route calculation
- **Crash detection** — Enable/disable crash detection system
- **Crash detection sensitivity** — Low / Medium / High threshold presets
- **Emergency contact** — Phone number for emergency SMS (phone keyboard input, optional)
- **Sensor calibration** — Start 30-second calibration ride

### Ride Recording & Analytics

**During the ride:**
- Sensor data recorded at 10Hz into GPX track extensions (`lean_angle`, `lateral_g`, `longitudinal_g`, `total_g`)
- In-memory buffer up to 1,000,000 data points (~5.5 hours at 50Hz)
- Live statistics: max lean, max G-force, max braking G, averages
- Real-time ride analytics engine aggregating corner events, G-force distributions
- Auto-start recording when MOTORCYCLE mode is active and moving (> 0.5 m/s)

**After the ride — Ride Summary Dialog:**
- **Corner detection:** State machine tracking lean transitions with direction, max/avg lean, intensity
- **Corner intensity classification:** Gentle (<15 deg), Moderate (15-30 deg), Aggressive (30-45 deg), Extreme (>45 deg)
- **G-force analysis:** Classification by G-range with peak tracking and percentages
- **Ride Score (0-100):**
  - 25% — Corner variety (left/right balance x count)
  - 30% — Lean usage (peak angle achievement)
  - 25% — Smoothness (inverse G-force variance)
  - 20% — Braking score (maximum braking G)
- **Ride summary dialog** — Dedicated UI screen showing all post-ride statistics and charts

**Chart integration:** View lean angle and G-force graphs over your recorded track in OsmAnd's track analysis view.

---

## Architecture

```
OsmAnd/src/net/osmand/plus/plugins/motorcyclesensors/
├── MotorcycleSensorsPlugin.kt               # Central orchestrator (OsmandPlugin)
├── MotorcycleSensorsSettingsFragment.java    # Plugin settings UI (PreferenceFragment)
├── MotorcycleRideSummaryDialog.java          # Post-ride summary dialog
├── sensors/
│   ├── DeviceSensorHelper.kt                # Raw Android sensor I/O (accel, gyro, rotation)
│   ├── LeanAngleCalculator.kt               # Complementary filter → lean angle (deg)
│   ├── GForceCalculator.kt                  # Gravity compensation → G-force decomposition
│   └── SensorDataProcessor.kt               # Unified sensor pipeline (fusion + dispatch)
├── widgets/
│   ├── LeanAngleWidget.kt                   # Map widget: lean angle with L/R indicator
│   └── GForceWidget.kt                      # Map widget: total/lateral/longitudinal G
├── recording/
│   └── MotorcycleSensorRecorder.kt          # 10Hz GPX recording + in-memory buffer
├── analysis/
│   └── MotorcycleSensorAnalysisHelper.kt    # Post-ride: corners, scoring, distribution
├── routing/
│   ├── CurvyRoadRouter.kt                   # Twistiness analysis, Fun Score, route stats
│   ├── MotorcycleRoutingHelper.kt           # Curvy road preference + routing params
│   ├── RoutingSanityGuard.kt                # Route quality validation (prevents bad routes)
│   └── TwistinessCalculator.kt              # Low-level polyline curvature computation
├── safety/
│   ├── CrashDetectionHelper.kt              # Multi-signal crash detection (2-of-3 fusion)
│   ├── CrashAlertDialog.kt                  # Full-screen crash alert with countdown
│   └── CrashEventLog.kt                     # Persistent crash event log (timestamp, GPS, sensors)
├── calibration/
│   └── SensorCalibrationHelper.kt           # 30s calibration ride, bias correction
├── instrumentation/
│   └── SensorDiagnosticsHelper.kt           # Ring buffer monitoring, noise analysis
└── analytics/
    └── RideAnalyticsEngine.kt               # Real-time ride aggregation & statistics
```

**Resource files:**

| File | Purpose |
|------|---------|
| `res/xml/motorcycle_sensors_settings.xml` | Plugin settings preferences |
| `res/layout/motorcycle_ride_summary.xml` | Ride summary dialog layout |
| `res/layout/crash_alert_dialog.xml` | Crash alert full-screen layout |
| `res/drawable/widget_motorcycle_lean_angle_day.xml` | Lean angle widget icon (day) |
| `res/drawable/widget_motorcycle_lean_angle_night.xml` | Lean angle widget icon (night) |
| `res/drawable/widget_motorcycle_gforce_day.xml` | G-force widget icon (day) |
| `res/drawable/widget_motorcycle_gforce_night.xml` | G-force widget icon (night) |
| `res/drawable/ic_action_curvy_road_day.xml` | Curvy road routing icon (day) |
| `res/drawable/ic_action_curvy_road_night.xml` | Curvy road routing icon (night) |
| `res/drawable/ic_action_dirt_motorcycle.xml` | Dirt motorcycle icon |
| `res/drawable/ic_action_enduro_motorcycle.xml` | Enduro motorcycle icon |
| `res/drawable/ic_action_motorcycle_dark.xml` | Dark motorcycle icon |

**Modified OsmAnd core files** (minimal changes only):

| File | Change |
|------|--------|
| `WidgetsAvailabilityHelper.java` | Registered motorcycle widgets for MOTORCYCLE ApplicationMode |
| `SettingsScreenType.java` | Added `MOTORCYCLE_SENSORS_SETTINGS` enum for settings navigation |
| `strings.xml` | ~50+ new motorcycle-specific string resources |

---

## Building

### Prerequisites

- Android Studio (latest stable)
- JDK 17+
- Android SDK with compile SDK 34+
- Git with LFS support
- CMake and NDK (for native OsmAnd core)

### Build Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/markec12345678/OsmAndmarkec.git
   cd OsmAndmarkec
   ```

2. **Clone OsmAnd-core (native C++ library):**
   ```bash
   git clone https://github.com/osmandapp/OsmAnd-core.git
   cd OsmAnd-core && git checkout master
   # Build native core following OsmAnd docs
   cd ..
   ```

3. **Open in Android Studio:**
   - Open the root `OsmAndmarkec` directory
   - Let Gradle sync (may take several minutes on first build)
   - Select `OsmAnd` module as the run configuration
   - Build and run on a device or emulator

4. **For F-Droid / debug builds:**
   ```bash
   ./gradlew assembleDebug
   ```

### Build Notes

- The motorcycle plugin is a **built-in plugin** compiled directly inside the `OsmAnd` app module (like Astronomy, Accessibility, and other built-in plugins). It does not need a separate `plugins/` subdirectory.
- The plugin requires device sensors (accelerometer + gyroscope) for full functionality. Emulators lack these sensors.
- For detailed OsmAnd build instructions, see [OsmAnd Technical Docs](https://www.osmand.net/docs/technical/build-osmand/).

---

## Usage

### First Setup

1. Open OsmAnd and go to **Plugins** → Enable **Motorcycle Sensors**
2. Switch to **Motorcycle** application mode (motorcycle icon in the profile selector)
3. Add motorcycle widgets to your dashboard:
   - Long-press the widget area → **Add widget** → **Motorcycle Sensors** → Choose lean angle / G-force widgets
4. Go to **Profile** → **Motorcycle** → **Plugin settings** to configure:
   - Sensor data recording on/off
   - Accelerometer filter coefficient (0.0–1.0, default 0.8)
   - Gyroscope filter coefficient (0.0–1.0, default 0.7)
   - Curvy road preference on/off
   - Avoid motorway on/off
   - Crash detection on/off
   - Crash detection sensitivity (Low / Medium / High)
5. **Calibrate sensors** — Start a 30-second straight-line calibration ride for best accuracy

### During a Ride

- Sensor recording **auto-starts** when MOTORCYCLE mode is active and you're moving
- Lean angle and G-force update in real-time on the map dashboard
- Sensor diagnostics run in the background, warning you of sensor issues
- If crash detection is enabled and a crash is detected, a full-screen alert appears with:
  - **"I'm Okay"** — dismisses the alert (must press within countdown)
  - **"Emergency"** — sends SMS with GPS location to your emergency contact
  - **30-second countdown** — auto-triggers emergency protocol if not dismissed

### After a Ride

1. Open **My Places** → **Tracks** → Select your recorded track
2. View **Analysis** tab for lean angle and G-force charts
3. Check **Ride Summary** dialog for: max lean, max G-force, corner count, ride score, corner distribution
4. Review **Crash Event Log** if any incidents were detected during the ride

### Curvy Road Navigation

1. Plan a route while in MOTORCYCLE mode
2. Enable **Prefer curvy roads** in motorcycle plugin settings
3. The router will prioritize winding tertiary and secondary roads over straight highways
4. **Routing Sanity Guard** validates the route to prevent dangerous suggestions
5. View route statistics: Fun Score, curviness classification, corners per km

### Sensor Calibration

1. Go to **Plugin settings** → **Calibrate sensors**
2. Find a straight, flat road
3. Ride at a steady speed for 30 seconds without turning
4. The system computes bias corrections automatically
5. Calibration quality score tells you if recalibration is needed

---

## What's Missing / Known Issues

> These are items that still need to be implemented before the app is fully production-ready. Contributions welcome!

### High (Important features not yet implemented)

| Issue | Description | Status |
|-------|-------------|--------|
| **No curvy road map overlay** | `TwistinessCalculator` computes road curvature and defines overlay colors, but there's no `OsmandMapTileView` overlay implementation to visualize twistiness on the map. Riders can't see which roads are twisty without planning a route. | Missing |
| **No lean angle heat map** | Planned feature — overlay on the map showing lean angle intensity along recorded tracks, color-coded by angle. Not implemented. | Planned |
| **Routing profile not properly separated** | The MOTORCYCLE mode inherits from CAR. Need a proper separate routing profile with motorcycle-specific speed assumptions, road restrictions, and the curvy road preference built-in. | Partial |
| **No custom motorcycle rendering style** | No dedicated map rendering style for motorcycles. Should highlight twisty roads, show fuel stations prominently, and use motorcycle-friendly POI categories. | Missing |
| **Build not fully tested** | The code has been written and committed but the full OsmAnd project has NOT been compiled end-to-end. There may be compilation errors, missing imports, or API mismatches with the OsmAnd codebase. A full build test is needed. | Untested |

### Medium (Nice-to-have improvements)

| Issue | Description | Status |
|-------|-------------|--------|
| **Group Riding** | Real-time position sharing between riders via peer-to-peer or relay server. Track your riding group on the map. | Planned |
| **Track Day Mode** | Lap timing, sector splits, best lap tracking for track day sessions. Uses GPS for timing, not transponders. | Planned |
| **Fuel range overlay** | Show remaining fuel range as a polygon on the map based on fuel tank size and consumption rate. | Planned |
| **Weather integration** | Show wind speed/direction, temperature, and precipitation along the route. Critical for motorcycle safety. | Planned |
| **OBD2 sensor integration** | Extend the existing `VehicleMetricsPlugin` OBD2 support for motorcycle-specific data: RPM, gear position, throttle position, coolant temp. | Planned |
| **Apple CarPlay / Android Auto** | Adapt the motorcycle dashboard for automotive displays. | Planned |
| **Wear OS companion** | Quick glance at lean angle and G-force on a smartwatch. | Planned |
| **Ride sharing & community** | Upload ride stats, compare Fun Scores with other riders, discover popular twisty roads. | Planned |
| **Multi-language support** | String resources are English-only. Need translations for major languages (DE, FR, ES, IT, PT, JA, SL, etc.). | Missing |
| **Unit tests** | No unit tests exist for any motorcycle plugin code. Critical algorithms (lean angle fusion, G-force calculation, twistiness scoring, crash detection) should be tested. | Missing |

---

## Contributing

We welcome contributions from riders, developers, and motorcycle enthusiasts!

### How to Contribute

1. **Fork** the repository
2. **Create a feature branch:** `git checkout -b feature/your-feature-name`
3. **Write code** following OsmAnd's coding conventions (Kotlin for new code, Java where matching existing patterns)
4. **Test** on a real device with sensors whenever possible
5. **Commit** with descriptive messages following [Conventional Commits](https://www.conventionalcommits.org/)
6. **Push** and create a **Pull Request**

### Priority Areas for Contributions

1. **Curvy road map overlay** — Visual twistiness on the map
2. **Build verification** — Test that it compiles and runs
3. **Unit tests** — Reliability for core algorithms
4. **Translations** — Multi-language support
5. **Custom motorcycle rendering style** — Motorcycle-friendly map display
6. **Custom SMS message template** — Let users customize the emergency message text

### Code Style

- Kotlin for all new motorcycle plugin code
- Java when modifying existing OsmAnd Java files (match surrounding style)
- 4-space indentation
- Follow OsmAnd's existing package structure
- Use meaningful variable names (avoid `a`, `b`, `tmp`)

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| App framework | Android (min SDK 24, target SDK 34) |
| Languages | Kotlin (plugin), Java (OsmAnd core modifications) |
| Navigation engine | OsmAnd custom routing (Java, C++ native core) |
| Map data | OpenStreetMap offline vector maps (.obf format) |
| Sensor I/O | Android SensorManager (TYPE_ACCELEROMETER, TYPE_GYROSCOPE, TYPE_GAME_ROTATION_VECTOR) |
| Data recording | GPX 1.1 with custom extensions |
| Sensor fusion | Complementary filter (gyro + accelerometer + rotation vector) |
| Build system | Gradle (multi-module) |
| License | GPL v3 |

---

## Algorithm Details

### Lean Angle Calculation

The lean angle is computed using a **complementary filter** that fuses three sensor sources:

1. **Rotation vector** — Direct roll from `SensorManager.getOrientation()`, accurate but noisy
2. **Accelerometer** — `atan2(lateralAccel, sqrt(longitudinal^2 + vertical^2))`, good for steady-state
3. **Gyroscope integration** — Integrate roll rate over time, drifts but excellent for dynamics

**Fusion formula:**
```
fusedLean = alpha * rotationVectorRoll + (1 - alpha) * gyroIntegratedLean
```
Where `alpha = 0.02` — heavily trusting the gyroscope for dynamic lean angles (cornering) while using the rotation vector for long-term drift correction. The output is clamped to +/-60 degrees (realistic motorcycle lean range) and smoothed with a 5-sample moving average.

### G-Force Calculation

1. Rotate gravity vector from world frame to phone frame using roll/pitch
2. `dynamicAccel = measuredAccel - gravityComponent`
3. Convert to G-units (divide by 9.80665 m/s^2)
4. Clamp to +/-3.0G (max realistic motorcycle G-force)
5. Apply 5-sample moving average smoothing
6. Track peak values: peakLateralG, peakLongitudinalG, peakTotalG, peakBrakingG

### Sensor Calibration

1. Collect 30 seconds of sensor data during straight-line riding
2. Compute mean gyroscope bias per axis (should be ~0 when not rotating)
3. Compute mean accelerometer bias per axis (deviation from expected gravity)
4. Apply bias correction to all subsequent raw sensor readings
5. Track calibration quality — reject if variance is too high (unstable ride)

### Sensor Diagnostics

1. Maintain a ring buffer of last 30 seconds of raw sensor data
2. Compute RMS noise floor for each sensor axis
3. Track drift rate by comparing integrated gyro against rotation vector
4. Flag sensors as OK / Warning / Error based on thresholds
5. Provide visibility into data quality for debugging sensor issues

### Twistiness (Curvy Road Score)

**Metric:** Degrees of bearing change per 100 meters of road distance.

For each road segment:
```
twistiness = |bearingChange| / segmentDistance * 100
```

Where bearing change is calculated using the haversine bearing formula between segment start and end points. Segments shorter than 5m are skipped. Corners are defined as bearing changes exceeding 15 degrees.

### Crash Detection

**2-of-3 signal fusion within a 3-second window:**

| Signal | Threshold | Rationale |
|--------|-----------|-----------|
| High G-force | >= 2.5G | Typical crash impact exceeds 2.5G |
| High rotation | >= 300 deg/s (5.24 rad/s) | Motorcycle falling generates rapid rotation |
| Speed drop | GPS speed < 7.2 km/h | Vehicle stops suddenly after impact |

**Special case:** A single ultra-high G event (>3.75G, 1.5x threshold) triggers an immediate potential crash alert even without corroboration.

**Dynamic sensitivity:** Configurable presets adjust all thresholds:
- **Low:** 3.0G / 400 deg/s (fewer false positives, may miss minor crashes)
- **Medium:** 2.5G / 300 deg/s (balanced, default)
- **High:** 2.0G / 200 deg/s (more sensitive, may trigger on aggressive riding)

---

## License

This project is licensed under the GNU General Public License v3.0 — see the [LICENSE](LICENSE) file for details. This is a fork of OsmAnd, which is also GPL v3 licensed.

---

## Acknowledgments

- **[OsmAnd](https://osmand.net/)** — The incredible open-source navigation app this project is built on
- **[OpenStreetMap](https://www.openstreetmap.org/)** — The free, collaborative map of the world
- **OsmAnd contributors** — Thousands of developers, translators, and mappers who built the foundation

---

## Links

- **Repository:** [https://github.com/markec12345678/OsmAndmarkec](https://github.com/markec12345678/OsmAndmarkec)
- **Upstream OsmAnd:** [https://github.com/osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd)
- **OsmAnd Website:** [https://osmand.net/](https://osmand.net/)
- **OsmAnd Build Docs:** [https://www.osmand.net/docs/technical/build-osmand/](https://www.osmand.net/docs/technical/build-osmand/)
- **OpenStreetMap:** [https://www.openstreetmap.org/](https://www.openstreetmap.org/)
