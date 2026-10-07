package com.example.btpriority.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routine_rules")
data class RoutineRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val ruleName: String = "",
    // Comma-separated MAC addresses & names for trigger devices (1 or many)
    val triggerAddresses: String = "",
    val triggerNames: String = "",
    // Comma-separated MAC addresses & names for blocked devices (1 or many)
    val blockedAddresses: String = "",
    val blockedNames: String = "",
    // Location tracking condition (optional)
    val hasLocationCondition: Boolean = false,
    val locationName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 150f,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getTriggerList(): List<String> = triggerAddresses.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }

    fun getTriggerNameList(): List<String> = triggerNames.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun getBlockedList(): List<String> = blockedAddresses.split(",")
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }

    fun getBlockedNameList(): List<String> = blockedNames.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
