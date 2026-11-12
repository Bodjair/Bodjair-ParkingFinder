package com.example.parkingfinder

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parkingfinder.data.DemoPriceProvider
import com.example.parkingfinder.data.ParkingSpot
import com.example.parkingfinder.data.PlacesRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class UiState(
    val loading: Boolean = false,
    val me: LatLng? = null,
    val spots: List<ParkingSpot> = emptyList(),
    val selected: ParkingSpot? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlacesRepository(DemoPriceProvider())
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
                val me = LatLng(loc.latitude, loc.longitude)
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
