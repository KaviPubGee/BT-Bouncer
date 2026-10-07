package com.example.btpriority

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages blocked/disallowed status for Bluetooth devices by MAC address.
 * When a device is blocked, it is disconnected and any reconnection attempts are rejected.
 */
class DeviceBlockManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("bt_priority_blocks", Context.MODE_PRIVATE)

    fun isBlocked(macAddress: String): Boolean {
        return prefs.getBoolean("blocked_$macAddress", false)
    }

    fun setBlocked(macAddress: String, blocked: Boolean) {
        prefs.edit().putBoolean("blocked_$macAddress", blocked).apply()
    }

    fun isAllowed(macAddress: String): Boolean {
        return !isBlocked(macAddress)
    }
}
