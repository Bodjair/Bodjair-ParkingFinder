package com.example.parkingfinder.data

data class ParkingSpot(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val pricePerHour: Double?,
    val pricePerDay: Double?,
    val isDemoPrice: Boolean,
)

/** Джерело цін. Замініть реалізацію на ParkWhiz/SpotHero, коли буде API-ключ. */
interface PriceProvider {
    suspend fun priceFor(id: String, name: String, lat: Double, lng: Double): Pair<Double?, Double?>
    val isDemo: Boolean
}

/** Демо-ціни: стабільні для одного місця, не реальні. */
class DemoPriceProvider : PriceProvider {
    override val isDemo = true
    override suspend fun priceFor(id: String, name: String, lat: Double, lng: Double): Pair<Double?, Double?> {
        val h = (id.hashCode() and 0x7fffffff)
        val hourly = 3.0 + (h % 1600) / 100.0          // $3.00 – $18.99
        val daily = (hourly * (4 + (h % 3))).coerceAtMost(60.0)
        return Pair(round2(hourly), round2(daily))
    }
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
