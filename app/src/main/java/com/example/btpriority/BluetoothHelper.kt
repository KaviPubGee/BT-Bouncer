package com.example.btpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context

object BluetoothHelper {
    @Volatile
    private var profileManager: BluetoothProfileManager? = null

    fun getManager(context: Context): BluetoothProfileManager {
        return profileManager ?: synchronized(this) {
            profileManager ?: BluetoothProfileManager(context.applicationContext).also {
                profileManager = it
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(context: Context, device: BluetoothDevice, onComplete: (() -> Unit)? = null) {
        getManager(context).disconnectDevice(device, onComplete)
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(context: Context, device: BluetoothDevice) {
        getManager(context).connectDevice(device)
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(context: Context, device: BluetoothDevice): Boolean {
        return getManager(context).pair(device)
    }

    @SuppressLint("MissingPermission")
    fun unpairDevice(context: Context, device: BluetoothDevice): Boolean {
        return getManager(context).unpair(device)
    }
}
