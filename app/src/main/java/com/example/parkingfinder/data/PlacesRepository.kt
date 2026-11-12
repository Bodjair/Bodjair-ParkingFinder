package com.example.parkingfinder.data

import com.example.parkingfinder.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Places API (New) – Nearby Search, тип "parking". */
class PlacesRepository(private val prices: PriceProvider) {

    suspend fun nearby(lat: Double, lng: Double, radiusMeters: Double = 1500.0): List<ParkingSpot> =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("includedTypes", JSONArray().put("parking"))
                put("maxResultCount", 20)
                put("rankPreference", "DISTANCE")
                put("locationRestriction", JSONObject().put("circle", JSONObject().apply {
                    put("center", JSONObject().put("latitude", lat).put("longitude", lng))
                    put("radius", radiusMeters)
                }))
            }

            val conn = (URL("https://places.googleapis.com/v1/places:searchNearby")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                setRequestProperty(
                    "X-Goog-FieldMask",
                    "places.id,places.displayName,places.formattedAddress,places.location"
                )
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                error("Places API ${conn.responseCode}: $err")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.optJSONArray("places") ?: return@withContext emptyList()

            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val loc = p.getJSONObject("location")
                val id = p.getString("id")
                val name = p.optJSONObject("displayName")?.optString("text") ?: "Parking"
                val plat = loc.getDouble("latitude")
                val plng = loc.getDouble("longitude")
                val (h, d) = prices.priceFor(id, name, plat, plng)
                ParkingSpot(
                    id = id,
                    name = name,
                    address = p.optString("formattedAddress"),
                    lat = plat,
                    lng = plng,
                    pricePerHour = h,
                    pricePerDay = d,
                    isDemoPrice = prices.isDemo,
                )
            }
        }
}
