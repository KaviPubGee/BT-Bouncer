package com.example.btpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.example.btpriority.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RoutineRuleEvaluator {
    private const val TAG = "RoutineRuleEvaluator"

    @SuppressLint("MissingPermission")
    suspend fun evaluateOnConnectionEvent(
        context: Context,
        device: BluetoothDevice,
        isConnectedOrConnecting: Boolean
    ) = withContext(Dispatchers.IO) {
        val currentDeviceAddress = device.address.uppercase()
        ConnectedDeviceTracker.setConnected(currentDeviceAddress, isConnectedOrConnecting)

        if (!isConnectedOrConnecting) {
            return@withContext
        }

        val database = AppDatabase.getDatabase(context)
        val enabledRules = database.routineRuleDao().getEnabledRules()
        if (enabledRules.isEmpty()) return@withContext

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bondedDevices = bluetoothManager?.adapter?.bondedDevices ?: emptySet()

        for (rule in enabledRules) {
            // 1. Check Location Condition if rule has one
            if (rule.hasLocationCondition && rule.latitude != null && rule.longitude != null) {
                val isInside = LocationHelper.isInsideGeofence(
                    context = context,
                    targetLat = rule.latitude,
                    targetLng = rule.longitude,
                    radiusMeters = rule.radiusMeters
                )
                if (!isInside) {
                    Log.d(TAG, "Rule '${rule.ruleName}' skipped: Outside designated location (${rule.locationName ?: "Geofence"})")
                    continue
                }
            }

            val triggerList = rule.getTriggerList()
            val blockedList = rule.getBlockedList()

            // CASE A: The connecting device is in this rule's BLOCKED list
            if (blockedList.contains(currentDeviceAddress)) {
                val shouldBlock = if (triggerList.isEmpty()) {
                    // Pure location-based rule: if in location, block!
                    true
                } else {
                    // Trigger-based rule: block if ANY trigger is active
                    triggerList.any { triggerMac ->
                        ConnectedDeviceTracker.isConnected(triggerMac) ||
                            bondedDevices.find { it.address.equals(triggerMac, ignoreCase = true) }
                                ?.let { BluetoothHelper.isDeviceConnected(it) } == true
                    }
                }

                if (shouldBlock) {
                    Log.w(
                        TAG,
                        "RULE MATCH [${rule.ruleName}]: Dropping blocked device '${device.name ?: device.address}' ($currentDeviceAddress)!"
                    )
                    withContext(Dispatchers.Main) {
                        BluetoothHelper.disconnectDevice(context, device)
                    }
                    ConnectedDeviceTracker.setConnected(currentDeviceAddress, false)
                    return@withContext
                }
            }

            // CASE B: The connecting device is one of the TRIGGER devices in this rule
            if (triggerList.contains(currentDeviceAddress)) {
                // Disconnect ANY device in the blocked list that is currently connected
                for (blockedMac in blockedList) {
                    val isBlockedConnected = ConnectedDeviceTracker.isConnected(blockedMac) ||
                        bondedDevices.find { it.address.equals(blockedMac, ignoreCase = true) }
                            ?.let { BluetoothHelper.isDeviceConnected(it) } == true

                    if (isBlockedConnected) {
                        val blockedDev = bondedDevices.find { it.address.equals(blockedMac, ignoreCase = true) }
                        if (blockedDev != null) {
                            Log.w(
                                TAG,
                                "TRIGGER CONNECTED [${rule.ruleName}]: Trigger active! Disconnecting already-connected blocked device '${blockedDev.name ?: blockedDev.address}' ($blockedMac)"
                            )
                            withContext(Dispatchers.Main) {
                                BluetoothHelper.disconnectDevice(context, blockedDev)
                            }
                            ConnectedDeviceTracker.setConnected(blockedMac, false)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun evaluateAllRules(context: Context) = withContext(Dispatchers.IO) {
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val bondedDevices = bluetoothManager?.adapter?.bondedDevices ?: emptySet()
        for (dev in bondedDevices) {
            val isConn = BluetoothHelper.isDeviceConnected(dev)
            if (isConn) {
                evaluateOnConnectionEvent(context, dev, true)
            }
        }
        // Refresh widget to reflect any rule-enforced state changes
        BouncerAppWidgetProvider.updateAllWidgets(context)
    }
}

