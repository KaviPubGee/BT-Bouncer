package com.example.btpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

object ConnectedDeviceTracker {
    private val addressSet = Collections.synchronizedSet(mutableSetOf<String>())
    private val _connectedFlow = MutableStateFlow<Set<String>>(emptySet())
    val connectedFlow: StateFlow<Set<String>> = _connectedFlow.asStateFlow()

    fun isConnected(mac: String): Boolean {
        return addressSet.contains(mac.uppercase())
    }

    fun setConnected(mac: String, connected: Boolean) {
        val normalized = mac.uppercase()
        if (connected) {
            addressSet.add(normalized)
        } else {
            addressSet.remove(normalized)
        }
        _connectedFlow.value = addressSet.toSet()
    }

    @SuppressLint("MissingPermission")
    fun syncState(context: Context) {
        val bm = context.getSystemService(BluetoothManager::class.java)
        val bonded = bm?.adapter?.bondedDevices ?: return
        for (dev in bonded) {
            val isConn = BluetoothHelper.isDeviceConnected(dev)
            val normalized = dev.address.uppercase()
            if (isConn) {
                addressSet.add(normalized)
            } else {
                addressSet.remove(normalized)
            }
        }
        _connectedFlow.value = addressSet.toSet()
    }
}
