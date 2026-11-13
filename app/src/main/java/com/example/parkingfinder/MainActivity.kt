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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.parkingfinder.data.ParkingSpot
import com.example.parkingfinder.data.Place
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.Locale
import kotlin.math.abs

/** Плитки Esri World Street Map (без ключа; потрібна вказівка авторства). */
private val EsriStreet = object : OnlineTileSourceBase(
    "EsriWorldStreet", 0, 19, 256, ".png",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
    "Tiles © Esri",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "ParkingFinder/0.3 (Android; github.com/Bodjair/Bodjair-ParkingFinder)"
        }
        setContent { AppTheme { Screen(vm) } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1558D6),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE7FF),
            onPrimaryContainer = Color(0xFF0B2A66),
            secondaryContainer = Color(0xFFDCE7FF),
            background = Color(0xFFF5F7FB),
            surface = Color.White,
            surfaceVariant = Color(0xFFEEF1F7),
        ),
        content = content,
    )
}

// ---------- форматування ----------

private fun money(v: Double?): String = when {
    v == null -> "—"
    v == 0.0 -> "$0"
    else -> "$" + String.format(Locale.US, "%.2f", v)
}

/** "~" перед ціною означає демо-ціну (не реальну). */
private fun priceText(v: Double?, demo: Boolean) = (if (demo && v != null) "~" else "") + money(v)

private fun miles(m: Float) = String.format(Locale.US, "%.1f mi", m / 1609.34)

// ---------- головний екран ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var granted by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }

    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        granted = res.values.any { it }
        asked = true
        if (granted) vm.refresh()
    }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    val visible = remember(state.spots, state.filters) { applyFilters(state.spots, state.filters) }
    val tierMap = remember(visible) { computeTiers(visible) }
    val badges = remember(visible, tierMap, state.selectedId) {
        visible.associate { s ->
            s.id to BitmapDrawable(
                ctx.resources,
                priceBitmap(
                    ctx,
                    priceText(s.pricePerHour, s.isDemoPrice) + "/год",
                    priceText(s.pricePerDay, s.isDemoPrice) + "/доба",
                    tierMap[s.id] ?: Tier.MID,
                    s.id == state.selectedId,
                )
            )
        }
    }
    val meIcon = remember { BitmapDrawable(ctx.resources, dotBitmap(ctx, 0xFF1558D6.toInt())) }
    val pinIcon = remember { BitmapDrawable(ctx.resources, dotBitmap(ctx, 0xFF6A1B9A.toInt())) }

    val scaffoldState = rememberBottomSheetScaffoldState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedId, visible) {
        val idx = visible.indexOfFirst { it.id == state.selectedId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    val listHeight: Dp = (LocalConfiguration.current.screenHeightDp * 0.6f).dp

    val openRoute: (ParkingSpot) -> Unit = { s ->
        val uri = Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}(${Uri.encode(s.name)})")
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
        }
    }
    val myLocation: () -> Unit = {
        focus.clearFocus()
        if (granted) vm.refresh() else launcher.launch(permissions)
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = 240.dp,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        sheetContent = {
            Column(Modifier.fillMaxWidth()) {
                // заголовок + сортування
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (visible.isEmpty()) "Паркінги" else "${visible.size} паркінгів",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterPill("Ближчі", state.filters.sort == Sort.DISTANCE) {
                            vm.setFilters(state.filters.copy(sort = Sort.DISTANCE))
                        }
                        FilterPill("Дешевші", state.filters.sort == Sort.PRICE) {
                            vm.setFilters(state.filters.copy(sort = Sort.PRICE))
                        }
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("● дешево", color = Color(Tier.CHEAP.argb), style = MaterialTheme.typography.labelMedium)
                    Text("● середньо", color = Color(Tier.MID.argb), style = MaterialTheme.typography.labelMedium)
                    Text("● дорого", color = Color(Tier.PRICEY.argb), style = MaterialTheme.typography.labelMedium)
                }
                if (visible.any { it.isDemoPrice }) {
                    Text(
                        "~ означає демо-ціну (не реальну). Ціни без ~ взяті з OpenStreetMap.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                Box(Modifier.fillMaxWidth().height(listHeight)) {
                    if (visible.isEmpty()) {
                        val msg = when {
                            state.loading -> "Шукаю паркінги…"
                            state.spots.isNotEmpty() -> "Нічого не підходить під обрані фільтри."
                            state.searchCenter != null ->
                                "Паркінгів поруч не знайдено. Посуньте мапу й натисніть «Шукати в цій області»."
                            asked && !granted ->
                                "Немає доступу до локації. Введіть адресу у пошуку зверху."
                            else -> "Дозвольте доступ до локації або введіть адресу у пошуку зверху."
                        }
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(visible, key = { it.id }) { s ->
                                SpotCard(
                                    s = s,
                                    tier = tierMap[s.id] ?: Tier.MID,
                                    selected = s.id == state.selectedId,
                                    onClick = { vm.select(s, true) },
                                    onRoute = { openRoute(s) },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            MapLayer(
                state = state,
                visible = visible,
                tierMap = tierMap,
                badges = badges,
                meIcon = meIcon,
                pinIcon = pinIcon,
                onMoved = { c, r -> vm.onMapMoved(c, r) },
                onPick = { vm.select(it, false) },
            )

            Text(
                "Tiles © Esri · Data © OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = inner.calculateBottomPadding() + 4.dp)
                    .background(Color(0xB3FFFFFF))
                    .padding(horizontal = 4.dp),
            )

            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))

            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)
            ) {
                SearchField(
                    query = state.query,
                    onQuery = { vm.onQuery(it) },
                    onSubmit = { vm.submitQuery(); focus.clearFocus() },
                    onClear = { vm.clearQuery() },
                    onMyLocation = myLocation,
                )
                if (state.suggestions.isNotEmpty()) {
                    SuggestionList(state.suggestions) { vm.pickPlace(it); focus.clearFocus() }
                } else {
                    FilterRow(state.filters) { vm.setFilters(it) }
                    if (state.canSearchHere) {
                        Button(
                            onClick = { vm.searchHere() },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
                        ) { Text("Шукати в цій області") }
                    }
                }
            }
        }
    }
}

// ---------- мапа ----------

@Composable
private fun MapLayer(
    state: UiState,
    visible: List<ParkingSpot>,
    tierMap: Map<String, Tier>,
    badges: Map<String, BitmapDrawable>,
    meIcon: BitmapDrawable,
    pinIcon: BitmapDrawable,
    onMoved: (GeoPoint, Int) -> Unit,
    onPick: (ParkingSpot) -> Unit,
) {
    val moved by rememberUpdatedState(onMoved)
    val report: (MapView) -> Unit = { m ->
        try {
            val c = m.mapCenter
            val bb = m.boundingBox
            val r = (abs(bb.latNorth - bb.latSouth) * 111_320.0 / 2).toInt().coerceIn(400, 3000)
            moved(GeoPoint(c.latitude, c.longitude), r)
        } catch (e: Exception) {
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            MapView(c).apply {
                setTileSource(EsriStreet)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                minZoomLevel = 3.0
                maxZoomLevel = 19.0
                controller.setZoom(4.0)
                controller.setCenter(GeoPoint(39.8283, -98.5795)) // центр США
                addMapListener(object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        report(this@apply)
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        report(this@apply)
                        return false
                    }
                })
            }
        },
        update = { map ->
            val cam = state.cam
            if (cam != null && map.tag != state.camTick) {
                val z = state.camZoom
                if (z != null) {
                    map.controller.setZoom(z)
                    map.controller.setCenter(cam)
                } else {
                    map.controller.animateTo(cam)
                }
                map.tag = state.camTick
            }

            map.overlays.clear()

            val me = state.me
            val sc = state.searchCenter
            if (sc != null && (me == null || sc.distanceToAsDouble(me) > 80.0)) {
                map.overlays.add(Marker(map).apply {
                    position = sc
                    icon = pinIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            if (me != null) {
                map.overlays.add(Marker(map).apply {
                    position = me
                    icon = meIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }

            // дорогі — знизу, зелені — зверху, вибраний — найвище
            val ordered = visible.sortedWith(
                compareBy<ParkingSpot>(
                    { it.id == state.selectedId },
                    { -(tierMap[it.id]?.ordinal ?: 1) },
                )
            )
            ordered.forEach { s ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(s.lat, s.lng)
                    icon = badges[s.id]
                    title = s.name
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ ->
                        onPick(s)
                        true
                    }
                })
            }
            map.invalidate()
        },
        onRelease = { it.onDetach() },
    )
}

// ---------- пошук і фільтри ----------

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onMyLocation: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", modifier = Modifier.padding(start = 16.dp))
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text("Адреса, місто або місце") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) { Text("✕") }
            }
            IconButton(onClick = onMyLocation) { Text("📍") }
        }
    }
}

@Composable
private fun SuggestionList(items: List<Place>, onPick: (Place) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        color = Color.White,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column {
            items.forEachIndexed { i, p ->
                Text(
                    p.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(p) }
                        .padding(14.dp),
                )
                if (i < items.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
private fun FilterRow(f: Filters, onChange: (Filters) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        item { FilterPill("Безкоштовні", f.freeOnly) { onChange(f.copy(freeOnly = !f.freeOnly)) } }
        item { PriceChip(f.maxHourly) { onChange(f.copy(maxHourly = it)) } }
        item { FilterPill("Криті", f.covered) { onChange(f.copy(covered = !f.covered)) } }
        item { FilterPill("24/7", f.open247) { onChange(f.copy(open247 = !f.open247)) } }
        item { FilterPill("EV", f.ev) { onChange(f.copy(ev = !f.ev)) } }
        item { FilterPill("♿ Доступні", f.accessible) { onChange(f.copy(accessible = !f.accessible)) } }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

@Composable
private fun PriceChip(max: Double?, onPick: (Double?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterPill(if (max == null) "Ціна" else "до ${money(max)}/год", max != null) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf<Double?>(null, 5.0, 10.0, 15.0, 20.0).forEach { v ->
                DropdownMenuItem(
                    text = { Text(if (v == null) "Будь-яка ціна" else "до ${money(v)}/год") },
                    onClick = {
                        onPick(v)
                        open = false
                    },
                )
            }
        }
    }
}

// ---------- картка паркінгу ----------

@Composable
private fun SpotCard(
    s: ParkingSpot,
    tier: Tier,
    selected: Boolean,
    onClick: () -> Unit,
    onRoute: () -> Unit,
) {
    val tags = buildList {
        if (s.pricePerHour == 0.0) add("Безкоштовно")
        if (s.covered) add("Криті")
        if (s.open247) add("24/7")
        if (s.ev) add("EV")
        if (s.accessible) add("♿")
        s.capacity?.let { add("$it місць") }
    }
    val subtitle = listOfNotNull(s.address.takeIf { it.isNotBlank() }, miles(s.distanceM))
        .joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                if (tags.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tags.take(4).forEach { Tag(it) }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    priceText(s.pricePerHour, s.isDemoPrice) + "/год",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(tier.argb),
                )
                Text(
                    priceText(s.pricePerDay, s.isDemoPrice) + "/доба",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onRoute, contentPadding = PaddingValues(0.dp)) {
                    Text("Маршрут ›")
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(Color(0xFFEEF1F7), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
