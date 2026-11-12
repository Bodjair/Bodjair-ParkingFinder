package com.example.parkingfinder

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.parkingfinder.data.ParkingSpot
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen(vm) } }
    }
}

private fun money(v: Double?) = if (v == null) "—" else "$" + "%.2f".format(v)

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

    val camera = rememberCameraPositionState()
    LaunchedEffect(state.me) {
        state.me?.let { camera.position = CameraPosition.fromLatLngZoom(it, 15f) }
    }

    val tierMap = remember(state.spots) { computeTiers(state.spots) }

    Scaffold(
        topBar ={ TopAppBar(title = { Text("Parking Finder") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { if (granted) vm.refresh() }) { Text("⟳") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = camera,
                    properties = MapProperties(isMyLocationEnabled = granted),
                ) {
                    state.spots.forEach { s ->
                        val tier = tierMap[s.id] ?: Tier.MID
                        val icon = remember(s.id, s.pricePerHour, s.pricePerDay, tier) {
                            priceBitmap(
                                ctx,
                                money(s.pricePerHour) + "/год",
                                money(s.pricePerDay) + "/доба",
                                tier,
                            )
                        }
                        Marker(
                            state = rememberMarkerState(key = s.id, position = LatLng(s.lat, s.lng)),
                            title = s.name,
                            snippet = "${money(s.pricePerHour)}/год · ${money(s.pricePerDay)}/доба",
                            icon = icon,
                            anchor = Offset(0.5f, 1f),
                            zIndex = if (tier == Tier.CHEAP) 2f else 1f,
                            onClick = { vm.select(s); false },
                        )
                    }
                }
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            }

            if (state.spots.any { it.isDemoPrice }) {
                Text(
                    "Ціни демонстраційні, не реальні",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            LazyColumn(Modifier.weight(1f)) {
                items(state.spots, key = { it.id }) { s ->
                    SpotRow(s, tierMap[s.id] ?: Tier.MID, selected = s.id == state.selected?.id) {
                        vm.select(s)
                        ctx.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("google.navigation:q=${s.lat},${s.lng}")
                            ).setPackage("com.google.android.apps.maps")
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun SpotRow(s: ParkingSpot, tier: Tier, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            Text(s.address, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                money(s.pricePerHour) + "/год",
                style = MaterialTheme.typography.titleMedium,
                color = Color(tier.argb),
            )
            Text(money(s.pricePerDay) + "/доба", style = MaterialTheme.typography.bodySmall)
        }
    }
}
