package com.example.btpriority

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages persistent Bluetooth profile proxies (A2DP, Headset, HID_HOST, PAN, LE_AUDIO, etc.)
 * to allow clean profile-level disconnection and reconnection without unpairing devices.
 */
class BluetoothProfileManager(private val context: Context) {
    companion object {
        private const val TAG = "BtProfileManager"
        const val CONNECTION_POLICY_ALLOWED = 100
        const val CONNECTION_POLICY_FORBIDDEN = 0
        const val PRIORITY_OFF = 0
        const val PRIORITY_ON = 100
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val activeProxies = mutableMapOf<Int, BluetoothProfile>()

    // Core profile IDs to maintain persistent proxies for
    // 1: Headset, 2: A2DP, 4: HID_HOST, 5: PAN, 6: PBAP, 9: MAP, 10: SAP,
    // 17: PBAP_CLIENT, 18: MAP_CLIENT, 21: Hearing Aid, 22: LE Audio, 25: CSIS
    private val targetProfiles = listOf(1, 2, 4, 5, 6, 9, 10, 17, 18, 21, 22, 25)

    init {
        bindProxies()
    }

    @SuppressLint("MissingPermission")
    fun bindProxies() {
        val btAdapter = adapter ?: return
        for (profileId in targetProfiles) {
            try {
                btAdapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        synchronized(activeProxies) {
                            activeProxies[profile] = proxy
                        }
                        Log.d(TAG, "Proxy bound for profile: $profile")
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        synchronized(activeProxies) {
                            activeProxies.remove(profile)
                        }
                        Log.d(TAG, "Proxy unbound for profile: $profile")
                    }
                }, profileId)
            } catch (e: Exception) {
                Log.d(TAG, "Could not bind profile proxy $profileId: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(device: BluetoothDevice, onComplete: (() -> Unit)? = null) {
        val address = device.address
        val name = try { device.name ?: "Unknown" } catch (_: Exception) { "Unknown" }
        Log.d(TAG, "Disconnecting device without unpairing: $name ($address)")

        executeProfileDisconnection(device)

        // Multiple delayed passes to ensure profile service disconnection handshakes finalize
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            try {
                executeProfileDisconnection(device)
                onComplete?.invoke()
            } catch (_: Exception) {}
        }, 300L)

        handler.postDelayed({
            try {
                executeProfileDisconnection(device)
            } catch (_: Exception) {}
        }, 1000L)
    }

    @SuppressLint("MissingPermission")
    private fun executeProfileDisconnection(device: BluetoothDevice) {
        // 1. Direct device.disconnect() reflection (supported on Android 11+ AOSP & Samsung One UI)
        try {
            val method = device.javaClass.getMethod("disconnect")
            method.isAccessible = true
            method.invoke(device)
        } catch (_: Exception) {}

        // 2. Direct iBluetooth service reflection (adapter -> mService -> disconnect(BluetoothDevice) / disconnectAll(BluetoothDevice))
        try {
            val btAdapter = adapter
            if (btAdapter != null) {
                val mServiceField = btAdapter.javaClass.getDeclaredField("mService")
                mServiceField.isAccessible = true
                val iBluetooth = mServiceField.get(btAdapter)
                if (iBluetooth != null) {
                    for (m in iBluetooth.javaClass.methods) {
                        if (m.name.equals("disconnect", ignoreCase = true) ||
                            m.name.equals("disconnectAll", ignoreCase = true) ||
                            m.name.equals("disconnectDevice", ignoreCase = true)
                        ) {
                            try {
                                if (m.parameterTypes.size == 1 && m.parameterTypes[0] == BluetoothDevice::class.java) {
                                    m.isAccessible = true
                                    m.invoke(iBluetooth, device)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Forbid connection policy and disconnect on all bound profile proxies
        // (A2DP, Headset, HID_HOST, PAN, PBAP, MAP, LE Audio, Hearing Aid, etc.)
        synchronized(activeProxies) {
            for ((profileId, proxy) in activeProxies) {
                setProxyForbidden(proxy, device, profileId)
            }
        }

        // 4. Force disconnect via GATT if this is a BLE / Wearable / Smartwatch connection
        disconnectGattDevice(device)
    }

    /**
     * Terminate active BLE / GATT connections (used by smartwatches, fitness bands, trackers, etc.)
     */
    @SuppressLint("MissingPermission")
    private fun disconnectGattDevice(device: BluetoothDevice) {
        try {
            // Attempt to connect briefly to obtain a BluetoothGatt handle then immediately disconnect & close
            val gatt = device.connectGatt(
                context,
                false,
                object : android.bluetooth.BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt?, status: Int, newState: Int) {
                        try {
                            gatt?.disconnect()
                            gatt?.close()
                        } catch (_: Exception) {}
                    }
                },
                BluetoothDevice.TRANSPORT_AUTO
            )
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        val address = device.address
        Log.d(TAG, "Connecting device: $address")

        // 1. Restore allowed connection policy on all profile proxies
        synchronized(activeProxies) {
            for ((profileId, proxy) in activeProxies) {
                setProxyAllowed(proxy, device, profileId)
            }
        }

        // 2. Direct reflection connect
        try {
            val method = device.javaClass.getMethod("connect")
            method.isAccessible = true
            method.invoke(device)
        } catch (_: Exception) {}
    }

    private fun setProxyForbidden(proxy: BluetoothProfile, device: BluetoothDevice, profileId: Int) {
        // Set policy to FORBIDDEN
        try {
            val method = proxy.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(proxy, device, CONNECTION_POLICY_FORBIDDEN)
        } catch (_: Exception) {}

        // Set priority to OFF
        try {
            val method = proxy.javaClass.getMethod(
                "setPriority",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(proxy, device, PRIORITY_OFF)
        } catch (_: Exception) {}

        // Call disconnect
        try {
            val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            method.isAccessible = true
            method.invoke(proxy, device)
        } catch (_: Exception) {}
    }

    private fun setProxyAllowed(proxy: BluetoothProfile, device: BluetoothDevice, profileId: Int) {
        // Set policy to ALLOWED
        try {
            val method = proxy.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(proxy, device, CONNECTION_POLICY_ALLOWED)
        } catch (_: Exception) {}

        // Set priority to ON
        try {
            val method = proxy.javaClass.getMethod(
                "setPriority",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(proxy, device, PRIORITY_ON)
        } catch (_: Exception) {}

        // Call connect on proxy
        try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            method.isAccessible = true
            method.invoke(proxy, device)
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        // 1. Check device.isConnected() hidden API
        try {
            val method = device.javaClass.getMethod("isConnected")
            if (method.invoke(device) as? Boolean == true) return true
        } catch (_: Exception) {}

        // 2. Check all active profile proxies (HID, PAN, A2DP, HEADSET, LE Audio, etc.)
        synchronized(activeProxies) {
            for ((_, proxy) in activeProxies) {
                try {
                    val state = proxy.getConnectionState(device)
                    if (state == BluetoothProfile.STATE_CONNECTED) return true
                } catch (_: Exception) {}
            }
        }

        // 3. Check BluetoothManager connected devices across common profiles
        val bm = bluetoothManager
        if (bm != null) {
            val profilesToCheck = intArrayOf(
                BluetoothProfile.GATT,
                BluetoothProfile.GATT_SERVER,
                BluetoothProfile.HEADSET,
                BluetoothProfile.A2DP
            )
            for (p in profilesToCheck) {
                try {
                    val connectedGatt = bm.getConnectedDevices(p)
                    if (connectedGatt.any { it.address.equals(device.address, ignoreCase = true) }) {
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice): Boolean {
        return try {
            device.createBond()
        } catch (e: Exception) {
            Log.d(TAG, "Pair error: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun unpair(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            Log.d(TAG, "Unpair error: ${e.message}")
            false
        }
    }

    fun release() {
        val btAdapter = adapter ?: return
        synchronized(activeProxies) {
            for ((profileId, proxy) in activeProxies) {
                try {
                    btAdapter.closeProfileProxy(profileId, proxy)
                } catch (_: Exception) {}
            }
            activeProxies.clear()
        }
    }
}
