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
    private val targetProfiles = listOf(
        BluetoothProfile.HEADSET,       // 1: Headset / Calls
        BluetoothProfile.A2DP,          // 2: Media Audio (Headphones, Speakers, Car)
        4,                              // 4: HID_HOST (Keyboards, Mice, Watch controls)
        5,                              // 5: PAN (Tethering, Smartwatch sync)
        6,                              // 6: PBAP (Phonebook Access)
        9,                              // 9: MAP (Message Access)
        17,                             // 17: PBAP_CLIENT
        18,                             // 18: MAP_CLIENT
        21,                             // 21: Hearing Aid
        22                              // 22: LE Audio
    )

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
        // 1. Direct device.disconnect() reflection (if present on OEM framework)
        try {
            val method = device.javaClass.getMethod("disconnect")
            method.isAccessible = true
            method.invoke(device)
        } catch (_: Exception) {}

        // 2. Direct iBluetooth service reflection
        try {
            val btAdapter = adapter
            if (btAdapter != null) {
                val mServiceField = btAdapter.javaClass.getDeclaredField("mService")
                mServiceField.isAccessible = true
                val iBluetooth = mServiceField.get(btAdapter)
                if (iBluetooth != null) {
                    for (m in iBluetooth.javaClass.methods) {
                        if (m.name.contains("disconnect", ignoreCase = true) &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0] == BluetoothDevice::class.java
                        ) {
                            m.isAccessible = true
                            m.invoke(iBluetooth, device)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Forbid connection policy and disconnect on all bound profile proxies
        synchronized(activeProxies) {
            for ((profileId, proxy) in activeProxies) {
                setProxyForbidden(proxy, device, profileId)
            }
        }
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

        // Call connect only on supported profiles (A2DP and Headset)
        if (profileId == BluetoothProfile.A2DP || profileId == BluetoothProfile.HEADSET) {
            try {
                val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                method.isAccessible = true
                method.invoke(proxy, device)
            } catch (_: Exception) {}
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
