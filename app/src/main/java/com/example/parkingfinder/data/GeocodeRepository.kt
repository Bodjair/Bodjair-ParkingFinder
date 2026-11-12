package com.example.parkingfinder.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Пошук адрес і місць: Photon (Komoot) з запасним варіантом Nominatim. Ключ не потрібен. */
class GeocodeRepository {
    private val ua = "ParkingFinder/0.3 (Android; github.com/Bodjair/Bodjair-ParkingFinder)"

    suspend fun search(query: String, nearLat: Double?, nearLng: Double?): List<Place> =
        withContext(Dispatchers.IO) {
            try {
                photon(query, nearLat, nearLng).ifEmpty { nominatim(query) }
            } catch (e: Exception) {
                nominatim(query)
            }
        }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", ua)
            connectTimeout = 8_000
            readTimeout = 10_000
        }
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().readText()
    }

    private fun photon(q: String, lat: Double?, lng: Double?): List<Place> {
        var url = "https://photon.komoot.io/api/?limit=7&lang=en&q=" + URLEncoder.encode(q, "UTF-8")
        if (lat != null && lng != null) url += "&lat=$lat&lon=$lng"
        val feats = JSONObject(get(url)).optJSONArray("features") ?: return emptyList()

        val out = mutableListOf<Pair<Boolean, Place>>()
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("housenumber"), p.optString("street"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val label = listOf(p.optString("name"), street, p.optString("city"), p.optString("state"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            if (label.isBlank()) continue
            val isUs = p.optString("countrycode") == "US"
            out += isUs to Place(label, coords.getDouble(1), coords.getDouble(0))
        }
        // результати зі США — першими
        return out.sortedByDescending { it.first }.map { it.second }
    }

    private fun nominatim(q: String): List<Place> {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&countrycodes=us&q=" +
            URLEncoder.encode(q, "UTF-8")
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(o.getString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }
}
