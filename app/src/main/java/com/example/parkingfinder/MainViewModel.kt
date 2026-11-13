package com.example.parkingfinder

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parkingfinder.data.DemoPriceProvider
import com.example.parkingfinder.data.GeocodeRepository
import com.example.parkingfinder.data.OverpassRepository
import com.example.parkingfinder.data.ParkingSpot
import com.example.parkingfinder.data.Place
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.osmdroid.util.GeoPoint

data class UiState(
    val loading: Boolean = false,
    val me: GeoPoint? = null,
    val searchCenter: GeoPoint? = null,
    val spots: List<ParkingSpot> = emptyList(),
    val selectedId: String? = null,
    val error: String? = null,
    val cam: GeoPoint? = null,
    val camZoom: Double? = null,
    val camTick: Int = 0,
    val canSearchHere: Boolean = false,
    val query: String = "",
    val suggestions: List<Place> = emptyList(),
    val filters: Filters = Filters(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = OverpassRepository(DemoPriceProvider())
    private val geo = GeocodeRepository()
    private val fused = LocationServices.getFusedLocationProviderClient(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var viewCenter: GeoPoint? = null
    private var viewRadius = 1500
    private var lastRadius = 1500
    private var ignoreMovesUntil = 0L

    private fun launchSearch(block: suspend () -> Unit) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Помилка") }
            }
        }
    }

    private suspend fun runSearch(center: GeoPoint, radius: Int, moveCamera: Boolean, zoom: Double?) {
        lastRadius = radius
        ignoreMovesUntil = System.currentTimeMillis() + 2500
        _state.update { s ->
            s.copy(
                loading = true,
                error = null,
                searchCenter = center,
                canSearchHere = false,
                cam = if (moveCamera) center else s.cam,
                camZoom = if (moveCamera) zoom else null,
                camTick = if (moveCamera) s.camTick + 1 else s.camTick,
            )
        }
        val spots = repo.nearby(center.latitude, center.longitude, radius)
        _state.update { it.copy(loading = false, spots = spots, selectedId = null) }
    }

    /** Пошук навколо поточної локації користувача. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        _state.update { it.copy(query = "", suggestions = emptyList()) }
        launchSearch {
            _state.update { it.copy(loading = true, error = null) }
            // швидка локація (Wi-Fi/мережа) з таймаутом, інакше остання відома
            val loc = withTimeoutOrNull(10_000) {
                fused.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token
                ).await()
            } ?: fused.lastLocation.await()
                ?: error("Не вдалося отримати локацію. Увімкніть геолокацію або введіть адресу у пошуку.")
            val me = GeoPoint(loc.latitude, loc.longitude)
            _state.update { it.copy(me = me) }
            runSearch(me, 1500, true, 15.0)
        }
    }

    fun onQuery(q: String) {
        _state.update { it.copy(query = q) }
        suggestJob?.cancel()
        if (q.trim().length < 3) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(350)
            try {
                val me = _state.value.me
                val res = geo.search(q.trim(), me?.latitude, me?.longitude)
                _state.update { it.copy(suggestions = res) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(suggestions = emptyList()) }
            }
        }
    }

    fun clearQuery() {
        suggestJob?.cancel()
        _state.update { it.copy(query = "", suggestions = emptyList()) }
    }

    fun dismissSuggestions() {
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList()) }
    }

    fun pickPlace(p: Place) {
        suggestJob?.cancel()
        _state.update { it.copy(query = p.label, suggestions = emptyList()) }
        launchSearch { runSearch(GeoPoint(p.lat, p.lng), 1500, true, 15.0) }
    }

    /** Enter на клавіатурі: беремо перший результат пошуку. */
    fun submitQuery() {
        val q = _state.value.query.trim()
        if (q.length < 2) return
        suggestJob?.cancel()
        launchSearch {
            val me = _state.value.me
            val p = geo.search(q, me?.latitude, me?.longitude).firstOrNull()
                ?: error("Нічого не знайдено за запитом «$q»")
            _state.update { it.copy(query = p.label, suggestions = emptyList()) }
            runSearch(GeoPoint(p.lat, p.lng), 1500, true, 15.0)
        }
    }

    /** Кнопка «Шукати в цій області»: пошук навколо центру мапи. */
    fun searchHere() {
        val c = viewCenter ?: return
        val r = viewRadius
        launchSearch { runSearch(c, r, false, null) }
    }

    fun onMapMoved(center: GeoPoint, radius: Int) {
        viewCenter = center
        viewRadius = radius
        if (System.currentTimeMillis() < ignoreMovesUntil) return
        val s = _state.value
        if (s.canSearchHere || s.loading) return
        val sc = s.searchCenter
        val far = sc == null || sc.distanceToAsDouble(center) > maxOf(300.0, lastRadius * 0.4)
        if (far) _state.update { it.copy(canSearchHere = true) }
    }

    fun select(spot: ParkingSpot?, moveCamera: Boolean) {
        ignoreMovesUntil = System.currentTimeMillis() + 1500
        _state.update { s ->
            val move = spot != null && moveCamera
            s.copy(
                selectedId = spot?.id,
                cam = if (move) GeoPoint(spot!!.lat, spot.lng) else s.cam,
                camZoom = null,
                camTick = if (move) s.camTick + 1 else s.camTick,
            )
        }
    }

    fun setFilters(f: Filters) {
        _state.update { it.copy(filters = f) }
    }
}
