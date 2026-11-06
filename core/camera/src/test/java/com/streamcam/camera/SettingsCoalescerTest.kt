package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCoalescerTest {

    private fun settingsFor(shutterNs: Long) = CameraSettings(shutterNs = shutterNs)

    /** Runs the drain exactly as CameraEngine.drainApply() does, collecting every applied value. */
    private fun drain(coalescer: SettingsCoalescer): List<CameraSettings> {
        val applied = mutableListOf<CameraSettings>()
        while (true) {
            val next = coalescer.nextToApply() ?: break
            applied += next
        }
        return applied
    }

    @Test
    fun `first submit schedules a drain, the rest do not`() {
        val coalescer = SettingsCoalescer()
        val scheduledFlags = (0 until 60).map { coalescer.submit(settingsFor(it.toLong())) }

        assertTrue("first update must schedule a drain", scheduledFlags.first())
        assertFalse(
            "remaining 59 updates must not queue more drains",
            scheduledFlags.drop(1).any { it },
        )
    }

    @Test
    fun `burst of 60 updates collapses to a single apply of the newest value`() {
        val coalescer = SettingsCoalescer()
        repeat(60) { coalescer.submit(settingsFor(it.toLong())) }

        val applied = drain(coalescer)

        assertEquals("one apply for 60 updates", 1, applied.size)
        assertEquals("newest value wins", 59L, applied.single().shutterNs)
        assertNull("drain is idle afterwards", coalescer.nextToApply())
    }

    @Test
    fun `intermediate pending values are discarded`() {
        val coalescer = SettingsCoalescer()
        coalescer.submit(settingsFor(1L))
        coalescer.submit(settingsFor(2L))
        coalescer.submit(settingsFor(3L))

        val applied = drain(coalescer)

        assertEquals(listOf(3L), applied.map { it.shutterNs })
    }

    @Test
    fun `only one apply loop can run at a time`() {
        val coalescer = SettingsCoalescer()
        // A drain is scheduled but has not run yet: further submits must not schedule a second.
        assertTrue(coalescer.submit(settingsFor(1L)))
        repeat(10) { assertFalse(coalescer.submit(settingsFor(it.toLong()))) }

        // The drain takes a value (apply starts) and stays "scheduled", so updates arriving
        // during the apply still do not schedule a second loop.
        assertEquals(9L, coalescer.nextToApply()?.shutterNs)
        repeat(10) { assertFalse(coalescer.submit(settingsFor((it + 100).toLong()))) }

        // Still the same single loop: it picks up the newest mid-apply value, then goes idle.
        assertEquals(109L, coalescer.nextToApply()?.shutterNs)
        assertNull(coalescer.nextToApply())
    }

    @Test
    fun `update arriving while an apply is running is not lost`() {
        val coalescer = SettingsCoalescer()
        coalescer.submit(settingsFor(1L))

        // Drain takes the first value (apply starts) …
        val first = coalescer.nextToApply()
        assertEquals(1L, first?.shutterNs)

        // … a newer update arrives mid-apply …
        coalescer.submit(settingsFor(2L))

        // … and the drain picks it up on the next iteration instead of dropping it.
        val applied = drain(coalescer)
        assertEquals(listOf(2L), applied.map { it.shutterNs })
    }

    @Test
    fun `drain ends only when nothing is pending, then a new update schedules again`() {
        val coalescer = SettingsCoalescer()
        coalescer.submit(settingsFor(1L))
        drain(coalescer) // drains to idle, clearing the scheduled flag

        assertNull(coalescer.nextToApply())
        assertTrue("idle again, so the next update schedules", coalescer.submit(settingsFor(2L)))
        assertEquals(listOf(2L), drain(coalescer).map { it.shutterNs })
    }

    @Test
    fun `reopened session receives the newest settings, never a stale intermediate`() {
        // Models close/reopen: the coalescer holds only the latest desired value, so whatever
        // session is current when the drain runs gets the newest settings.
        val coalescer = SettingsCoalescer()
        coalescer.submit(settingsFor(1L)) // stale (previous session's value)
        coalescer.submit(settingsFor(2L)) // superseded
        coalescer.submit(settingsFor(9L)) // newest

        assertEquals(listOf(9L), drain(coalescer).map { it.shutterNs })
    }

    @Test
    fun `iso and shutter survive coalescing intact`() {
        val coalescer = SettingsCoalescer()
        val desired = CameraSettings(
            exposureMode = ExposureMode.MANUAL,
            iso = 800,
            shutterNs = 16_666_667L,
        )
        coalescer.submit(CameraSettings())
        coalescer.submit(desired)

        val applied = drain(coalescer).single()
        assertEquals(ExposureMode.MANUAL, applied.exposureMode)
        assertEquals(800, applied.iso)
        assertEquals(16_666_667L, applied.shutterNs)
    }

    @Test
    fun `other settings are not lost when updates are coalesced`() {
        val coalescer = SettingsCoalescer()
        coalescer.submit(CameraSettings())
        coalescer.submit(
            CameraSettings(
                whiteBalanceMode = WhiteBalanceMode.MANUAL,
                kelvin = 3200,
                tint = 12,
                focusMode = FocusMode.MANUAL,
                focusDistanceDiopters = 2.5f,
                zoomRatio = 4f,
                zoomWheelRate = 0.6f,
            ),
        )

        val applied = drain(coalescer).single()
        assertEquals(WhiteBalanceMode.MANUAL, applied.whiteBalanceMode)
        assertEquals(3200, applied.kelvin)
        assertEquals(12, applied.tint)
        assertEquals(FocusMode.MANUAL, applied.focusMode)
        assertEquals(2.5f, applied.focusDistanceDiopters, 0f)
        assertEquals(4f, applied.zoomRatio, 0f)
        assertEquals(0.6f, applied.zoomWheelRate, 0f)
    }
}
