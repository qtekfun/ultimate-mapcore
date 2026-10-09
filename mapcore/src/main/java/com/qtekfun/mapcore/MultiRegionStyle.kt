/*  Copyright (C) 2026 UltimateGadget contributors (adapted from UltimateMaps, same author)

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

import org.json.JSONArray
import org.json.JSONObject

/** Turns the packaged style template into a style JSON. Pure, JVM-testable. */
internal object StyleTemplate {
    const val DIR_MARKER = "@MAPDIR@"

    fun assetName(theme: MapTheme) =
        if (theme == MapTheme.DARK) "$MAP_CORE_ASSET_DIR/style-dark.json" else "$MAP_CORE_ASSET_DIR/style-light.json"

    fun jsonEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * Builds the style for every installed region from the packaged single-source template. One PMTiles
 * per region: each gets its own vector source (`protomaps-<i>`) and a copy of every layer pointing to
 * it, layer-major so z-order stays correct across neighbours. With no region the style has no sources
 * (background only): it never references a file that does not exist.
 */
internal object MultiRegionStyle {
    const val MAX_SOURCES = 25
    const val SOURCE_PREFIX = "protomaps-"
    private const val TEMPLATE_SOURCE = "protomaps"

    data class Result(val json: String, val sources: Int, val layers: Int, val skipped: Int, val templateLayers: Int)

    fun sourceId(index: Int) = "$SOURCE_PREFIX$index"

    fun layerId(original: String, index: Int) = if (index == 0) original else "$original@$index"

    fun build(template: String, mapDir: String, pmtilesPaths: List<String>): Result {
        val style = JSONObject(template.replace(StyleTemplate.DIR_MARKER, StyleTemplate.jsonEscape(mapDir)))
        val proto = style.getJSONObject("sources").getJSONObject(TEMPLATE_SOURCE)
        val distinct = pmtilesPaths.distinct()
        val paths = distinct.take(MAX_SOURCES)
        val sources = JSONObject()
        paths.forEachIndexed { i, path ->
            val s = JSONObject(proto.toString())
            s.put("url", "pmtiles://file://$path")
            sources.put(sourceId(i), s)
        }
        val templateLayers = style.getJSONArray("layers")
        val layers = JSONArray()
        for (n in 0 until templateLayers.length()) {
            val layer = templateLayers.getJSONObject(n)
            if (!layer.has("source")) {
                layers.put(layer)
                continue
            }
            for (i in paths.indices) {
                val copy = JSONObject(layer.toString())
                copy.put("id", layerId(layer.getString("id"), i))
                copy.put("source", sourceId(i))
                layers.put(copy)
            }
        }
        style.put("sources", sources)
        style.put("layers", layers)
        return Result(style.toString(), paths.size, layers.length(), distinct.size - paths.size, templateLayers.length())
    }
}
