package com.streamcam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSizeChooserTest {

    private val sizes = listOf(
        IntSize(4096, 3072),
        IntSize(1920, 1080),
        IntSize(1280, 720),
        IntSize(640, 480),
    )

    @Test
    fun `prefers 1080p then 720p then vga`() {
        assertEquals(IntSize(1920, 1080), StreamSizeChooser.choosePreviewSize(sizes))
        assertEquals(
            IntSize(1280, 720),
            StreamSizeChooser.choosePreviewSize(sizes.filter { it != IntSize(1920, 1080) }),
        )
        assertEquals(
            IntSize(640, 480),
            StreamSizeChooser.choosePreviewSize(listOf(IntSize(4096, 3072), IntSize(640, 480))),
        )
    }

    @Test
    fun `falls back to largest when no preferred size is present`() {
        assertEquals(
            IntSize(4096, 3072),
            StreamSizeChooser.choosePreviewSize(listOf(IntSize(4096, 3072), IntSize(2560, 1440))),
        )
        assertNull(StreamSizeChooser.choosePreviewSize(emptyList()))
    }

    @Test
    fun `output size honours supported preference`() {
        assertEquals(
            IntSize(1280, 720),
            StreamSizeChooser.chooseOutputSize(sizes, IntSize(1280, 720)),
        )
        assertEquals(
            IntSize(1920, 1080),
            StreamSizeChooser.chooseOutputSize(sizes, IntSize(1920, 1080)),
        )
    }

    @Test
    fun `unsupported output preference falls back to auto choice`() {
        assertEquals(
            IntSize(1920, 1080),
            StreamSizeChooser.chooseOutputSize(sizes, IntSize(3840, 2160)),
        )
        assertEquals(
            IntSize(1920, 1080),
            StreamSizeChooser.chooseOutputSize(sizes, null),
        )
    }

    @Test
    fun `output size empty list is null`() {
        assertNull(StreamSizeChooser.chooseOutputSize(emptyList(), IntSize(1920, 1080)))
    }
}
