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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM guards for the core-owned public types and the style helpers. Anything touching
 * `org.json` or the MapLibre SDK needs an instrumented/Robolectric environment, so this stays to
 * the parts that are pure Kotlin. The MapLibre-backed [UltimateMapEngine] is covered by
 * `assembleRelease` compiling the whole public API against the SDK.
 */
class MapCoreApiTest {

    @Test
    fun cameraStateDefaultsAreNorthUpAndFlat() {
        val s = CameraState(LatLon(40.4, -3.7), 12.0)
        assertEquals(0.0, s.bearing, 0.0)
        assertEquals(0.0, s.tilt, 0.0)
        // copy() is what resetNorth()/the ease helpers rely on to tweak one field.
        val reset = s.copy(bearing = 0.0, tilt = 0.0)
        assertEquals(s.center, reset.center)
        assertEquals(s.zoom, reset.zoom, 0.0)
    }

    @Test
    fun cameraStateCarriesBearingAndTilt() {
        val s = CameraState(LatLon(0.0, 0.0), 16.0, bearing = 90.0, tilt = 45.0)
        assertEquals(90.0, s.bearing, 0.0)
        assertEquals(45.0, s.tilt, 0.0)
    }

    @Test
    fun styleTemplatePicksThemeAsset() {
        assertEquals("mapcore/style-dark.json", StyleTemplate.assetName(MapTheme.DARK))
        assertEquals("mapcore/style-light.json", StyleTemplate.assetName(MapTheme.LIGHT))
    }

    @Test
    fun styleTemplateEscapesJsonStrings() {
        assertEquals("""a\\b\"c""", StyleTemplate.jsonEscape("""a\b"c"""))
    }

    @Test
    fun multiRegionSourceAndLayerIdsAreStableAcrossRegions() {
        assertEquals("protomaps-0", MultiRegionStyle.sourceId(0))
        assertEquals("protomaps-3", MultiRegionStyle.sourceId(3))
        // The first region keeps the original layer id; later regions get a suffixed copy.
        assertEquals("roads", MultiRegionStyle.layerId("roads", 0))
        assertEquals("roads@2", MultiRegionStyle.layerId("roads", 2))
    }
}
