package com.example.btpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BtConnectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BtConnectionReceiver"
        // Persistent scope so we never leak a CoroutineScope per onReceive call
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive action: $action")

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        val isDisconnect = when {
            action == BluetoothDevice.ACTION_ACL_DISCONNECTED -> true
            action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                state == BluetoothAdapter.STATE_DISCONNECTED
            }
            action.contains("CONNECTION_STATE_CHANGED", ignoreCase = true) -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                state == BluetoothProfile.STATE_DISCONNECTED
            }
            else -> false
        }

        if (isDisconnect) {
            ConnectedDeviceTracker.setConnected(device.address, false)
            BouncerAppWidgetProvider.updateAllWidgets(context)
            return
        }

        // Only enforce disconnection/routine rules when device is actually CONNECTING or CONNECTED
        val isConnectEvent = when {
            action == BluetoothDevice.ACTION_ACL_CONNECTED -> true
            action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                state == BluetoothAdapter.STATE_CONNECTED || state == BluetoothAdapter.STATE_CONNECTING
            }
            action.contains("CONNECTION_STATE_CHANGED", ignoreCase = true) -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING
            }
            else -> false
        }

        if (!isConnectEvent) return

        ConnectedDeviceTracker.setConnected(device.address, true)
        BouncerAppWidgetProvider.updateAllWidgets(context)

        val blockManager = DeviceBlockManager(context)
        val isBlocked = blockManager.isBlocked(device.address)

        if (isBlocked) {
            Log.d(TAG, "BLOCKED device (${device.address}) attempting connection! Dropping connection.")
            BluetoothHelper.disconnectDevice(context, device)
            ConnectedDeviceTracker.setConnected(device.address, false)
            BouncerAppWidgetProvider.updateAllWidgets(context)
            return
        }

        // Evaluate automated Routine Rules in background coroutine (reuse persistent scope)
        receiverScope.launch {
            RoutineRuleEvaluator.evaluateOnConnectionEvent(context, device, true)
            BouncerAppWidgetProvider.updateAllWidgets(context)
        }
    }
}
