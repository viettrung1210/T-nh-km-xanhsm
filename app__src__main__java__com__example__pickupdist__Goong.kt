package com.example.pickupdist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LatLng(val lat: Double, val lng: Double)
data class Route(val meters: Double, val seconds: Double)

object Goong {
    private const val BASE = "https://rsapi.goong.io"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        try {
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Chạy song song Autocomplete+Detail và Geocode, ưu tiên kết quả Autocomplete (khớp tên toà nhà tốt hơn). */
    suspend fun find(key: String, query: String, bias: LatLng?): LatLng? = coroutineScope {
        val viaPlace = async { findByPlace(key, query, bias) }
        val viaGeocode = async { findByGeocode(key, query) }
        viaPlace.await() ?: viaGeocode.await()
    }

    private suspend fun findByPlace(key: String, query: String, bias: LatLng?): LatLng? = try {
        val loc = bias?.let { "&location=${it.lat},${it.lng}" } ?: ""
        val ac = get("$BASE/Place/AutoComplete?api_key=$key&input=${enc(query)}$loc&limit=1")
        val pid = ac.optJSONArray("predictions")?.optJSONObject(0)?.optString("place_id")
        if (pid.isNullOrEmpty()) null else {
            val d = get("$BASE/Place/Detail?place_id=${enc(pid)}&api_key=$key")
            val l = d.optJSONObject("result")?.optJSONObject("geometry")?.optJSONObject("location")
            l?.let { LatLng(it.getDouble("lat"), it.getDouble("lng")) }
        }
    } catch (_: Exception) { null }

    private suspend fun findByGeocode(key: String, query: String): LatLng? = try {
        val g = get("$BASE/Geocode?address=${enc(query)}&api_key=$key")
        val l = g.optJSONArray("results")?.optJSONObject(0)
            ?.optJSONObject("geometry")?.optJSONObject("location")
        l?.let { LatLng(it.getDouble("lat"), it.getDouble("lng")) }
    } catch (_: Exception) { null }

    /** Quãng đường thực tế cho xe máy. */
    suspend fun route(key: String, from: LatLng, to: LatLng): Route? {
        val j = get(
            "$BASE/Direction?origin=${from.lat},${from.lng}" +
                "&destination=${to.lat},${to.lng}&vehicle=bike&api_key=$key"
        )
        val leg = j.optJSONArray("routes")?.optJSONObject(0)
            ?.optJSONArray("legs")?.optJSONObject(0) ?: return null
        return Route(
            leg.getJSONObject("distance").getDouble("value"),
            leg.getJSONObject("duration").getDouble("value")
        )
    }
}
