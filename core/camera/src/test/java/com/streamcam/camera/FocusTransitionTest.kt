package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusTransitionTest {

    @Test
    fun `focus success locks at the measured distance`() {
        val (intent, ui) = FocusTransition.onAfState(
            FocusIntent.TAPPING(FocusRegion(0, 0, 10, 10, 1000)),
            Camera2Constants.AF_STATE_FOCUSED_LOCKED,
            focusDistance = 2.5f,
        )
        assertEquals(FocusIntent.LOCKED(2.5f), intent)
        assertEquals(FocusState.LOCKED, ui)
    }

    @Test
    fun `focus failure reports FAILED and releases the intent`() {
        val (intent, ui) = FocusTransition.onAfState(
            FocusIntent.TAPPING(FocusRegion(0, 0, 10, 10, 1000)),
            Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED,
            focusDistance = 0.4f,
        )
        assertEquals(FocusIntent.IDLE, intent)
        assertEquals(FocusState.FAILED, ui)
    }

    @Test
    fun `failure never reports a successful lock`() {
        val (_, ui) = FocusTransition.onAfState(
            FocusIntent.TAPPING(null),
            Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED,
            focusDistance = null,
        )
        assertEquals(FocusState.FAILED, ui)
    }

    @Test
    fun `lock is invalidated when the HAL re-hunts`() {
        val (intent, ui) = FocusTransition.onAfState(
            FocusIntent.LOCKED(1.5f),
            Camera2Constants.AF_STATE_PASSIVE_SCAN,
            focusDistance = null,
        )
        assertEquals(FocusIntent.IDLE, intent)
        assertEquals(FocusState.IDLE, ui)
    }

    @Test
    fun `active scan during a hold also releases it`() {
        val (intent, ui) = FocusTransition.onAfState(
            FocusIntent.LOCKED(1.5f),
            Camera2Constants.AF_STATE_ACTIVE_SCAN,
            focusDistance = null,
        )
        assertEquals(FocusIntent.IDLE, intent)
        assertEquals(FocusState.IDLE, ui)
    }

    @Test
    fun `scanning states keep the tap pending without UI changes`() {
        for (state in listOf(
            Camera2Constants.AF_STATE_ACTIVE_SCAN,
            Camera2Constants.AF_STATE_PASSIVE_SCAN,
            Camera2Constants.AF_STATE_INACTIVE,
        )) {
            val (intent, ui) = FocusTransition.onAfState(FocusIntent.TAPPING(null), state, null)
            assertEquals(FocusIntent.TAPPING(null), intent)
            assertNull(ui)
        }
    }

    @Test
    fun `idle intent ignores all HAL states`() {
        for (state in listOf(
            Camera2Constants.AF_STATE_PASSIVE_FOCUSED,
            Camera2Constants.AF_STATE_NOT_FOCUSED_LOCKED,
            Camera2Constants.AF_STATE_FOCUSED_LOCKED,
        )) {
            val (intent, ui) = FocusTransition.onAfState(FocusIntent.IDLE, state, null)
            assertEquals(FocusIntent.IDLE, intent)
            assertNull(ui)
        }
    }
}
