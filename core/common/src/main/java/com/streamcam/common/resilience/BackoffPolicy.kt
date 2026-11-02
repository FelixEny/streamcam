package com.streamcam.common.resilience

/**
 * Exponential retry schedule with a cap and a bounded attempt count. Pure data (no timers): the
 * caller owns the coroutine and just asks this policy for the delay of the next attempt, so reopen
 * behavior is deterministic and unit-testable.
 */
data class BackoffPolicy(
    val initialDelayMs: Long = 500L,
    val multiplier: Double = 2.0,
    val maxDelayMs: Long = 8_000L,
    val maxAttempts: Int = 5,
) {
    init {
        require(initialDelayMs > 0L) { "initialDelayMs must be positive" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
        require(maxDelayMs >= initialDelayMs) { "maxDelayMs must be >= initialDelayMs" }
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    /** Delay before [attempt] (1-based), or null once the attempts are exhausted. */
    fun delayMsFor(attempt: Int): Long? {
        require(attempt >= 1) { "attempt is 1-based" }
        if (attempt > maxAttempts) return null
        val raw = initialDelayMs * Math.pow(multiplier, (attempt - 1).toDouble())
        return raw.coerceAtMost(maxDelayMs.toDouble()).toLong()
    }

    fun canRetry(attempt: Int): Boolean = attempt <= maxAttempts
}
