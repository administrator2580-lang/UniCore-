package com.example.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlin.math.min

private val Context.rateLimitDataStore by preferencesDataStore(name = "unicore_rate_limits")

/**
 * Pure deterministic rate-limit state machine + persistent DataStore wrapper.
 */
object RateLimitEngine {
    data class State(
        val count: Int = 0,
        val windowStart: Long = 0L,
        val lockedUntil: Long = 0L,
        val strikes: Int = 0
    )

    data class Evaluation(
        val nextState: State,
        val decision: RateLimiter.Decision
    )

    fun evaluate(
        state: State,
        bucket: RateLimiter.Bucket,
        nowMillis: Long
    ): Evaluation {
        if (nowMillis < state.lockedUntil) {
            val remainingSec = ((state.lockedUntil - nowMillis) / 1000L).coerceAtLeast(1L)
            return Evaluation(
                nextState = state,
                decision = RateLimiter.Decision.Blocked(
                    retryAfterSeconds = remainingSec,
                    reason = "Rate limit active for ${bucket.name.lowercase()}. Retry in ${remainingSec}s."
                )
            )
        }

        val resetWindow = nowMillis - state.windowStart > bucket.windowMillis
        val windowStart = if (resetWindow) nowMillis else state.windowStart
        val currentCount = if (resetWindow) 0 else state.count

        return if (currentCount >= bucket.maxAttemptsInWindow) {
            val strikes = (state.strikes + 1).coerceAtMost(5)
            val multiplier = 1L shl min(strikes - 1, 4)
            val lockoutMs = bucket.baseLockoutMillis * multiplier
            Evaluation(
                nextState = State(
                    count = currentCount,
                    windowStart = windowStart,
                    lockedUntil = nowMillis + lockoutMs,
                    strikes = strikes
                ),
                decision = RateLimiter.Decision.Blocked(
                    retryAfterSeconds = (lockoutMs / 1000L).coerceAtLeast(1L),
                    reason = "Too many attempts. Progressive cooldown activated (${lockoutMs / 1000L}s)."
                )
            )
        } else {
            Evaluation(
                nextState = state.copy(
                    count = currentCount + 1,
                    windowStart = windowStart
                ),
                decision = RateLimiter.Decision.Allowed
            )
        }
    }
}

class RateLimiter(
    private val context: Context,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() }
) {
    sealed interface Decision {
        data object Allowed : Decision
        data class Blocked(val retryAfterSeconds: Long, val reason: String) : Decision
    }

    enum class Bucket(
        val keyPrefix: String,
        val maxAttemptsInWindow: Int,
        val windowMillis: Long,
        val baseLockoutMillis: Long
    ) {
        AUTH("auth", maxAttemptsInWindow = 5, windowMillis = 60_000L, baseLockoutMillis = 30_000L),
        AI_TUTOR("ai", maxAttemptsInWindow = 4, windowMillis = 60_000L, baseLockoutMillis = 20_000L),
        QUIZ_SUBMIT("quiz", maxAttemptsInWindow = 3, windowMillis = 60_000L, baseLockoutMillis = 30_000L),
        ADMIN_ACTION("admin", maxAttemptsInWindow = 10, windowMillis = 60_000L, baseLockoutMillis = 15_000L)
    }

    suspend fun checkAndConsume(bucket: Bucket): Decision {
        val now = nowMillisProvider()
        val countKey = intPreferencesKey("${bucket.keyPrefix}_count")
        val windowStartKey = longPreferencesKey("${bucket.keyPrefix}_window_start")
        val lockedUntilKey = longPreferencesKey("${bucket.keyPrefix}_locked_until")
        val strikesKey = intPreferencesKey("${bucket.keyPrefix}_strikes")

        var result: Decision = Decision.Allowed
        context.rateLimitDataStore.edit { prefs ->
            val current = RateLimitEngine.State(
                count = prefs[countKey] ?: 0,
                windowStart = prefs[windowStartKey] ?: 0L,
                lockedUntil = prefs[lockedUntilKey] ?: 0L,
                strikes = prefs[strikesKey] ?: 0
            )
            val eval = RateLimitEngine.evaluate(current, bucket, now)
            prefs[countKey] = eval.nextState.count
            prefs[windowStartKey] = eval.nextState.windowStart
            prefs[lockedUntilKey] = eval.nextState.lockedUntil
            prefs[strikesKey] = eval.nextState.strikes
            result = eval.decision
        }
        return result
    }

    suspend fun resetBucket(bucket: Bucket) {
        context.rateLimitDataStore.edit { prefs ->
            prefs[intPreferencesKey("${bucket.keyPrefix}_count")] = 0
            prefs[longPreferencesKey("${bucket.keyPrefix}_locked_until")] = 0L
            prefs[intPreferencesKey("${bucket.keyPrefix}_strikes")] = 0
        }
    }

    suspend fun remainingCooldownSeconds(bucket: Bucket): Long {
        val prefs = context.rateLimitDataStore.data.first()
        val lockedUntil = prefs[longPreferencesKey("${bucket.keyPrefix}_locked_until")] ?: 0L
        val now = nowMillisProvider()
        return if (now < lockedUntil) ((lockedUntil - now) / 1000L).coerceAtLeast(1L) else 0L
    }
}
