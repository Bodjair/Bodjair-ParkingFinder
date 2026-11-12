package com.example.parkingfinder.data

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Паркінги з OpenStreetMap через Overpass API (ключ не потрібен).
 * Ціна береться з тегів OSM (fee / charge), якщо вони є, інакше — демо-ціна.
 */
class OverpassRepository(private val prices: PriceProvider) {

    private val hourRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:hour|hr|h)\b""", RegexOption.IGNORE_CASE)
    private val dayRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:day|d)\b""", RegexOption.IGNORE_CASE)

    suspend fun nearby(lat: Double, lng: Double, radius: Int = 1500): List<ParkingSpot> =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:25];(" +
                "node[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                "way[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                ");out center tags 80;"

            val conn = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "ParkingFinder/0.2 (Android demo app)")
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }

            if (conn.responseCode !in 200..299) {
                error("Overpass ${conn.responseCode}. Спробуйте ще раз за хвилину.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.optJSONArray("elements") ?: return@withContext emptyList()

            val result = mutableListOf<Pair<Float, ParkingSpot>>()
            for (i in 0 until arr.length()) {
                val el = arr.getJSONObject(i)
                val tags = el.optJSONObject("tags") ?: JSONObject()
                val access = tags.optString("access")
                if (access == "private" || access == "customers" || access == "permit") continue

                val (plat, plng) = if (el.has("lat")) {
                    el.getDouble("lat") to el.getDouble("lon")
                } else {
                    val c = el.optJSONObject("center") ?: continue
                    c.getDouble("lat") to c.getDouble("lon")
                }

                val id = el.getString("type") + "/" + el.getLong("id")
                val name = tags.optString("name").ifBlank { tags.optString("operator") }.ifBlank { "Парковка" }
                val street = tags.optString("addr:street")
                val number = tags.optString("addr:housenumber")
                val address = listOf(street, number).filter { it.isNotBlank() }.joinToString(" ")

                var hourly: Double? = null
                var daily: Double? = null
                var demo = false

                val fee = tags.optString("fee")
                val charge = tags.optString("charge")
                if (fee == "no") {
                    hourly = 0.0; daily = 0.0
                } else {
                    hourly = hourRe.find(charge)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    daily = dayRe.find(charge)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    if (hourly == null && daily == null) {
                        val (h, d) = prices.priceFor(id, name, plat, plng)
                        hourly = h; daily = d; demo = prices.isDemo
                    }
                }

                val dist = FloatArray(1)
                Location.distanceBetween(lat, lng, plat, plng, dist)
                result += dist[0] to ParkingSpot(id, name, address, plat, plng, hourly, daily, demo)
            }
            result.sortedBy { it.first }.take(30).map { it.second }
        }
}
