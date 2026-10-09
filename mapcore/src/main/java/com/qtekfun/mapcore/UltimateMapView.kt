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
    private var mapReadyListener: ((MapLibreMap, Style) -> Unit)? = null
    private var cameraGestureListener: (() -> Unit)? = null
    private var cameraIdleListener: (() -> Unit)? = null

    // Which style is applied. Default = the packaged PMTiles multi-region style; a consumer may swap
    // in its own JSON or URI. reloadStyle()/refreshTiles() reapply whatever is active here.
    private var styleSource: StyleSource = StyleSource.DefaultMultiRegion

    // Pending data applied once the style is ready (and re-applied after a theme change).
    private var pendingLine: List<LatLon> = emptyList()
    private var pendingMarkers: List<LatLon> = emptyList()
    private var pendingUser: LatLon? = null

    // Native MapLibre logo + attribution are lifted above a bottom system inset (gesture/nav bar).
    private var bottomChromeInsetPx: Int = 0
    private var baseLogoBottom = -1
    private var baseAttrBottom = -1

    /**
     * The underlying MapLibre map, or null until the map finishes creating. Exposed so a consumer
     * (e.g. UltimateMaps navigation) can drive the SDK directly — add its OWN sources and layers
     * (alternative routes, transport, chargers, bike-share, ZBE polygons, 3D buildings as a
     * fill-extrusion layer, …), read gestures, etc. Prefer [onMapReady] when you need to (re)add
     * layers after every style (re)load; use this for one-off reads. Main thread only.
     */
    val mapLibreMap: MapLibreMap? get() = map

    /**
     * The current loaded [Style], or null until a style finishes loading. Add your fill-extrusion
     * (3D buildings), custom sources/layers and images here. It becomes invalid after every theme
     * change, [setStyle], [reloadStyle] or [refreshTiles], so use [onMapReady] to re-add your layers
     * each time a new style loads. Main thread only.
     */
    val currentStyle: Style? get() = style

    init {
        MapLibre.getInstance(appContext)
        view = MapView(appContext)
        view.getMapAsync { m ->
            if (closed) return@getMapAsync
            map = m
            applyChromeInset(m)
            // Rotate and tilt gestures are needed for navigation; MapLibre enables them by default
            // but we set them explicitly so the base shared view always allows them.
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = true
            m.addOnMapClickListener { p ->
                tapListener?.invoke(LatLon(p.latitude, p.longitude))
                tapListener != null
            }
            // A user-driven camera gesture (so navigation can stop following and offer "recenter").
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    cameraGestureListener?.invoke()
                }
            }
            // The camera settled (useful to refresh the visible viewport / reload layers).
            m.addOnCameraIdleListener { cameraIdleListener?.invoke() }
            loadStyle()
        }
    }

    fun onMapTap(cb: ((LatLon) -> Unit)?) { tapListener = cb }

    /**
     * Fired every time a style finishes loading — on first load and after each theme change,
     * [setStyle], [reloadStyle] or [refreshTiles]. This is the hook for a consumer to (re)add its
     * OWN sources, layers and images (3D buildings fill-extrusion, alternative routes, chargers,
     * bike-share, ZBE polygons, …): because a style reload drops every custom layer, re-adding them
     * here keeps them alive across reloads. The core's own route/track/marker/user layers are added
     * before this callback runs, so consumer layers sit on top of them. Pass null to clear.
     */
    fun onMapReady(cb: ((MapLibreMap, Style) -> Unit)?) {
        mapReadyListener = cb
        val m = map
        val s = style
        if (cb != null && m != null && s != null && s.isFullyLoaded) cb(m, s)
    }

    /**
     * Reports when the USER starts moving the camera with a gesture (not animations the app asked
     * for), so a navigation screen can stop following and offer "recenter". Called once per gesture
     * start; pass null to clear.
     */
    fun onCameraGesture(cb: (() -> Unit)?) { cameraGestureListener = cb }

    /**
     * Reports when the camera settles after any move (gesture or animation). Read [cameraState] in
     * the callback to refresh the visible viewport or reload viewport-bound layers. Pass null to clear.
     */
    fun onCameraIdle(cb: (() -> Unit)?) { cameraIdleListener = cb }

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
        // A theme change only re-renders when the default packaged style is active; a custom style
        // chosen by the consumer owns its own day/night handling.
        if (map != null && styleSource == StyleSource.DefaultMultiRegion) loadStyle()
    }

    /**
     * Load an ARBITRARY style from a JSON string, replacing the packaged PMTiles multi-region style.
     * The consumer owns the sprites/glyphs/sources this style references. The core's route/track/
     * marker/user layers are re-added on top and [onMapReady] fires once loaded. Call [setDefaultStyle]
     * to return to the packaged style.
     */
    fun setStyle(json: String) {
        styleSource = StyleSource.CustomJson(json)
        if (map != null) loadStyle()
    }

    /**
     * Load an arbitrary style from a URI (`file://`, `asset://`, `http(s)://`, `mapbox://`/`maplibre://`),
     * replacing the packaged style. See [setStyle] for the layer/lifecycle contract.
     */
    fun setStyleUri(uri: String) {
        styleSource = StyleSource.CustomUri(uri)
        if (map != null) loadStyle()
    }

    /** Return to the packaged PMTiles multi-region style (the default) after a custom [setStyle]/[setStyleUri]. */
    fun setDefaultStyle() {
        styleSource = StyleSource.DefaultMultiRegion
        if (map != null) loadStyle()
    }

    /**
     * Reapply the current style from scratch (packaged or custom). Use it when the inputs changed
     * under the engine — e.g. the consumer edited the custom style JSON, or new `.pmtiles` regions
     * were installed — and the map must pick them up. Any custom layers must be re-added in
     * [onMapReady], which fires again when the reload completes.
     */
    fun reloadStyle() {
        if (map != null) loadStyle()
    }

    /**
     * Refresh the tiles of the packaged multi-region style, picking up `.pmtiles` regions that were
     * added, replaced or removed in `tilesDir`. Equivalent to [reloadStyle] while the default style
     * is active; a no-op for a custom style (the consumer controls its own sources there).
     */
    fun refreshTiles() {
        if (map != null && styleSource == StyleSource.DefaultMultiRegion) loadStyle()
    }

    fun setCamera(center: LatLon, zoom: Double) {
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition(center, zoom, null, null)))
    }

    /**
     * Instantly move the full navigation camera: [center] + [zoom] plus optional [bearing] (degrees
     * clockwise from north, 0 = north up) and [tilt] (pitch, 0 = flat, up to 60 for the 3D driving
     * view). Null bearing/tilt keep the current value. No animation (safe to call every GNSS frame).
     */
    fun setCamera(center: LatLon, zoom: Double, bearing: Double? = null, tilt: Double? = null) {
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition(center, zoom, bearing, tilt)))
    }

    /** Instantly move to a full [CameraState]. */
    fun setCamera(state: CameraState) = setCamera(state.center, state.zoom, state.bearing, state.tilt)

    /**
     * Flight-style animated move to the given camera (MapLibre `animateCamera`): accelerates, arcs
     * out and settles. Good for a one-off "fly to" to a place or route. [durationMs] is the animation
     * length. Null bearing/tilt keep the current value.
     */
    fun animateCamera(center: LatLon, zoom: Double, bearing: Double? = null, tilt: Double? = null, durationMs: Int = 600) {
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition(center, zoom, bearing, tilt)), durationMs)
    }

    /** Flight-style animated move to a full [CameraState]. */
    fun animateCamera(state: CameraState, durationMs: Int = 600) =
        animateCamera(state.center, state.zoom, state.bearing, state.tilt, durationMs)

    /** Alias for [animateCamera]: the flight ("fly to") animation. */
    fun flyTo(state: CameraState, durationMs: Int = 600) = animateCamera(state, durationMs)

    /**
     * Ease (constant ground speed) animated move to the given camera (MapLibre `easeCamera`). Smoother
     * than [animateCamera] for the short, frequent camera nudges of turn-by-turn following.
     * Null bearing/tilt keep the current value.
     */
    fun easeCamera(center: LatLon, zoom: Double, bearing: Double? = null, tilt: Double? = null, durationMs: Int = 500) {
        map?.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition(center, zoom, bearing, tilt)), durationMs)
    }

    /** Ease animated move to a full [CameraState]. */
    fun easeCamera(state: CameraState, durationMs: Int = 500) =
        easeCamera(state.center, state.zoom, state.bearing, state.tilt, durationMs)

    /** Alias for [easeCamera], matching the common `easeTo` naming. */
    fun easeTo(state: CameraState, durationMs: Int = 500) = easeCamera(state, durationMs)

    /** Current full camera state (centre, zoom, bearing, tilt), or null before the map is ready. */
    fun cameraState(): CameraState? {
        val p = map?.cameraPosition ?: return null
        val t = p.target ?: return null
        return CameraState(LatLon(t.latitude, t.longitude), p.zoom, p.bearing, p.tilt)
    }

    /** Current camera centre + zoom, or null before the map is ready. */
    fun camera(): Pair<LatLon, Double>? = cameraState()?.let { it.center to it.zoom }

    /** Animate bearing and tilt back to north-up / flat, keeping the current centre and zoom. */
    fun resetNorth(durationMs: Int = 300) {
        val s = cameraState() ?: return
        easeCamera(s.copy(bearing = 0.0, tilt = 0.0), durationMs)
    }

    /** Enable/disable the pan (scroll) gesture. */
    fun setScrollGesturesEnabled(enabled: Boolean) { map?.uiSettings?.isScrollGesturesEnabled = enabled }

    /** Enable/disable the pinch/double-tap zoom gestures. */
    fun setZoomGesturesEnabled(enabled: Boolean) { map?.uiSettings?.isZoomGesturesEnabled = enabled }

    /** Enable/disable the two-finger rotate gesture (bearing). */
    fun setRotateGesturesEnabled(enabled: Boolean) { map?.uiSettings?.isRotateGesturesEnabled = enabled }

    /** Enable/disable the two-finger vertical shove gesture (tilt/pitch). */
    fun setTiltGesturesEnabled(enabled: Boolean) { map?.uiSettings?.isTiltGesturesEnabled = enabled }

    /** Enable/disable scroll, zoom, rotate and tilt gestures in one call. */
    fun setAllGesturesEnabled(enabled: Boolean) { map?.uiSettings?.setAllGesturesEnabled(enabled) }

    private fun cameraPosition(center: LatLon, zoom: Double, bearing: Double?, tilt: Double?): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(center.lat, center.lon))
            .zoom(zoom)
            .apply { bearing?.let { bearing(it) }; tilt?.let { tilt(it) } }
            .build()

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
        when (val src = styleSource) {
            StyleSource.DefaultMultiRegion ->
                // The packaged style needs its sprites/glyphs copied out of the APK once (blocking IO).
                if (files.isInstalled()) {
                    applyStyle(m, defaultStyleBuilder())
                } else {
                    Thread({
                        runCatching { files.install() }
                        main.post { if (!closed) map?.let { applyStyle(it, defaultStyleBuilder()) } }
                    }, "mapcore-assets").start()
                }
            is StyleSource.CustomJson -> applyStyle(m, Style.Builder().fromJson(src.json))
            is StyleSource.CustomUri -> applyStyle(m, Style.Builder().fromUri(src.uri))
        }
    }

    /** Builder for the packaged multi-region PMTiles style of the current theme, or null if it fails to build. */
    private fun defaultStyleBuilder(): Style.Builder? =
        runCatching { files.style(theme) }.getOrNull()?.let { Style.Builder().fromJson(it.json) }

    private fun applyStyle(m: MapLibreMap, builder: Style.Builder?) {
        if (builder == null) return
        m.setStyle(builder) { s ->
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
            // Let the consumer (re)add its own sources/layers on top of the fresh style.
            mapReadyListener?.invoke(m, s)
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

/** Which style the engine renders: the packaged PMTiles multi-region style, or a consumer-supplied one. */
private sealed interface StyleSource {
    data object DefaultMultiRegion : StyleSource
    data class CustomJson(val json: String) : StyleSource
    data class CustomUri(val uri: String) : StyleSource
}

/**
 * Compose host for [UltimateMapEngine]. Forwards the Android lifecycle to the MapView and hands the
 * engine to [onReady] once created so the caller can draw routes/tracks and react to taps.
 *
 * @param tilesDir where `*.pmtiles` regions live (by convention `context.filesDir/maps`).
 * @param onReady called once with the engine, for the simple viewer use (draw route, set camera, …).
 * @param onMapReady optional access to the raw MapLibre map + style, re-fired after every style
 *   (re)load, so a consumer (UltimateMaps navigation) can add and re-add its own sources/layers.
 */
@Composable
fun UltimateMapView(
    modifier: Modifier = Modifier,
    tilesDir: File,
    theme: MapTheme = MapTheme.DARK,
    onReady: (UltimateMapEngine) -> Unit = {},
    onMapReady: ((MapLibreMap, Style) -> Unit)? = null,
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
            onMapReady?.let { engine.onMapReady(it) }
            onReady(engine)
            engine.view
        },
    )
}
