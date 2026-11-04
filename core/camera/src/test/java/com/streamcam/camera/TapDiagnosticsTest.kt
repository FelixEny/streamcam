package com.streamcam.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDiagnosticsTest {

    @Test
    fun `inactive until a tap starts`() {
        val diagnostics = TapDiagnostics()
        assertFalse(diagnostics.active)
        assertFalse("nothing to log before a tap", diagnostics.onReturnedRegions("[region]"))
    }

    @Test
    fun `returned regions are logged once per distinct value`() {
        val diagnostics = TapDiagnostics()
        diagnostics.start()
        assertTrue(diagnostics.active)
        assertTrue("first value", diagnostics.onReturnedRegions("region-a"))
        assertFalse("same value again", diagnostics.onReturnedRegions("region-a"))
        assertTrue("changed value", diagnostics.onReturnedRegions("region-b"))
        assertFalse(diagnostics.onReturnedRegions(null))
    }

    @Test
    fun `terminal af states end the attempt`() {
        for (state in listOf(
            Camera2Constants.AF_STATE_FOCUSED_LOCKED,
            Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED,
        )) {
            val diagnostics = TapDiagnostics()
            diagnostics.start()
            diagnostics.onAfState(state)
            assertFalse("state=$state must finish the attempt", diagnostics.active)
            assertFalse("state=$state must stop logging", diagnostics.onReturnedRegions("region"))
        }
    }

    @Test
    fun `non-terminal af states keep the attempt running`() {
        for (state in listOf(
            Camera2Constants.AF_STATE_INACTIVE,
            Camera2Constants.AF_STATE_ACTIVE_SCAN,
            Camera2Constants.AF_STATE_PASSIVE_SCAN,
            Camera2Constants.AF_STATE_PASSIVE_FOCUSED,
            Camera2Constants.AF_STATE_PASSIVE_UNFOCUSED,
        )) {
            val diagnostics = TapDiagnostics()
            diagnostics.start()
            diagnostics.onAfState(state)
            assertTrue("state=$state must not finish the attempt", diagnostics.active)
        }
    }

    @Test
    fun `reset clears the attempt and its remembered region`() {
        val diagnostics = TapDiagnostics()
        diagnostics.start()
        diagnostics.onReturnedRegions("region-a")
        diagnostics.reset()

        assertFalse(diagnostics.active)
        assertFalse("reset must stop logging", diagnostics.onReturnedRegions("region-b"))

        // A new attempt must not inherit the old region, even if the HAL repeats it verbatim.
        diagnostics.start()
        assertTrue(diagnostics.active)
        assertTrue("region-a must log again", diagnostics.onReturnedRegions("region-a"))
    }
}
