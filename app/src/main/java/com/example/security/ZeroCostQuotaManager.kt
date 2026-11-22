package com.example.security

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Enforces Zero-Cost Spark free-tier quotas, warns at >=80% utilization,
 * and safely pauses affected features at 100% without ever upgrading to paid tiers.
 */
object ZeroCostQuotaManager {

    enum class QuotaHealth {
        HEALTHY,
        WARNING_80_PERCENT,
        PAUSED_LIMIT_REACHED
    }

    data class QuotaSnapshot(
        val aiUsedToday: Int,
        val aiDailyCap: Int,
        val writesUsedToday: Int,
        val writesDailyCap: Int,
        val aiGloballyEnabled: Boolean,
        val zeroCostLockActive: Boolean
    ) {
        val aiRatio: Float
            get() = if (aiDailyCap <= 0) 1f else (aiUsedToday.toFloat() / aiDailyCap).coerceIn(0f, 1f)

        val writesRatio: Float
            get() = if (writesDailyCap <= 0) 1f else (writesUsedToday.toFloat() / writesDailyCap).coerceIn(0f, 1f)

        val aiHealth: QuotaHealth
            get() = when {
                !aiGloballyEnabled || zeroCostLockActive || aiUsedToday >= aiDailyCap ->
                    QuotaHealth.PAUSED_LIMIT_REACHED
                aiRatio >= 0.8f -> QuotaHealth.WARNING_80_PERCENT
                else -> QuotaHealth.HEALTHY
            }

        val writeHealth: QuotaHealth
            get() = when {
                zeroCostLockActive || writesUsedToday >= writesDailyCap ->
                    QuotaHealth.PAUSED_LIMIT_REACHED
                writesRatio >= 0.8f -> QuotaHealth.WARNING_80_PERCENT
                else -> QuotaHealth.HEALTHY
            }
    }

    fun currentUtcDayKey(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }
}
