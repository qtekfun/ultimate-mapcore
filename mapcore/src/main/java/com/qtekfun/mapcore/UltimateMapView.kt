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

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File

/**
 * Self-contained MapLibre + PMTiles map. No app dependencies: the caller passes the tiles directory
 * (where `*.pmtiles` regions live, by convention `filesDir/maps`) and simple [LatLon] points.
 *
 * Lifecycle: the host must forward onStart/onResume/onPause/onStop/onDestroy/onLowMemory; the Compose
 * [UltimateMapView] does this automatically. Create off the UI only for the one-time asset copy.
 */
class UltimateMapEngine(context: Context, tilesDir: File) {

    private val appContext = context.applicationContext
    private val files = MapStyleFiles(appContext, tilesDir)
    private val main = Handler(Looper.getMainLooper())

    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var closed = false
    private var theme: MapTheme = MapTheme.DARK

    private var tapListener: ((LatLon) -> Unit)? = null

    // Pending data applied once the style is ready (and re-applied after a theme change).
    private var pendingLine: List<LatLon> = emptyList()
    private var pendingMarkers: List<LatLon> = emptyList()
    private var pendingUser: LatLon? = null

    // Native MapLibre logo + attribution are lifted above a bottom system inset (gesture/nav bar).
    private var bottomChromeInsetPx: Int = 0
    private var baseLogoBottom = -1
    private var baseAttrBottom = -1

    init {
        MapLibre.getInstance(appContext)
        view = MapView(appContext)
        view.getMapAsync { m ->
            if (closed) return@getMapAsync
            map = m
            applyChromeInset(m)
            m.addOnMapClickListener { p ->
                tapListener?.invoke(LatLon(p.latitude, p.longitude))
                tapListener != null
            }
            loadStyle()
        }
    }

    fun onMapTap(cb: ((LatLon) -> Unit)?) { tapListener = cb }

    /**
     * Lift the native MapLibre logo + attribution above a bottom system inset (e.g. the
     * navigation/gesture bar), in pixels. The map keeps rendering edge-to-edge; only the attribution
     * widgets move so they are not covered by system chrome.
     */
    fun setBottomChromeInset(px: Int) {
        if (bottomChromeInsetPx == px) return
        bottomChromeInsetPx = px
        map?.let { applyChromeInset(it) }
    }

    private fun applyChromeInset(m: MapLibreMap) {
        val ui = m.uiSettings
        if (baseLogoBottom < 0) {
            baseLogoBottom = ui.logoMarginBottom
            baseAttrBottom = ui.attributionMarginBottom
        }
        ui.setLogoMargins(ui.logoMarginLeft, ui.logoMarginTop, ui.logoMarginRight, baseLogoBottom + bottomChromeInsetPx)
        ui.setAttributionMargins(ui.attributionMarginLeft, ui.attributionMarginTop, ui.attributionMarginRight, baseAttrBottom + bottomChromeInsetPx)
    }

    fun setTheme(newTheme: MapTheme) {
        if (theme == newTheme) return
        theme = newTheme
        if (map != null) loadStyle()
    }

    fun setCamera(center: LatLon, zoom: Double) {
        map?.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(center.lat, center.lon)).zoom(zoom).build(),
            ),
        )
    }

    /** Draw the route/track line (and nothing else). */
    fun drawRoute(points: List<LatLon>) { pendingLine = points; pushLine() }

    /** Alias: a recorded workout track is drawn the same way. */
    fun drawTrack(points: List<LatLon>) = drawRoute(points)

    /** Small circle markers (e.g. the route waypoints or start/end). */
    fun markers(points: List<LatLon>) { pendingMarkers = points; pushMarkers() }

    /** Show (or clear, with null) the "my location" blue dot. Does not move the camera. */
    fun setUserLocation(point: LatLon?) { pendingUser = point; pushUser() }

    /** Fit the camera to the given points with padding. No-op for fewer than two points. */
    fun fitTo(points: List<LatLon>, paddingPx: Int = 96) {
        val m = map ?: return
        if (points.size < 2) {
            points.firstOrNull()?.let { setCamera(it, 14.0) }
            return
        }
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        val update = runCatching {
            CameraUpdateFactory.newLatLngBounds(bounds, paddingPx, paddingPx, paddingPx, paddingPx)
        }.getOrNull() ?: return
        m.moveCamera(update)
    }

    private fun loadStyle() {
        val m = map ?: return
        if (files.isInstalled()) {
            applyStyle(m)
        } else {
            Thread({
                runCatching { files.install() }
                main.post { if (!closed) map?.let { applyStyle(it) } }
            }, "mapcore-assets").start()
        }
    }

    private fun applyStyle(m: MapLibreMap) {
        val built = runCatching { files.style(theme) }.getOrNull() ?: return
        m.setStyle(Style.Builder().fromJson(built.json)) { s ->
            if (closed) return@setStyle
            style = s
            val dark = theme == MapTheme.DARK
            s.addSource(GeoJsonSource(ROUTE_SRC))
            s.addLayer(
                LineLayer(ROUTE_CASING, ROUTE_SRC).withProperties(
                    lineColor(if (dark) 0xFF0A2A5E.toInt() else 0xFFFFFFFF.toInt()),
                    lineWidth(9f), lineOpacity(0.8f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addLayer(
                LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
                    lineColor(ROUTE_COLOR), lineWidth(5f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addSource(GeoJsonSource(MARKERS_SRC))
            s.addLayer(
                CircleLayer(MARKERS_LAYER, MARKERS_SRC).withProperties(
                    circleRadius(6f), circleColor(ROUTE_COLOR),
                    circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(2f),
                ),
            )
            s.addSource(GeoJsonSource(USER_SRC))
            s.addLayer(
                CircleLayer(USER_HALO, USER_SRC).withProperties(
                    circleRadius(16f), circleColor(USER_COLOR), circleOpacity(0.18f),
                ),
            )
            s.addLayer(
                CircleLayer(USER_DOT, USER_SRC).withProperties(
                    circleRadius(7f), circleColor(USER_COLOR),
                    circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(3f),
                ),
            )
            pushLine()
            pushMarkers()
            pushUser()
        }
    }

    private fun pushLine() {
        val src = style?.getSourceAs<GeoJsonSource>(ROUTE_SRC) ?: return
        if (pendingLine.size < 2) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }
        val line = LineString.fromLngLats(pendingLine.map { Point.fromLngLat(it.lon, it.lat) })
        src.setGeoJson(Feature.fromGeometry(line))
    }

    private fun pushMarkers() {
        val src = style?.getSourceAs<GeoJsonSource>(MARKERS_SRC) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(
                pendingMarkers.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) },
            ),
        )
    }

    private fun pushUser() {
        val src = style?.getSourceAs<GeoJsonSource>(USER_SRC) ?: return
        val p = pendingUser
        if (p == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        } else {
            src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
        }
    }

    // Lifecycle passthrough.
    fun onCreate(bundle: Bundle?) = view.onCreate(bundle)
    fun onStart() = view.onStart()
    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onStop() = view.onStop()
    fun onLowMemory() = view.onLowMemory()
    fun onDestroy() {
        closed = true
        view.onDestroy()
    }

    private companion object {
        const val ROUTE_SRC = "ug-route-src"
        const val ROUTE_CASING = "ug-route-casing"
        const val ROUTE_LAYER = "ug-route-line"
        const val MARKERS_SRC = "ug-markers-src"
        const val MARKERS_LAYER = "ug-markers-layer"
        const val USER_SRC = "ug-user-src"
        const val USER_HALO = "ug-user-halo"
        const val USER_DOT = "ug-user-dot"
        const val ROUTE_COLOR = 0xFF2F6FD6.toInt()
        const val USER_COLOR = 0xFF2F8FFF.toInt()
    }
}

/**
 * Compose host for [UltimateMapEngine]. Forwards the Android lifecycle to the MapView and hands the
 * engine to [onReady] once created so the caller can draw routes/tracks and react to taps.
 *
 * @param tilesDir where `*.pmtiles` regions live (by convention `context.filesDir/maps`).
 */
@Composable
fun UltimateMapView(
    modifier: Modifier = Modifier,
    tilesDir: File,
    theme: MapTheme = MapTheme.DARK,
    onReady: (UltimateMapEngine) -> Unit = {},
) {
    val context = LocalContext.current
    val engine = remember { UltimateMapEngine(context, tilesDir).also { it.setTheme(theme) } }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> engine.onStart()
                Lifecycle.Event.ON_RESUME -> engine.onResume()
                Lifecycle.Event.ON_PAUSE -> engine.onPause()
                Lifecycle.Event.ON_STOP -> engine.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            engine.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            engine.onCreate(null)
            engine.onStart()
            engine.onResume()
            onReady(engine)
            engine.view
        },
    )
}
