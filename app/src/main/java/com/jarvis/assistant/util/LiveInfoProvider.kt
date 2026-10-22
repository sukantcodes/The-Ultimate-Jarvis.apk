package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Answers requests about the phone's changing state from Android APIs rather than AI memory.
 * Network-backed topics use LocationWeatherHelper and clearly report unavailable data.
 */
object LiveInfoProvider {
    suspend fun get(context: Context, topic: String): String {
        return when (topic.trim().lowercase(Locale.ROOT)) {
            "battery" -> battery(context)
            "time" -> "The current device time is ${DateFormat.getTimeInstance(DateFormat.LONG, Locale.getDefault()).format(Date())} (${TimeZone.getDefault().id})."
            "date" -> "Today's device date is ${DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(Date())}."
            "connectivity" -> connectivity(context)
            "location" -> {
                val location = LocationWeatherHelper.fetchCurrentLocationLabel(context)
                location ?: "I couldn't verify your current location. Please enable Location and grant JARVIS location permission, then try again."
            }
            "weather" -> {
                val weather = LocationWeatherHelper.fetchWeather(context)
                if (weather == null) {
                    "I couldn't fetch live weather. Please check internet access and grant location permission so I can use your current area."
                } else {
                    "Current weather for ${weather.cityName}: ${weather.temperature}, ${weather.condition}. ${weather.details}. This was fetched from the live weather service."
                }
            }
            "device_status" -> "Android version ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), device ${Build.MANUFACTURER} ${Build.MODEL}. ${battery(context)} ${connectivity(context)}"
            else -> "I don't recognize that live information category. Supported topics are battery, time, date, weather, location, connectivity, and device_status."
        }
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "I couldn't read the current battery status from Android."
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return "I couldn't read the current battery percentage from Android."
        val percent = (level * 100f / scale).toInt()
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "The current battery level is $percent percent. Charging: ${if (charging) "yes" else "no"}."
    }

    private fun connectivity(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "I couldn't read the current network connection."
        val network = manager.activeNetwork
            ?: return "The phone currently has no active internet network connection."
        val caps = manager.getNetworkCapabilities(network)
            ?: return "The phone's current network status is unavailable."
        val transports = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "mobile data"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return if (transports.isEmpty()) {
            "The phone has an active network connection, but its type couldn't be identified."
        } else {
            "Current connection: ${transports.joinToString(" and ")}. Internet capability reported by Android: ${if (internet) "available" else "not available"}."
        }
    }
}
