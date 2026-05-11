package com.airi.assistant.connector.api

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WeatherConnector — real-time and forecast weather via the Open-Meteo API.
 *
 * Open-Meteo is a free, open-source weather API that requires NO API key.
 * Data is sourced from government weather services (DWD, NOAA, ECMWF).
 * Privacy: only latitude/longitude are sent — no user identifiers.
 *
 * ## Supported actions
 * | action      | params                                   | notes                        |
 * |-------------|------------------------------------------|------------------------------|
 * | `current`   | `lat`, `lon`, `city` (display only)      | Current conditions           |
 * | `forecast`  | `lat`, `lon`, `days` (1–16)              | Daily forecast array         |
 * | `hourly`    | `lat`, `lon`, `hours` (1–48)             | Hourly forecast              |
 *
 * Default location falls back to Riyadh (lat=24.69, lon=46.72) when no
 * coordinates are provided.
 */
class WeatherConnector : Connector {

    override val id          = "weather"
    override val name        = "Weather"
    override val description = "Real-time weather via Open-Meteo (no API key required)"
    override val type        = ConnectorType.API

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(ConnectorState(connected = true,
        statusLine = "Open-Meteo (free, no key)"))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("weather", "forecast", "temperature", "free"),
        iconUrl = null)

    override suspend fun connect(): ConnectorState {
        // Open-Meteo needs no credentials — always connected
        val s = ConnectorState(connected = true, healthy = true, statusLine = "Open-Meteo ready (no key)")
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        // No-op for a stateless HTTP connector
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            val lat  = input.params["lat"]?.toDoubleOrNull() ?: DEFAULT_LAT
            val lon  = input.params["lon"]?.toDoubleOrNull() ?: DEFAULT_LON
            val city = input.params["city"] ?: ""
            when (input.action) {
                "current"  -> getCurrent(lat, lon, city)
                "forecast" -> getForecast(lat, lon, input.params["days"]?.toIntOrNull() ?: 7)
                "hourly"   -> getHourly(lat, lon, input.params["hours"]?.toIntOrNull() ?: 24)
                else -> ConnectorOutput.Failure("unknown_action",
                    "WeatherConnector does not support: ${input.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WeatherConnector error: ${e.message}")
            ConnectorOutput.Failure("weather_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    // ── Current weather ───────────────────────────────────────────────────────

    private fun getCurrent(lat: Double, lon: Double, city: String): ConnectorOutput {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature," +
            "precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
            "&timezone=auto"

        val json = fetch(url) ?: return ConnectorOutput.Failure("network_error",
            "Could not reach Open-Meteo API", retryable = true)

        val cur  = json.getJSONObject("current")
        val code = cur.optInt("weather_code", 0)
        val desc = weatherCodeDesc(code)
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        val feel = cur.optDouble("apparent_temperature", Double.NaN)
        val hum  = cur.optInt("relative_humidity_2m", 0)
        val wind = cur.optDouble("wind_speed_10m", 0.0)

        val locationStr = if (city.isNotBlank()) city else "($lat, $lon)"
        val summary = buildString {
            appendLine("Weather in $locationStr:")
            appendLine("  Condition   : $desc")
            appendLine("  Temperature : ${fmtTemp(temp)} (feels like ${fmtTemp(feel)})")
            appendLine("  Humidity    : $hum%")
            appendLine("  Wind        : ${String.format("%.1f", wind)} km/h")
        }
        return ConnectorOutput.Success(
            text = summary.trim(),
            data = mapOf(
                "temperature"  to fmtTemp(temp),
                "feels_like"   to fmtTemp(feel),
                "condition"    to desc,
                "humidity_pct" to "$hum",
                "wind_kph"     to String.format("%.1f", wind),
                "weather_code" to "$code",
                "location"     to locationStr
            )
        )
    }

    // ── Daily forecast ────────────────────────────────────────────────────────

    private fun getForecast(lat: Double, lon: Double, days: Int): ConnectorOutput {
        val clampedDays = days.coerceIn(1, 16)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,weather_code" +
            "&timezone=auto&forecast_days=$clampedDays"

        val json  = fetch(url) ?: return ConnectorOutput.Failure("network_error",
            "Could not reach Open-Meteo API", retryable = true)
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val maxT  = daily.getJSONArray("temperature_2m_max")
        val minT  = daily.getJSONArray("temperature_2m_min")
        val codes = daily.getJSONArray("weather_code")

        val sb = StringBuilder("${clampedDays}-day forecast (lat=$lat, lon=$lon):\n")
        for (i in 0 until dates.length()) {
            val code = codes.optInt(i, 0)
            sb.appendLine("  ${dates.getString(i)}: ${weatherCodeDesc(code)}, " +
                "Max ${maxT.optDouble(i).toInt()}°C / Min ${minT.optDouble(i).toInt()}°C")
        }
        return ConnectorOutput.Success(text = sb.toString().trim(),
            data = mapOf("forecast_days" to "$clampedDays", "raw_json" to daily.toString()))
    }

    // ── Hourly forecast ───────────────────────────────────────────────────────

    private fun getHourly(lat: Double, lon: Double, hours: Int): ConnectorOutput {
        val clampedHrs = hours.coerceIn(1, 48)
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
            "&timezone=auto&forecast_hours=$clampedHrs"

        val json   = fetch(url) ?: return ConnectorOutput.Failure("network_error",
            "Could not reach Open-Meteo API", retryable = true)
        val hourly = json.getJSONObject("hourly")
        val times  = hourly.getJSONArray("time")
        val temps  = hourly.getJSONArray("temperature_2m")
        val codes  = hourly.getJSONArray("weather_code")

        val sb = StringBuilder("${clampedHrs}-hour forecast:\n")
        for (i in 0 until minOf(times.length(), clampedHrs)) {
            sb.appendLine("  ${times.getString(i)}: ${weatherCodeDesc(codes.optInt(i, 0))}, " +
                "${temps.optDouble(i).toInt()}°C")
        }
        return ConnectorOutput.Success(text = sb.toString().trim())
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun fetch(url: String): JSONObject? {
        return try {
            val req  = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { Log.w(TAG, "HTTP ${resp.code} from $url"); return null }
            val body = resp.body?.string() ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "fetch error: ${e.message}")
            null
        }
    }

    // ── Weather code → human description ─────────────────────────────────────

    private fun weatherCodeDesc(code: Int): String = when (code) {
        0                    -> "Clear sky ☀️"
        1, 2, 3              -> "Partly cloudy ⛅"
        45, 48               -> "Foggy 🌫️"
        51, 53, 55           -> "Drizzle 🌦️"
        61, 63, 65           -> "Rain 🌧️"
        71, 73, 75           -> "Snow 🌨️"
        77                   -> "Snow grains ❄️"
        80, 81, 82           -> "Rain showers 🌦️"
        85, 86               -> "Snow showers 🌨️"
        95                   -> "Thunderstorm ⛈️"
        96, 99               -> "Thunderstorm with hail ⛈️🌨️"
        else                 -> "Unknown ($code)"
    }

    private fun fmtTemp(t: Double): String =
        if (t.isNaN()) "N/A" else "${t.toInt()}°C"

    companion object {
        private const val TAG         = "AIRI_WeatherConnector"
        private const val DEFAULT_LAT = 24.69  // Riyadh
        private const val DEFAULT_LON = 46.72
    }
}
