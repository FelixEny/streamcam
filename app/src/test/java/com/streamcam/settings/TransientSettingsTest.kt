package com.streamcam.settings

import com.streamcam.camera.CameraSettings
import com.streamcam.camera.SettingsCoalescer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientSettingsTest {

    private fun cameraSettings(shutterNs: Long) = CameraSettings(shutterNs = shutterNs)

    @Test
    fun `update applies immediately but never persists`() {
        val applied = mutableListOf<CameraSettings>()
        val persisted = mutableListOf<CameraSettings>()
        val settings = TransientSettings(
            CameraSettings(),
            onApply = { applied += it },
            onPersist = { persisted += it },
        )

        repeat(60) { settings.update(cameraSettings(it.toLong())) }

        assertEquals("every transient update reaches the camera", 60, applied.size)
        assertTrue("no DataStore write per sample", persisted.isEmpty())
    }

    @Test
    fun `commit persists the latest value exactly once`() {
        val persisted = mutableListOf<CameraSettings>()
        val settings = TransientSettings(
            CameraSettings(),
            onPersist = { persisted += it },
        )

        repeat(60) { settings.update(cameraSettings(it.toLong())) }
        settings.commit()

        assertEquals("one write for a whole drag", 1, persisted.size)
        assertEquals("final value, not an intermediate", 59L, persisted.single().shutterNs)
    }

    @Test
    fun `commit persists the value current at commit time, never a stale one`() {
        val persisted = mutableListOf<CameraSettings>()
        val settings = TransientSettings(
            CameraSettings(),
            onPersist = { persisted += it },
        )

        settings.update(cameraSettings(10L))
        settings.commit()
        settings.update(cameraSettings(20L))
        settings.commit()

        assertEquals(listOf(10L, 20L), persisted.map { it.shutterNs })
    }

    @Test
    fun `commit without a prior update persists the current value`() {
        val persisted = mutableListOf<CameraSettings>()
        val settings = TransientSettings(
            cameraSettings(7L),
            onPersist = { persisted += it },
        )

        settings.commit()

        assertEquals(listOf(7L), persisted.map { it.shutterNs })
    }

    @Test
    fun `stream settings follow the same transient then commit contract`() {
        val applied = mutableListOf<StreamSettings>()
        val persisted = mutableListOf<StreamSettings>()
        val settings = TransientSettings(
            StreamSettings(),
            onApply = { applied += it },
            onPersist = { persisted += it },
        )

        repeat(60) { settings.update(StreamSettings(bitrateBps = 2_000_000 + it)) }
        settings.commit()

        assertEquals(60, applied.size)
        assertEquals(1, persisted.size)
        assertEquals(2_000_059, persisted.single().bitrateBps)
    }

    /**
     * End-to-end pipeline: 60 rapid slider samples through the transient holder into the engine's
     * coalescer. Asserts the camera does not process a backlog of 60 requests and that exactly one
     * storage write happens, with the final requested shutter applied.
     */
    @Test
    fun `60 rapid slider updates produce one camera apply and one storage write`() {
        val coalescer = SettingsCoalescer()
        val cameraApplies = mutableListOf<CameraSettings>()
        val persists = mutableListOf<CameraSettings>()
        var updatesReceived = 0

        val settings = TransientSettings(
            CameraSettings(),
            // Mirrors CameraViewModel: transient updates go to the engine (coalesced).
            onApply = {
                updatesReceived++
                coalescer.submit(it)
            },
            onPersist = { persists += it },
        )

        // The drag: 60 samples, faster than the camera thread can drain them.
        repeat(60) { i -> settings.update(cameraSettings(16_666_667L + i)) }

        // The camera thread runs its single posted drain (CameraEngine.drainApply).
        while (true) {
            val next = coalescer.nextToApply() ?: break
            cameraApplies += next
        }

        // The drag ends: the control commits once.
        settings.commit()

        assertEquals("settings updates received", 60, updatesReceived)
        assertEquals(
            "camera apply operations executed (no 60-deep backlog)",
            1,
            cameraApplies.size,
        )
        assertEquals(
            "final requested shutter applied",
            16_666_667L + 59,
            cameraApplies.last().shutterNs,
        )
        assertEquals("DataStore writes", 1, persists.size)
        assertEquals(
            "persisted value is the final one",
            16_666_667L + 59,
            persists.single().shutterNs,
        )
    }

    /**
     * Contrast with the previous implementation, where each slider sample called `engine.apply`
     * (posting one camera task that ran `applyInternal`) and launched one DataStore save. The old
     * side below models that per-sample structure; the new side runs the real pipeline.
     */
    @Test
    fun `new pipeline issues far fewer camera applies and storage writes than the per-sample path`() {
        // Old: one camera apply + one DataStore write per sample (the reported lag source).
        var oldCameraApplies = 0
        var oldDataStoreWrites = 0
        repeat(60) { i ->
            CameraSettings(shutterNs = 16_666_667L + i) // queued, processed later in order
            oldCameraApplies++
            oldDataStoreWrites++
        }

        // New: coalesced applies + a single commit when the drag finishes.
        val coalescer = SettingsCoalescer()
        var newCameraApplies = 0
        var newDataStoreWrites = 0
        val settings = TransientSettings(
            CameraSettings(),
            onApply = { coalescer.submit(it) },
            onPersist = { newDataStoreWrites++ },
        )
        repeat(60) { i -> settings.update(CameraSettings(shutterNs = 16_666_667L + i)) }
        while (true) {
            val next = coalescer.nextToApply() ?: break
            newCameraApplies++
            // model the apply consuming the value
            assertEquals(16_666_667L + 59, next.shutterNs)
        }
        settings.commit()

        assertEquals(60, oldCameraApplies)
        assertEquals(60, oldDataStoreWrites)
        assertEquals(1, newCameraApplies)
        assertEquals(1, newDataStoreWrites)
        assertTrue("camera applies reduced", newCameraApplies < oldCameraApplies)
        assertTrue("storage writes reduced", newDataStoreWrites < oldDataStoreWrites)
    }
}
