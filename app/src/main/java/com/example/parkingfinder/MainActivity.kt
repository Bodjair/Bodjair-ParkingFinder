package com.example.parkingfinder

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.parkingfinder.data.ParkingSpot
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName   // обов'язково для серверів плиток OSM
        }
        setContent { MaterialTheme { Screen(vm) } }
    }
}

private fun money(v: Double?): String = when {
    v == null -> "—"
    v == 0.0 -> "$0"
    else -> "$" + "%.2f".format(v)
}

/** "~" перед ціною означає демо-ціну (не реальну). */
private fun priceText(v: Double?, demo: Boolean) = (if (demo && v != null) "~" else "") + money(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        granted = res.values.any { it }
        if (granted) vm.refresh()
    }
    LaunchedEffect(Unit) {
        launcher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    val tierMap = remember(state.spots) { computeTiers(state.spots) }
    val badges = remember(state.spots, tierMap) {
        state.spots.associate { s ->
            val tier = tierMap[s.id] ?: Tier.MID
            s.id to BitmapDrawable(
                ctx.resources,
                priceBitmap(
                    ctx,
                    priceText(s.pricePerHour, s.isDemoPrice) + "/год",
                    priceText(s.pricePerDay, s.isDemoPrice) + "/доба",
                    tier,
                )
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Parking Finder") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (granted) vm.refresh() }) { Text("⟳") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        MapView(c).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(39.8283, -98.5795)) // центр США
                        }
                    },
                    update = { map ->
                        map.overlays.clear()
                        state.me?.let { me ->
                            if (map.tag != me) {
                                map.controller.setCenter(me)
                                map.tag = me
                            }
                            map.overlays.add(Marker(map).apply {
                                position = me
                                title = "Ви тут"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            })
                        }
                        // зелені додаємо останніми, щоб були поверх
                        state.spots.sortedByDescending { tierMap[it.id]?.ordinal ?: 1 }.forEach { s ->
                            map.overlays.add(Marker(map).apply {
                                position = GeoPoint(s.lat, s.lng)
                                icon = badges[s.id]
                                title = s.name
                                snippet = s.address
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                setOnMarkerClickListener { m, _ ->
                                    vm.select(s); m.showInfoWindow(); true
                                }
                            })
                        }
                        map.invalidate()
                    },
                    onRelease = { it.onDetach() },
                )
                Text(
                    "© OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }

            if (state.spots.any { it.isDemoPrice }) {
                Text(
                    "~ означає демо-ціну (не реальну). Ціни без ~ взяті з OpenStreetMap.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                items(state.spots, key = { it.id }) { s ->
                    SpotRow(s, tierMap[s.id] ?: Tier.MID) {
                        vm.select(s)
                        val uri = Uri.parse(
                            "geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}(${Uri.encode(s.name)})"
                        )
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: ActivityNotFoundException) {
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SpotRow(s: ParkingSpot, tier: Tier, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            if (s.address.isNotBlank()) Text(s.address, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                priceText(s.pricePerHour, s.isDemoPrice) + "/год",
                style = MaterialTheme.typography.titleMedium,
                color = Color(tier.argb),
            )
            Text(
                priceText(s.pricePerDay, s.isDemoPrice) + "/доба",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
