package com.example.btpriority

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class RoutineDeviceRule(
    val macAddress: String,
    val deviceName: String,
    val shouldAllow: Boolean // true = allow/connect, false = block/disconnect
)

data class Routine(
    val id: String,
    val title: String,
    val description: String,
    val iconKey: String = "bolt", // "fitness", "work", "car", "bedtime", "bolt"
    val rules: List<RoutineDeviceRule> = emptyList(),
    val isCustom: Boolean = false
)

class RoutineManager(private val context: Context) {
    companion object {
        private const val TAG = "RoutineManager"
        private const val KEY_ACTIVE_ROUTINE_ID = "active_routine_id"
        private const val KEY_ROUTINES_JSON = "routines_json"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("bt_routines_prefs", Context.MODE_PRIVATE)

    fun getActiveRoutineId(): String? {
        return prefs.getString(KEY_ACTIVE_ROUTINE_ID, null)
    }

    fun setActiveRoutineId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_ACTIVE_ROUTINE_ID)
            else putString(KEY_ACTIVE_ROUTINE_ID, id)
        }.apply()
    }

    fun getRoutines(): List<Routine> {
        val jsonStr = prefs.getString(KEY_ROUTINES_JSON, null)
        if (jsonStr == null) {
            val defaults = getDefaultRoutines()
            saveRoutines(defaults)
            return defaults
        }
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Routine>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rulesArray = obj.optJSONArray("rules") ?: JSONArray()
                val rules = mutableListOf<RoutineDeviceRule>()
                for (j in 0 until rulesArray.length()) {
                    val rObj = rulesArray.getJSONObject(j)
                    rules.add(
                        RoutineDeviceRule(
                            macAddress = rObj.getString("mac"),
                            deviceName = rObj.optString("name", "Device"),
                            shouldAllow = rObj.getBoolean("allow")
                        )
                    )
                }
                list.add(
                    Routine(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        description = obj.optString("desc", ""),
                        iconKey = obj.optString("icon", "bolt"),
                        rules = rules,
                        isCustom = obj.optBoolean("isCustom", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.d(TAG, "Error parsing routines JSON: ${e.message}")
            getDefaultRoutines()
        }
    }

    fun saveRoutines(routines: List<Routine>) {
        val array = JSONArray()
        for (r in routines) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("desc", r.description)
                put("icon", r.iconKey)
                put("isCustom", r.isCustom)
                val rulesArray = JSONArray()
                for (rule in r.rules) {
                    rulesArray.put(JSONObject().apply {
                        put("mac", rule.macAddress)
                        put("name", rule.deviceName)
                        put("allow", rule.shouldAllow)
                    })
                }
                put("rules", rulesArray)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_ROUTINES_JSON, array.toString()).apply()
    }

    fun addOrUpdateRoutine(routine: Routine) {
        val current = getRoutines().toMutableList()
        val index = current.indexOfFirst { it.id == routine.id }
        if (index >= 0) {
            current[index] = routine
        } else {
            current.add(routine)
        }
        saveRoutines(current)
    }

    fun deleteRoutine(routineId: String) {
        if (getActiveRoutineId() == routineId) {
            setActiveRoutineId(null)
        }
        val current = getRoutines().filter { it.id != routineId }
        saveRoutines(current)
    }

    private fun getDefaultRoutines(): List<Routine> {
        return listOf(
            Routine(
                id = "workout",
                title = "Workout / Gym",
                description = "Prioritize headphones/earbuds and block secondary watches or speakers.",
                iconKey = "fitness"
            ),
            Routine(
                id = "work",
                title = "Work Meeting",
                description = "Focus audio exclusively on headset, silencing watch and media devices.",
                iconKey = "work"
            ),
            Routine(
                id = "driving",
                title = "Driving Mode",
                description = "Route calls and audio strictly to your car system.",
                iconKey = "car"
            ),
            Routine(
                id = "quiet",
                title = "Quiet / Sleep",
                description = "Block all Bluetooth audio peripherals to keep phone audio localized.",
                iconKey = "bedtime"
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun executeRoutine(routine: Routine, onComplete: () -> Unit) {
        val blockManager = DeviceBlockManager(context)
        val profileManager = BluetoothHelper.getManager(context)
        val bondedDevices = profileManager.adapter?.bondedDevices ?: emptySet()

        for (rule in routine.rules) {
            val device = bondedDevices.find { it.address == rule.macAddress }
            if (rule.shouldAllow) {
                blockManager.setBlocked(rule.macAddress, false)
                if (device != null) {
                    BluetoothHelper.connectDevice(context, device)
                }
            } else {
                blockManager.setBlocked(rule.macAddress, true)
                if (device != null) {
                    BluetoothHelper.disconnectDevice(context, device)
                }
            }
        }
        setActiveRoutineId(routine.id)
        onComplete()
    }

    fun deactivateRoutine(onComplete: () -> Unit) {
        val blockManager = DeviceBlockManager(context)
        val activeId = getActiveRoutineId()
        if (activeId != null) {
            val routine = getRoutines().find { it.id == activeId }
            if (routine != null) {
                for (rule in routine.rules) {
                    if (!rule.shouldAllow) {
                        blockManager.setBlocked(rule.macAddress, false)
                    }
                }
            }
        }
        setActiveRoutineId(null)
        onComplete()
    }
}
