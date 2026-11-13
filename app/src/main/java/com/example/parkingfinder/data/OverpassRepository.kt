package com.example.parkingfinder.data

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Паркінги з OpenStreetMap через Overpass API (ключ не потрібен).
 * Ціна береться з тегів OSM (fee / charge), якщо вони є, інакше — демо-ціна.
 */
class OverpassRepository(private val prices: PriceProvider) {

    private val hourRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:hour|hr|h)\b""", RegexOption.IGNORE_CASE)
    private val dayRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:day|d)\b""", RegexOption.IGNORE_CASE)

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /** Один запит до одного сервера. Кидає виняток, якщо відповідь не годиться. */
    private fun request(url: String, query: String, open: MutableList<HttpURLConnection>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty(
                "User-Agent",
                "ParkingFinder/0.3 (Android; github.com/Bodjair/Bodjair-ParkingFinder)"
            )
            connectTimeout = 6_000
            readTimeout = 14_000
        }
        open += conn
        val host = URL(url).host
        try {
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) error("$host: HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            // Overpass іноді віддає 200, але з помилкою всередині (наприклад, не встиг порахувати)
            val remark = json.optString("remark")
            if (remark.isNotBlank() && json.optJSONArray("elements")?.length() == 0) {
                error("$host: $remark")
            }
            return json
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Запитує всі дзеркала одночасно й бере першу успішну відповідь.
     * Окремий scope, щоб не чекати на сервери, які «зависли» (наприклад, на DNS).
     */
    private suspend fun fetchFirst(query: String): JSONObject {
        val open = CopyOnWriteArrayList<HttpURLConnection>()
        val results = Channel<Result<JSONObject>>(endpoints.size)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            endpoints.forEach { url ->
                scope.launch { results.send(runCatching { request(url, query, open) }) }
            }
            val errors = mutableListOf<String>()
            val found = withTimeoutOrNull(20_000) {
                var failures = 0
                var json: JSONObject? = null
                while (json == null && failures < endpoints.size) {
                    val r = results.receive()
                    json = r.getOrNull()
                    if (json == null) {
                        failures++
                        errors += r.exceptionOrNull()?.message ?: "невідома помилка"
                    }
                }
                json
            }
            if (found != null) return found
            error(
                "Сервери карт перевантажені або недоступні. Спробуйте ще раз за хвилину.\n" +
                    "(${errors.firstOrNull() ?: "таймаут"})"
            )
        } finally {
            scope.cancel()
            open.forEach { runCatching { it.disconnect() } }
        }
    }

    suspend fun nearby(lat: Double, lng: Double, radius: Int = 1500): List<ParkingSpot> =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:10];(" +
                "node[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                "way[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                ");out center tags 150;"

            // одна автоматична повторна спроба, якщо всі сервери зайняті
            val json = try {
                fetchFirst(query)
            } catch (e: IllegalStateException) {
                delay(1500)
                fetchFirst(query)
            }
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
                val address = listOf(number, street).filter { it.isNotBlank() }.joinToString(" ")

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

                val parkingType = tags.optString("parking")
                val covered = parkingType == "multi-storey" || parkingType == "underground" ||
                    parkingType == "rooftop" || tags.optString("covered") == "yes"
                val open247 = tags.optString("opening_hours") == "24/7"
                val ev = (tags.optString("capacity:charging").toIntOrNull() ?: 0) > 0
                val disabledCap = tags.optString("capacity:disabled")
                val accessible = (disabledCap.isNotBlank() && disabledCap != "no" && disabledCap != "0") ||
                    tags.optString("wheelchair") == "yes"
                val capacity = tags.optString("capacity").toIntOrNull()

                val dist = FloatArray(1)
                Location.distanceBetween(lat, lng, plat, plng, dist)
                result += dist[0] to ParkingSpot(
                    id = id,
                    name = name,
                    address = address,
                    lat = plat,
                    lng = plng,
                    pricePerHour = hourly,
                    pricePerDay = daily,
                    isDemoPrice = demo,
                    distanceM = dist[0],
                    covered = covered,
                    open247 = open247,
                    ev = ev,
                    accessible = accessible,
                    capacity = capacity,
                )
            }
            result.sortedBy { it.first }.take(60).map { it.second }
        }
}
