package com.streamcam.common.resilience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffPolicyTest {

    @Test
    fun `delays grow exponentially from the initial delay`() {
        val policy = BackoffPolicy(initialDelayMs = 500L, multiplier = 2.0)
        assertEquals(500L, policy.delayMsFor(1))
        assertEquals(1_000L, policy.delayMsFor(2))
        assertEquals(2_000L, policy.delayMsFor(3))
        assertEquals(4_000L, policy.delayMsFor(4))
        assertEquals(8_000L, policy.delayMsFor(5))
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        val policy = BackoffPolicy(initialDelayMs = 500L, multiplier = 2.0, maxDelayMs = 3_000L, maxAttempts = 4)
        assertEquals(500L, policy.delayMsFor(1))
        assertEquals(1_000L, policy.delayMsFor(2))
        assertEquals(2_000L, policy.delayMsFor(3))
        assertEquals(3_000L, policy.delayMsFor(4))
    }

    @Test
    fun `attempts beyond the limit have no delay`() {
        val policy = BackoffPolicy(maxAttempts = 3)
        assertNull(policy.delayMsFor(4))
        assertNull(policy.delayMsFor(100))
    }

    @Test
    fun `canRetry follows the attempt limit`() {
        val policy = BackoffPolicy(maxAttempts = 3)
        assertTrue(policy.canRetry(1))
        assertTrue(policy.canRetry(3))
        assertFalse(policy.canRetry(4))
    }

    @Test
    fun `multiplier of one keeps a constant delay`() {
        val policy = BackoffPolicy(initialDelayMs = 750L, multiplier = 1.0, maxAttempts = 3)
        assertEquals(750L, policy.delayMsFor(1))
        assertEquals(750L, policy.delayMsFor(2))
        assertEquals(750L, policy.delayMsFor(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `attempt numbers are one based`() {
        BackoffPolicy().delayMsFor(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxDelay below initial delay is rejected`() {
        BackoffPolicy(initialDelayMs = 2_000L, maxDelayMs = 1_000L)
    }
}
