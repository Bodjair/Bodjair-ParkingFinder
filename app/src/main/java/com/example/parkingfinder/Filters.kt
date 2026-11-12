package com.example.parkingfinder

import com.example.parkingfinder.data.ParkingSpot

enum class Sort { DISTANCE, PRICE }

data class Filters(
    val sort: Sort = Sort.DISTANCE,
    val freeOnly: Boolean = false,
    val covered: Boolean = false,
    val open247: Boolean = false,
    val ev: Boolean = false,
    val accessible: Boolean = false,
    val maxHourly: Double? = null,
)

fun applyFilters(spots: List<ParkingSpot>, f: Filters): List<ParkingSpot> {
    val filtered = spots.filter { s ->
        val ph = s.pricePerHour
        val mh = f.maxHourly
        (!f.freeOnly || ph == 0.0) &&
            (!f.covered || s.covered) &&
            (!f.open247 || s.open247) &&
            (!f.ev || s.ev) &&
            (!f.accessible || s.accessible) &&
            (mh == null || (ph != null && ph <= mh))
    }
    return when (f.sort) {
        Sort.DISTANCE -> filtered.sortedBy { it.distanceM }
        Sort.PRICE -> filtered.sortedBy { it.pricePerHour ?: Double.MAX_VALUE }
    }
}
