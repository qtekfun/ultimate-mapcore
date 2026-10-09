# ultimate-mapcore

A self-contained, reusable **MapLibre + PMTiles** map view for the Ultimate app
family (UltimateGadget and, later, UltimateMaps). It renders an offline
multi-region basemap from `.pmtiles` files the host app installs, plus a
route/track line, markers and a "my location" dot — with no Google Play
Services and no network dependency for rendering.

It was extracted verbatim (package rename only) from the in-app `mapcore`
package of [UltimateGadget](../UltimateGadget), so both apps can consume one
source of truth instead of copy-pasting it.

- **Module:** `:mapcore` (`com.android.library`, Jetpack Compose)
- **Package / namespace:** `com.qtekfun.mapcore`
- **minSdk:** 23 · **compileSdk:** 37
- **Exposes:** `org.maplibre.gl:android-sdk:13.6.1` as an `api` dependency, so
  consumers inherit the MapLibre SDK automatically.

## Public API

```kotlin
import com.qtekfun.mapcore.UltimateMapView   // @Composable host
import com.qtekfun.mapcore.UltimateMapEngine  // setTheme/setCamera/fitTo, drawRoute/drawTrack, markers, setUserLocation, onMapTap, lifecycle hooks
import com.qtekfun.mapcore.MyLocationButton   // @Composable FAB (LocationManager, no Play Services)
import com.qtekfun.mapcore.LatLon             // geographic point
import com.qtekfun.mapcore.MapTheme           // DARK / LIGHT
```

```kotlin
UltimateMapView(
    modifier = Modifier.fillMaxSize(),
    tilesDir = File(context.filesDir, "maps"),  // where *.pmtiles regions live
    theme = MapTheme.DARK,
    onReady = { engine ->
        engine.drawRoute(points)   // points: List<LatLon>
        engine.fitTo(points)
    },
)
```

The map style assets (style JSON, sprites, Noto glyph PBFs) ship **inside the
library** under `src/main/assets/mapcore/`. `MapStyleFiles` copies sprites and
glyphs out of the APK into `filesDir/mapcore` on first use and reads the style
templates straight from assets, so the asset path convention (`assets/mapcore/`)
must be preserved — it is, by living in this module's `main` source set.

The caller is responsible only for filling `tilesDir` with `.pmtiles` regions
(the region downloader lives outside the core).

## How to consume it

### Option A — Git submodule + Gradle `includeBuild` (recommended)

In the consuming project (e.g. UltimateGadget):

```bash
git submodule add <ultimate-mapcore remote URL> ultimate-mapcore
```

`settings.gradle(.kts)` — include the module directly:

```kotlin
include(":mapcore")
project(":mapcore").projectDir = file("ultimate-mapcore/mapcore")
```

Make sure the consuming project's dependency repositories include
`google()` and `mavenCentral()` (they already do in UltimateGadget).

`app/build.gradle(.kts)`:

```kotlin
implementation(project(":mapcore"))   // MapLibre comes transitively via `api`
```

### Option B — Prebuilt AAR

```bash
./gradlew :mapcore:assembleRelease
# -> mapcore/build/outputs/aar/mapcore-release.aar
```

Drop the AAR in the consumer and add the MapLibre dependency yourself (an AAR
does not carry transitive Gradle metadata):

```kotlin
implementation(files("libs/mapcore-release.aar"))
implementation("org.maplibre.gl:android-sdk:13.6.1")
```

## Build

```bash
./gradlew :mapcore:assembleRelease   # builds the AAR
```

Requires JDK 21 and an Android SDK with platform 37 (set `sdk.dir` in
`local.properties`). Versions (AGP, Kotlin, Compose BOM, MapLibre) are pinned in
`gradle/libs.versions.toml` and kept **in lock-step with UltimateGadget** — bump
them in both repos together.

## Migration notes

### UltimateGadget → consume this submodule

The in-app copy currently lives at
`app/src/main/java/nodomain/freeyourgadget/gadgetbridge/mapcore/` with its assets
at `app/src/main/assets/mapcore/`. To switch to the submodule:

1. Add the submodule and wire the module in `settings.gradle.kts`
   (see *Option A* above).
2. In `app/build.gradle`, add `implementation(project(":mapcore"))`. The
   `org.maplibre.gl:android-sdk` dependency can then be **removed** from the app
   (it arrives transitively via mapcore's `api`), unless other app code uses
   MapLibre directly.
3. Delete the in-app package
   `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/mapcore/` **and** the
   assets directory `app/src/main/assets/mapcore/` (both now come from the
   library AAR). Keep any JVM unit tests for `MultiRegionStyle`/`StyleTemplate`
   only if you move them into this module.
4. Change the imports in the two map screens from
   `nodomain.freeyourgadget.gadgetbridge.mapcore.*` to `com.qtekfun.mapcore.*`:
   - `UltimateWorkoutMapActivity`
   - `UltimateRoutePlannerActivity`
   (`UltimateMapView`, `UltimateMapEngine`, `MyLocationButton`, `LatLon`,
   `MapTheme` are the symbols to re-point.)
5. Build: `./gradlew assembleMainlineDebug`.

Note: `MultiRegionStyle` and `StyleTemplate` are `internal` to the package, so
they are not visible to the app — the app only ever used the public API above,
which keeps this migration a pure import swap.

### UltimateMaps → consume this submodule

Same steps as UltimateGadget: add the submodule, `include(":mapcore")` +
`projectDir` in settings, `implementation(project(":mapcore"))`, and import
`com.qtekfun.mapcore.*`. Because MapLibre is exposed as `api`, UltimateMaps gets
the SDK transitively. The glyphs/sprites/style ship with the library, so
UltimateMaps only needs to provide a `tilesDir` with its `.pmtiles` regions.

## Licensing

Source code: **AGPL-3.0** (see `LICENSE`) — it derives from UltimateGadget,
which is AGPL-3.0.

Bundled map assets keep their upstream licenses (Noto fonts OFL-1.1, Protomaps
style/sprites permissive, OpenStreetMap data ODbL-1.0) — see `NOTICE`. Any UI
built on this map must keep the "© OpenStreetMap contributors" attribution
visible (it is already carried in the style's `attribution` field and shown by
MapLibre's attribution widget).
