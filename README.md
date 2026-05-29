# OsmAnd Motorcycle Edition

> The best open-source motorcycle navigation app — a fork of [OsmAnd](https://osmand.net/) with dedicated motorcycle telemetry, curvy road routing, crash detection, and ride analytics.

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
| **Lean Angle** | Real-time lean angle in degrees with L/R direction indicator (complementary filter fusion of gyroscope + rotation vector + accelerometer) |
| **Total G-Force** | Gravity-compensated total G-force with dynamic gauge display |
| **Lateral G-Force** | Left/right G-force (cornering force) with direction label |
| **Longitudinal G-Force** | Acceleration/braking G-force with "ACC"/"BRK" labels |

**Sensor fusion pipeline:**
```
Android Sensors (50Hz, low-pass filtered)
  → DeviceSensorHelper
    → LeanAngleCalculator (complementary filter: α=0.02 gyro trust)
    → GForceCalculator (gravity compensation + moving average)
      → Map Widgets (real-time display)
      → MotorcycleSensorRecorder (10Hz GPX recording)
      → CrashDetectionHelper (multi-signal crash alert)
```

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

### Ride Recording & Analysis

**During the ride:**
- Sensor data recorded at 10Hz into GPX track extensions (`lean_angle`, `lateral_g`, `longitudinal_g`, `total_g`)
- In-memory buffer up to 1,000,000 data points (~5.5 hours at 50Hz)
- Live statistics: max lean, max G-force, max braking G, averages
- Auto-start recording when MOTORCYCLE mode is active and moving (> 0.5 m/s)

**After the ride — Ride Analysis:**
- **Corner detection:** State machine tracking lean transitions with direction, max/avg lean, intensity
- **Corner intensity classification:** Gentle (<15 deg), Moderate (15-30 deg), Aggressive (30-45 deg), Extreme (>45 deg)
- **G-force analysis:** Classification by G-range with peak tracking and percentages
- **Ride Score (0-100):**
  - 25% — Corner variety (left/right balance x count)
  - 30% — Lean usage (peak angle achievement)
  - 25% — Smoothness (inverse G-force variance)
  - 20% — Braking score (maximum braking G)

**Chart integration:** View lean angle and G-force graphs over your recorded track in OsmAnd's track analysis view.

---

## Architecture

```
OsmAnd/src/net/osmand/plus/plugins/motorcyclesensors/
├── MotorcycleSensorsPlugin.kt          # Central orchestrator (OsmandPlugin)
├── sensors/
│   ├── DeviceSensorHelper.kt           # Raw Android sensor I/O (accel, gyro, rotation)
│   ├── LeanAngleCalculator.kt          # Complementary filter → lean angle (deg)
│   └── GForceCalculator.kt             # Gravity compensation → G-force decomposition
├── widgets/
│   ├── LeanAngleWidget.kt              # Map widget: lean angle with L/R indicator
│   └── GForceWidget.kt                 # Map widget: total/lateral/longitudinal G
├── recording/
│   └── MotorcycleSensorRecorder.kt     # 10Hz GPX recording + in-memory buffer
├── analysis/
│   └── MotorcycleSensorAnalysisHelper.kt  # Post-ride: corners, scoring, distribution
├── routing/
│   ├── CurvyRoadRouter.kt              # Twistiness analysis, Fun Score, route stats
│   ├── MotorcycleRoutingHelper.kt      # Curvy road preference + routing params
│   └── TwistinessCalculator.kt         # Low-level polyline curvature computation
└── safety/
    └── CrashDetectionHelper.kt         # Multi-signal crash detection (2-of-3 fusion)
```

**Modified OsmAnd core files:**

| File | Change |
|------|--------|
| `WidgetType.java` | Added 4 motorcycle widget types |
| `WidgetGroup.java` | Added MOTORCYCLE_SENSORS widget group |
| `PluginsHelper.java` | Registered MotorcycleSensorsPlugin |
| `GPXDataSetType.java` | Added MOTORCYCLE_LEAN_ANGLE + MOTORCYCLE_TOTAL_G chart types |
| `GpxDataSetTypeGroup.java` | Added MOTORCYCLE_SENSORS chart group |
| `PointAttributes.kt` | Added MotorcycleData class (leanAngle, lateralG, longitudinalG, totalG) |
| `ApplicationMode.java` | MOTORCYCLE mode already exists upstream |
| `strings.xml` | ~37 new motorcycle-specific string resources |

**Drawable resources created:**

| File | Purpose |
|------|---------|
| `widget_motorcycle_lean_angle_day.xml` | Lean angle widget icon (day mode) |
| `widget_motorcycle_lean_angle_night.xml` | Lean angle widget icon (night mode) |
| `widget_motorcycle_gforce_day.xml` | G-force widget icon (day mode) |
| `widget_motorcycle_gforce_night.xml` | G-force widget icon (night mode) |
| `ic_action_curvy_road_day.xml` | Curvy road routing icon (day mode) |
| `ic_action_curvy_road_night.xml` | Curvy road routing icon (night mode) |

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

### During a Ride

- Sensor recording **auto-starts** when MOTORCYCLE mode is active and you're moving
- Lean angle and G-force update in real-time on the map dashboard
- If crash detection is enabled and a crash is detected, an alert dialog appears with:
  - **"I'm Okay"** — dismisses the alert
  - **"Emergency"** — sends SMS with GPS location to your emergency contact

### After a Ride

1. Open **My Places** → **Tracks** → Select your recorded track
2. View **Analysis** tab for lean angle and G-force charts
3. Check ride statistics: max lean, max G-force, corner count, ride score

### Curvy Road Navigation

1. Plan a route while in MOTORCYCLE mode
2. Enable **Prefer curvy roads** in motorcycle plugin settings
3. The router will prioritize winding tertiary and secondary roads over straight highways
4. View route statistics: Fun Score, curviness classification, corners per km

---

## What's Missing / Known Issues

> These are items that still need to be implemented before the app is fully production-ready. Contributions welcome!

### Critical (App may not build or features won't work)

| Issue | Description | Status |
|-------|-------------|--------|
| **Widget availability not registered** | Motorcycle widgets are NOT registered in `WidgetsAvailabilityHelper.java` for the MOTORCYCLE ApplicationMode. Users won't see the widgets in the widget picker by default. Need to add `MOTORCYCLE_LEAN_ANGLE`, `MOTORCYCLE_GFORCE`, `MOTORCYCLE_GFORCE_LATERAL`, `MOTORCYCLE_GFORCE_LONGITUDINAL` to the MOTORCYCLE mode's available widgets. | Missing |
| **No plugin settings UI** | There is no `PreferenceFragmentCompat` or settings screen for the motorcycle plugin. The preferences exist in code (filter coefficients, recording toggle, curvy roads toggle, crash detection toggle) but users have no way to change them from the app UI. Need a `MotorcycleSensorsSettingsFragment` with proper preference XML. | Missing |
| **Build not tested** | The code has been written and committed but the full OsmAnd project has NOT been compiled and tested. There may be compilation errors, missing imports, or API mismatches with the OsmAnd codebase. A full build test is needed. | Untested |
| **Curvy road routing not integrated with OsmAnd routing engine** | `MotorcycleRoutingHelper` defines routing parameter maps, but they are not wired into OsmAnd's `RouteCalculationParams` or `RoutingHelper`. The curvy road preference needs to be applied during actual route calculation, not just stored as parameters. | Partial |

### High (Important features not yet implemented)

| Issue | Description | Status |
|-------|-------------|--------|
| **No emergency contact configuration** | Crash detection sends an SMS, but there's no UI to configure the emergency contact phone number or customize the message template. | Missing |
| **No crash detection UI flow** | When a crash is detected, the `CrashDetectionHelper` fires a listener callback, but there's no Activity or Dialog that handles it. Need an `AlertDialog` with countdown timer, SMS sending, and emergency call functionality. | Missing |
| **No curvy road map overlay** | `TwistinessCalculator` computes road curvature and defines overlay colors, but there's no `OsmandMapTileView` overlay implementation to visualize twistiness on the map. Riders can't see which roads are twisty without planning a route. | Missing |
| **No ride analysis UI** | `MotorcycleSensorAnalysisHelper` computes corner events, ride scores, and distributions, but there's no dedicated Activity/Fragment to display these results. Need a "Ride Summary" screen. | Missing |
| **No lean angle heat map** | Planned feature — overlay on the map showing lean angle intensity along recorded tracks, color-coded by angle. Not implemented. | Planned |
| **Routing profile not properly separated** | The MOTORCYCLE mode inherits from CAR. Need a proper separate routing profile with motorcycle-specific speed assumptions, road restrictions, and the curvy road preference built-in. | Partial |
| **No custom motorcycle rendering style** | No dedicated map rendering style for motorcycles. Should highlight twisty roads, show fuel stations prominently, and use motorcycle-friendly POI categories. | Missing |

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

1. **Widget availability registration** — Quick fix, huge impact
2. **Plugin settings UI** — Makes the plugin actually configurable
3. **Build verification** — Test that it compiles and runs
4. **Crash detection UI flow** — Safety feature, critical for real use
5. **Ride analysis summary screen** — Makes post-ride data accessible
6. **Curvy road map overlay** — Visual twistiness on the map
7. **Unit tests** — Reliability for core algorithms
8. **Translations** — Multi-language support

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
fusedLean = alpha * accelRoll + (1 - alpha) * gyroIntegratedLean
```
Where `alpha = 0.02` — heavily trusting the gyroscope for dynamic lean angles (cornering) while using the accelerometer for long-term drift correction. The output is clamped to +/-60 degrees (realistic motorcycle lean range) and smoothed with a 5-sample moving average.

### G-Force Calculation

1. Rotate gravity vector from world frame to phone frame using roll/pitch
2. `dynamicAccel = measuredAccel - gravityComponent`
3. Convert to G-units (divide by 9.80665 m/s^2)
4. Clamp to +/-3.0G (max realistic motorcycle G-force)
5. Apply 5-sample moving average smoothing
6. Track peak values: peakLateralG, peakLongitudinalG, peakTotalG, peakBrakingG

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
