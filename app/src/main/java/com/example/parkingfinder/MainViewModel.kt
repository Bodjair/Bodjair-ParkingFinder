package com.example.parkingfinder

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parkingfinder.data.DemoPriceProvider
import com.example.parkingfinder.data.OverpassRepository
import com.example.parkingfinder.data.ParkingSpot
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.util.GeoPoint

data class UiState(
    val loading: Boolean = false,
    val me: GeoPoint? = null,
    val spots: List<ParkingSpot> = emptyList(),
    val selected: ParkingSpot? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = OverpassRepository(DemoPriceProvider())
    private val fused = LocationServices.getFusedLocationProviderClient(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @SuppressLint("MissingPermission")
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val loc = fused.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
                ).await() ?: error("Не вдалося отримати локацію")
                val me = GeoPoint(loc.latitude, loc.longitude)
                _state.value = _state.value.copy(me = me)
                val spots = repo.nearby(me.latitude, me.longitude)
                _state.value = UiState(me = me, spots = spots)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun select(spot: ParkingSpot?) {
        _state.value = _state.value.copy(selected = spot)
    }
}
