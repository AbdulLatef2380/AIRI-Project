package com.airi.assistant.world

/**
 * WorldState - A snapshot of the device and environment state.
 */
data class WorldState(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val networkType: NetworkType,
    val isNetworkConnected: Boolean,
    val availableMemoryMB: Long,
    val topAppPackage: String?,
    val timestamp: Long = System.currentTimeMillis()
)

enum class NetworkType {
    WIFI, CELLULAR, NONE, UNKNOWN
}
