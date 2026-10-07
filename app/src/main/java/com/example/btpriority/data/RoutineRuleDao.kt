package com.example.btpriority.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineRuleDao {
    @Query("SELECT * FROM routine_rules ORDER BY id DESC")
    fun getAllRulesFlow(): Flow<List<RoutineRuleEntity>>

    @Query("SELECT * FROM routine_rules WHERE isEnabled = 1")
    suspend fun getEnabledRules(): List<RoutineRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RoutineRuleEntity): Long

    @Query("UPDATE routine_rules SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, isEnabled: Boolean): Int

    @Query("DELETE FROM routine_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long): Int
}
