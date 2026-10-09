/*  Copyright (C) 2026 UltimateGadget contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package com.qtekfun.mapcore

/**
 * Shared map core (MapLibre + PMTiles), intended to be reused across the Ultimate family
 * (UltimateGadget and, later, UltimateMaps). It is deliberately self-contained:
 *
 *  - It depends only on Android, MapLibre (`org.maplibre.gl:android-sdk`), org.json and its own
 *    types ([LatLon], [MapTheme]). It must NOT reference anything app-specific (no GBApplication,
 *    no DeviceManager, no app strings/resources), so it can be lifted out as-is.
 *  - Its style assets live under `assets/mapcore/` (style-dark/light.json, sprites, fonts/glyphs).
 *  - PMTiles regions are read from a tiles directory the caller passes in (by convention
 *    the `.pmtiles` files under `filesDir/maps`); the downloader that fills it is outside the core.
 *
 * To extract it to a Gradle module `:map-core` later, move this whole `mapcore` package plus the
 * `assets/mapcore/` directory and the `org.maplibre.gl:android-sdk` dependency. Nothing else in
 * Gadgetbridge is referenced from here.
 *
 * Public API: [UltimateMapView] (Compose) + [UltimateMapEngine] (setTheme/setCamera/fitTo,
 * drawRoute, drawTrack, markers, onMapTap, and the MapView lifecycle hooks).
 *
 * Style rendering is adapted from UltimateMaps (same author), stripped down to the base map plus a
 * route/track line and markers.
 */
internal const val MAP_CORE_ASSET_DIR = "mapcore"

/** A geographic point. Core-owned type so the public API carries no app classes. */
data class LatLon(val lat: Double, val lon: Double)

/** The two packaged styles. */
enum class MapTheme { DARK, LIGHT }

/**
 * Full camera state. Core-owned value type so the public API carries no MapLibre classes:
 *
 *  - [center] is the map centre.
 *  - [zoom] is the MapLibre zoom level (0 = whole world).
 *  - [bearing] is the map rotation in degrees clockwise from north (0 = north up).
 *  - [tilt] is the camera pitch in degrees from straight down (0 = flat, up to 60 in navigation).
 *
 * It mirrors the fields of MapLibre's `CameraPosition` so a consumer's navigation camera can be
 * expressed without importing the SDK. See [UltimateMapEngine.cameraState]/[UltimateMapEngine.setCamera]/
 * [UltimateMapEngine.animateCamera]/[UltimateMapEngine.easeCamera].
 */
data class CameraState(
    val center: LatLon,
    val zoom: Double,
    val bearing: Double = 0.0,
    val tilt: Double = 0.0,
)
