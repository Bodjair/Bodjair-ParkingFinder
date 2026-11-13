package com.example.parkingfinder.data

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos

/**
 * Паркінги з OpenStreetMap (ключ не потрібен).
 * Основне джерело: Overpass API (кілька дзеркал одночасно).
 * Запасне: Nominatim (пошук «parking» у рамці навколо точки), якщо Overpass перевантажений.
 * Ціна береться з тегів OSM (fee / charge), якщо вони є, інакше — демо-ціна.
 */
class OverpassRepository(private val prices: PriceProvider) {

    private data class Raw(val id: String, val tags: JSONObject, val lat: Double, val lng: Double)

    private val ua = "ParkingFinder/0.3 (Android; github.com/Bodjair/Bodjair-ParkingFinder)"

    private val hourRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:hour|hr|h)\b""", RegexOption.IGNORE_CASE)
    private val dayRe = Regex("""(\d+(?:[.,]\d+)?)\s*(?:USD|usd|\$)?\s*/\s*(?:day|d)\b""", RegexOption.IGNORE_CASE)

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://lz4.overpass-api.de/api/interpreter",
        "https://z.overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.osm.jp/api/interpreter",
        "https://overpass.nchc.org.tw/api/interpreter",
    )

    // ---------- Overpass ----------

    /** Один запит до одного сервера. Кидає виняток, якщо відповідь не годиться. */
    private fun request(url: String, query: String, open: MutableList<HttpURLConnection>): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", ua)
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
            val found = withTimeoutOrNull(15_000) {
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
            error(errors.firstOrNull() ?: "таймаут")
        } finally {
            scope.cancel()
            open.forEach { runCatching { it.disconnect() } }
        }
    }

    private suspend fun fetchOverpass(query: String): List<Raw> {
        val json = fetchFirst(query)
        val arr = json.optJSONArray("elements") ?: return emptyList()
        val out = mutableListOf<Raw>()
        for (i in 0 until arr.length()) {
            val el = arr.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val lat: Double
            val lng: Double
            if (el.has("lat")) {
                lat = el.getDouble("lat"); lng = el.getDouble("lon")
            } else {
                val c = el.optJSONObject("center") ?: continue
                lat = c.getDouble("lat"); lng = c.getDouble("lon")
            }
            out += Raw(el.getString("type") + "/" + el.getLong("id"), tags, lat, lng)
        }
        return out
    }

    // ---------- Nominatim (запасне джерело) ----------

    private fun fetchNominatim(lat: Double, lng: Double, radius: Int): List<Raw> {
        val dLat = radius / 111_320.0
        val dLng = radius / (111_320.0 * cos(Math.toRadians(lat)))
        val viewbox = String.format(
            Locale.US, "%.6f,%.6f,%.6f,%.6f",
            lng - dLng, lat + dLat, lng + dLng, lat - dLat
        )
        val url = "https://nominatim.openstreetmap.org/search?q=parking&format=jsonv2&limit=40" +
            "&bounded=1&extratags=1&addressdetails=1&accept-language=en&viewbox=$viewbox"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", ua)
            connectTimeout = 6_000
            readTimeout = 12_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("nominatim: HTTP $code")
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val out = mutableListOf<Raw>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("type") != "parking") continue
                val tags = JSONObject()
                o.optJSONObject("extratags")?.let { ex ->
                    ex.keys().forEach { k -> tags.put(k, ex.optString(k)) }
                }
                val name = o.optString("name")
                if (name.isNotBlank()) tags.put("name", name)
                o.optJSONObject("address")?.let { a ->
                    a.optString("road").takeIf { it.isNotBlank() }?.let { tags.put("addr:street", it) }
                    a.optString("house_number").takeIf { it.isNotBlank() }?.let { tags.put("addr:housenumber", it) }
                }
                out += Raw(
                    o.getString("osm_type") + "/" + o.getLong("osm_id"),
                    tags,
                    o.getString("lat").toDouble(),
                    o.getString("lon").toDouble(),
                )
            }
            return out
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------- збирання результату ----------

    private suspend fun toSpot(r: Raw, centerLat: Double, centerLng: Double): ParkingSpot? {
        val tags = r.tags
        val access = tags.optString("access")
        if (access == "private" || access == "customers" || access == "permit") return null

        val name = tags.optString("name").ifBlank { tags.optString("operator") }.ifBlank { "Парковка" }
        val street = tags.optString("addr:street")
        val number = tags.optString("addr:housenumber")
        val address = listOf(number, street).filter { it.isNotBlank() }.joinToString(" ")

        var hourly: Double?
        var daily: Double?
        var demo = false

        val fee = tags.optString("fee")
        val charge = tags.optString("charge")
        if (fee == "no") {
            hourly = 0.0; daily = 0.0
        } else {
            hourly = hourRe.find(charge)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            daily = dayRe.find(charge)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            if (hourly == null && daily == null) {
                val (h, d) = prices.priceFor(r.id, name, r.lat, r.lng)
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
        Location.distanceBetween(centerLat, centerLng, r.lat, r.lng, dist)
        return ParkingSpot(
            id = r.id,
            name = name,
            address = address,
            lat = r.lat,
            lng = r.lng,
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

    suspend fun nearby(lat: Double, lng: Double, radius: Int = 1500): List<ParkingSpot> =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:10];(" +
                "node[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                "way[\"amenity\"=\"parking\"](around:$radius,$lat,$lng);" +
                ");out center tags 150;"

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                // обидва джерела стартують одночасно
                val overpass = scope.async { runCatching { fetchOverpass(query) } }
                val nominatim = scope.async { runCatching { fetchNominatim(lat, lng, radius) } }

                // Overpass має кращі дані: чекаємо його до 8 с, інакше беремо Nominatim
                val first = withTimeoutOrNull(8_000) { overpass.await() }
                var raws: List<Raw>? = first?.getOrNull()
                var failure: Throwable? = first?.exceptionOrNull()
                if (raws == null) {
                    val nm = nominatim.await()
                    raws = nm.getOrNull()
                    if (raws == null) {
                        val late = if (first == null) withTimeoutOrNull(8_000) { overpass.await() } else null
                        raws = late?.getOrNull()
                        failure = late?.exceptionOrNull() ?: failure ?: nm.exceptionOrNull()
                    }
                }
                val list = raws ?: error(
                    "Сервери карт перевантажені або недоступні. Спробуйте ще раз за хвилину.\n" +
                        "(${failure?.message ?: "таймаут"})"
                )

                list.distinctBy { it.id }
                    .mapNotNull { toSpot(it, lat, lng) }
                    .sortedBy { it.distanceM }
                    .take(60)
            } finally {
                scope.cancel()
            }
        }
}
