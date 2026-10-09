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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal

/**
 * A "my location" FAB for the map. No network and no Google Play Services: it reads the fix from the
 * Android [LocationManager] (last-known for an instant center, then a fresh single fix to refine).
 * The caller decides what to do with the point (center the camera, draw the dot via
 * [UltimateMapEngine.setUserLocation], …). Colors default to the Material theme so it also works in
 * the companion app; callers with a palette can override them.
 */
@Composable
fun MyLocationButton(
    onLocation: (LatLon) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onLocating: () -> Unit = {},
    onUnavailable: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) fetchLocation(context, onLocating, onLocation, onUnavailable)
        else onUnavailable("Permiso de ubicación denegado")
    }
    FloatingActionButton(
        onClick = {
            if (hasLocationPermission(context)) {
                fetchLocation(context, onLocating, onLocation, onUnavailable)
            } else {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        },
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Icon(Icons.Filled.MyLocation, contentDescription = "Mi ubicación")
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission") // guarded by hasLocationPermission / the permission launcher
private fun fetchLocation(
    context: Context,
    onLocating: () -> Unit,
    onLocation: (LatLon) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null) {
        onUnavailable("Sin servicio de ubicación")
        return
    }
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    // Instant center from the freshest cached fix, if any.
    val last = providers
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    if (last != null) onLocation(LatLon(last.latitude, last.longitude))

    // Then a fresh single fix to refine.
    val provider = when {
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
        runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
        else -> null
    }
    if (provider == null) {
        if (last == null) onUnavailable("Activa la ubicación (GPS) del teléfono")
        return
    }
    if (last == null) onLocating()
    runCatching {
        LocationManagerCompat.getCurrentLocation(
            lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(context),
        ) { loc ->
            if (loc != null) onLocation(LatLon(loc.latitude, loc.longitude))
            else if (last == null) onUnavailable("No se pudo obtener la ubicación")
        }
    }.onFailure { if (last == null) onUnavailable("No se pudo obtener la ubicación") }
}
