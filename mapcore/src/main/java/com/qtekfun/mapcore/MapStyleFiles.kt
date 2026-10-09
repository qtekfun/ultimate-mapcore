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

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Locates the map data. MapLibre cannot read `file://` under `Android/data`, so sprites and glyphs are
 * copied once from the APK assets into the internal `filesDir/mapcore`; PMTiles live in [tilesDir]
 * (by convention `filesDir/maps`), filled by the region downloader (outside the core).
 */
internal class MapStyleFiles(private val context: Context, private val tilesDir: File) {
    private val assetsDir get() = File(context.filesDir, MAP_CORE_ASSET_DIR)

    /** Every PMTiles file to draw (sorted); empty when nothing is installed -> background-only style. */
    fun pmtilesList(): List<File> =
        tilesDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }?.sortedBy { it.name }.orEmpty()

    fun tilesSignature(): String = pmtilesList().joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }

    fun isInstalled(): Boolean = File(assetsDir, MARKER).readTextOrNull() == ASSET_VERSION

    /** Copies sprites and glyphs from the APK to `filesDir/mapcore` if the packaged version changed. Blocking IO. */
    fun install() {
        if (isInstalled()) return
        copyTree(context.assets, MAP_CORE_ASSET_DIR, assetsDir)
        File(assetsDir, MARKER).writeText(ASSET_VERSION)
    }

    /** Style for [theme] with one source per installed region; background only when none. */
    fun style(theme: MapTheme): MultiRegionStyle.Result {
        val template = context.assets.open(StyleTemplate.assetName(theme)).use { it.readBytes().toString(Charsets.UTF_8) }
        return MultiRegionStyle.build(template, assetsDir.absolutePath, pmtilesList().map { it.absolutePath })
    }

    private fun copyTree(am: AssetManager, path: String, dest: File) {
        val children = am.list(path).orEmpty()
        if (children.isEmpty()) {
            // style-*.json are read straight from assets, not copied.
            if (path.endsWith(".json") && path.substringAfterLast('/').startsWith("style-")) return
            dest.parentFile?.mkdirs()
            am.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (c in children) copyTree(am, "$path/$c", File(dest, c))
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private companion object {
        const val MARKER = ".version"
        const val ASSET_VERSION = "1"
    }
}
