package com.example.btpriority

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews

class BouncerAppWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_DEVICE = "com.example.btpriority.ACTION_TOGGLE_DEVICE"
        const val ACTION_REFRESH_WIDGET = "com.example.btpriority.ACTION_REFRESH_WIDGET"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        @Volatile
        private var lastToggleTime = 0L
        @Volatile
        private var lastToggleMac = ""

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, BouncerAppWidgetProvider::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (widgetIds.isNotEmpty()) {
                val provider = BouncerAppWidgetProvider()
                for (id in widgetIds) {
                    provider.updateSingleWidget(context, appWidgetManager, id)
                }
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, id)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == ACTION_TOGGLE_DEVICE) {
            val mac = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
            if (!mac.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                if (mac.equals(lastToggleMac, ignoreCase = true) && (now - lastToggleTime < 500L)) {
                    return
                }
                lastToggleTime = now
                lastToggleMac = mac

                val blockManager = DeviceBlockManager(context)
                val isCurrentlyBlocked = blockManager.isBlocked(mac)
                val newBlocked = !isCurrentlyBlocked
                blockManager.setBlocked(mac, newBlocked)

                val bm = context.getSystemService(BluetoothManager::class.java)
                val dev = bm?.adapter?.bondedDevices?.find { it.address.equals(mac, ignoreCase = true) }

                if (dev != null) {
                    if (newBlocked) {
                        // User toggled OFF: Disconnect profiles and disallow connection, KEEP device in list
                        BluetoothHelper.disconnectDevice(context, dev)
                        ConnectedDeviceTracker.setConnected(mac, false)
                    } else {
                        // User toggled ON: Allow connection and connect device
                        BluetoothHelper.connectDevice(context, dev)
                    }
                }
                updateAllWidgets(context)
            }
            return
        } else if (action == ACTION_REFRESH_WIDGET) {
            updateAllWidgets(context)
            return
        }

        super.onReceive(context, intent)
    }

    @SuppressLint("MissingPermission")
    fun updateSingleWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_bouncer_layout)
        val blockManager = DeviceBlockManager(context)

        // 1. Refresh button pending intent
        val refreshIntent = Intent(context, BouncerAppWidgetProvider::class.java).apply {
            action = ACTION_REFRESH_WIDGET
        }
        val refreshPi = PendingIntent.getBroadcast(
            context,
            1001,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPi)

        // 2. Tapping header opens the full app
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            context,
            1002,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPi)

        // 3. Query paired devices
        val bm = context.getSystemService(BluetoothManager::class.java)
        val bondedDevices = bm?.adapter?.bondedDevices?.toList() ?: emptyList()

        if (bondedDevices.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty_view, View.VISIBLE)
            views.setViewVisibility(R.id.widget_slots_container, View.GONE)
            appWidgetManager.updateAppWidget(appWidgetId, views)
            return
        }

        views.setViewVisibility(R.id.widget_empty_view, View.GONE)
        views.setViewVisibility(R.id.widget_slots_container, View.VISIBLE)

        // Stable fixed order for top 5 devices:
        // Always display the same top 5 paired devices sorted by name so toggling ON/OFF does NOT make them disappear or jump around!
        val topDevices = bondedDevices.sortedWith(
            compareBy<BluetoothDevice> {
                try { it.name?.lowercase() ?: "zz" } catch (_: Exception) { "zz" }
            }.thenBy { it.address }
        ).take(5)

        val slotLayoutIds = intArrayOf(
            R.id.slot_1_layout, R.id.slot_2_layout, R.id.slot_3_layout, R.id.slot_4_layout, R.id.slot_5_layout
        )
        val slotNameIds = intArrayOf(
            R.id.slot_1_name, R.id.slot_2_name, R.id.slot_3_name, R.id.slot_4_name, R.id.slot_5_name
        )
        val slotStatusIds = intArrayOf(
            R.id.slot_1_status, R.id.slot_2_status, R.id.slot_3_status, R.id.slot_4_status, R.id.slot_5_status
        )
        val slotToggleIds = intArrayOf(
            R.id.slot_1_toggle, R.id.slot_2_toggle, R.id.slot_3_toggle, R.id.slot_4_toggle, R.id.slot_5_toggle
        )

        // Vibrant emerald green (#00BF63) palette matching the app UI
        val colorOnSurface = Color.parseColor("#E5ECE8")
        val colorOnSurfaceVariant = Color.parseColor("#98ADA2")
        val colorGreenAccent = Color.parseColor("#00BF63")
        val colorToggleOnText = Color.parseColor("#003919")
        val colorToggleOffText = Color.parseColor("#98ADA2")

        for (i in 0 until 5) {
            if (i < topDevices.size) {
                val dev = topDevices[i]
                val devName = try { dev.name?.ifBlank { "Unknown Device" } ?: "Unknown Device" } catch (_: Exception) { "Unknown Device" }
                val isBlocked = blockManager.isBlocked(dev.address)
                val isConn = !isBlocked && BluetoothHelper.isDeviceConnected(dev)

                views.setViewVisibility(slotLayoutIds[i], View.VISIBLE)
                views.setTextViewText(slotNameIds[i], devName)
                views.setTextColor(slotNameIds[i], colorOnSurface)

                if (isBlocked) {
                    // Turned OFF / Blocked
                    views.setTextViewText(slotStatusIds[i], "Off • Disconnected")
                    views.setTextColor(slotStatusIds[i], colorOnSurfaceVariant)
                    views.setTextViewText(slotToggleIds[i], "OFF")
                    views.setTextColor(slotToggleIds[i], colorToggleOffText)
                    views.setInt(slotToggleIds[i], "setBackgroundResource", R.drawable.widget_btn_blocked)
                } else if (isConn) {
                    // Turned ON and currently Connected
                    views.setTextViewText(slotStatusIds[i], "On • Connected")
                    views.setTextColor(slotStatusIds[i], colorGreenAccent)
                    views.setTextViewText(slotToggleIds[i], "ON")
                    views.setTextColor(slotToggleIds[i], colorToggleOnText)
                    views.setInt(slotToggleIds[i], "setBackgroundResource", R.drawable.widget_btn_allowed)
                } else {
                    // Turned ON and Ready to connect
                    views.setTextViewText(slotStatusIds[i], "On • Ready")
                    views.setTextColor(slotStatusIds[i], colorOnSurfaceVariant)
                    views.setTextViewText(slotToggleIds[i], "ON")
                    views.setTextColor(slotToggleIds[i], colorToggleOnText)
                    views.setInt(slotToggleIds[i], "setBackgroundResource", R.drawable.widget_btn_allowed)
                }

                // Toggle click intent (uses unique request code so PendingIntents don't collide)
                val toggleIntent = Intent(context, BouncerAppWidgetProvider::class.java).apply {
                    action = ACTION_TOGGLE_DEVICE
                    putExtra(EXTRA_DEVICE_ADDRESS, dev.address)
                }
                val togglePi = PendingIntent.getBroadcast(
                    context,
                    2000 + i,
                    toggleIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(slotToggleIds[i], togglePi)

            } else {
                views.setViewVisibility(slotLayoutIds[i], View.GONE)
            }
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
