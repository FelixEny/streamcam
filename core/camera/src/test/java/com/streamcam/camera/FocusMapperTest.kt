package com.streamcam.camera

import com.streamcam.imageproc.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusMapperTest {

    private fun region(
        tapX: Float,
        tapY: Float,
        viewWidth: Int = 2992,
        viewHeight: Int = 1344,
        bufferWidth: Int = 1920,
        bufferHeight: Int = 1080,
        displayRotation: Int = 90,
        lensFacing: Int = Camera2Constants.LENS_FACING_BACK,
        activeW: Int = 4000,
        activeH: Int = 3000,
        maxAfRegions: Int = 1,
        scalerCrop: IntRect? = null,
    ) = FocusMapper.toRegion(
        tapX = tapX, tapY = tapY,
        viewWidth = viewWidth, viewHeight = viewHeight,
        bufferWidth = bufferWidth, bufferHeight = bufferHeight,
        displayRotation = displayRotation,
        lensFacing = lensFacing,
        activeArrayWidth = activeW, activeArrayHeight = activeH,
        maxAfRegions = maxAfRegions,
        scalerCrop = scalerCrop,
    )

    // --- Centre / rotation coverage -----------------------------------------------

    @Test
    fun `centre tap maps to the active array centre for all rotations`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val r = assertNotNull(
                "rotation=$rotation should produce a region",
                region(tapX = 1496f, tapY = 672f, displayRotation = rotation),
            )
            val centreX = r.left + r.width / 2
            val centreY = r.top + r.height / 2
            assertEquals("rotation=$rotation x", 2000.0, centreX.toDouble(), 2.0)
            assertEquals("rotation=$rotation y", 1500.0, centreY.toDouble(), 2.0)
        }
    }

    // --- Mirroring ----------------------------------------------------------------

    @Test
    fun `front camera mirror flips the horizontal mapping`() {
        // Use rotation 0 so the view x-axis maps straight onto the sensor x-axis.
        val viewW = 2992
        val front = Camera2Constants.LENS_FACING_FRONT
        val back = Camera2Constants.LENS_FACING_BACK
        val left = assertNotNull("front left tap",
            region(600f, 672f, viewWidth = viewW, displayRotation = 0, lensFacing = front))
        val right = assertNotNull("front right tap",
            region((viewW - 600).toFloat(), 672f, viewWidth = viewW, displayRotation = 0, lensFacing = front))
        val centre = assertNotNull("front centre tap",
            region(1496f, 672f, displayRotation = 0, lensFacing = front))
        val backLeft = assertNotNull("back left tap",
            region(600f, 672f, viewWidth = viewW, displayRotation = 0, lensFacing = back))
        val leftCx = left.left + left.width / 2
        val rightCx = right.left + right.width / 2
        val centreCx = centre.left + centre.width / 2
        val backLeftCx = backLeft.left + backLeft.width / 2
        // Mirrored: front-left lands right of centre, front-right lands left of centre.
        assertTrue("front left tap should land right of centre", leftCx > centreCx)
        assertTrue("front right tap should land left of centre", rightCx < centreCx)
        // Sanity: the same tap on the back camera lands on the opposite side.
        assertTrue("back left tap should land left of centre", backLeftCx < centreCx)
    }

    // --- Letterbox rejection -------------------------------------------------------

    @Test
    fun `taps in the letterbox bars are rejected`() {
        // rotation 90: content is 2112 wide in a 2992 view -> 440 px bars each side.
        assertNull("left bar", region(100f, 672f))
        assertNull("right bar", region(2900f, 672f))
    }

    @Test
    fun `taps inside the content are accepted`() {
        assertNotNull(region(1496f, 672f))
    }

    // --- Region capability gating --------------------------------------------------

    @Test
    fun `camera without af regions returns null`() {
        assertNull(region(1496f, 672f, maxAfRegions = 0))
    }

    // --- Crop / zoom ---------------------------------------------------------------

    @Test
    fun `region stays inside the live scaler crop when zoomed`() {
        val crop = IntRect(800, 600, 3200, 2400)
        val r = assertNotNull("zoomed region", region(1496f, 672f, scalerCrop = crop))
        assertTrue(r.left >= crop.left)
        assertTrue(r.top >= crop.top)
        assertTrue(r.left + r.width <= crop.left + crop.width)
        assertTrue(r.top + r.height <= crop.top + crop.height)
    }

    @Test
    fun `zoomed crop shifts the mapping towards the crop origin`() {
        val full = assertNotNull("full region", region(900f, 672f))
        val crop = IntRect(800, 600, 3200, 2400)
        val zoomed = assertNotNull("zoomed region", region(900f, 672f, scalerCrop = crop))
        val fullCy = full.top + full.height / 2
        val zoomedCy = zoomed.top + zoomed.height / 2
        // Same tap, smaller visible area: the sensor point must differ from the unzoomed one.
        assertTrue("zoom must change the sensor mapping", fullCy != zoomedCy)
    }

    // --- Degenerate inputs ---------------------------------------------------------

    @Test
    fun `degenerate geometry returns null instead of a bogus region`() {
        assertNull(region(100f, 100f, viewWidth = 0))
        assertNull(region(100f, 100f, bufferWidth = 0))
        assertNull(region(100f, 100f, activeW = 0))
    }

    // --- Preview-to-sensor aspect-ratio crop (stream crop) --------------------------

    @Test
    fun `streamCrop centres a 16 by 9 preview inside a 4 by 3 sensor`() {
        // Pixel 8 Pro rear: active array 4080x3072, preview 1920x1080.
        val crop = FocusMapper.streamCrop(4080, 3072, 1920, 1080)
        assertEquals(0, crop.left)
        assertEquals(4080, crop.right)
        assertEquals(388, crop.top)
        assertEquals(2683, crop.bottom)
    }

    @Test
    fun `streamCrop is centred inside a zoomed scaler crop`() {
        val scaler = IntRect(800, 600, 3200, 2400)
        val crop = FocusMapper.streamCrop(4000, 3000, 1920, 1080, scaler)
        assertEquals(800, crop.left)
        assertEquals(3200, crop.right)
        assertEquals(825, crop.top)
        assertEquals(2175, crop.bottom)
    }

    @Test
    fun `streamCrop is identity-aspect when buffer matches the sensor`() {
        val crop = FocusMapper.streamCrop(4000, 3000, 4, 3)
        assertEquals(0, crop.left)
        assertEquals(4000, crop.right)
        assertEquals(0, crop.top)
        assertEquals(3000, crop.bottom)
    }

    private fun regionSquare(
        tapX: Float,
        tapY: Float,
        lensFacing: Int = Camera2Constants.LENS_FACING_BACK,
        scalerCrop: IntRect? = null,
    ) = FocusMapper.toRegion(
        tapX = tapX, tapY = tapY,
        viewWidth = 1920, viewHeight = 1080,
        bufferWidth = 1920, bufferHeight = 1080,
        displayRotation = 0,
        lensFacing = lensFacing,
        activeArrayWidth = 4000, activeArrayHeight = 3000,
        maxAfRegions = 1,
        scalerCrop = scalerCrop,
    )

    @Test
    fun `centre tap maps to the stream crop centre`() {
        val r = assertNotNull("centre", regionSquare(960f, 540f))
        assertEquals(2000, r.left + r.width / 2)
        assertEquals(1500, r.top + r.height / 2)
    }

    @Test
    fun `top centre tap maps to the top of the stream crop`() {
        val r = assertNotNull("top", regionSquare(960f, 0f))
        // stream crop top = 375; region is clamped to it.
        assertEquals(375, r.top)
    }

    @Test
    fun `bottom centre tap maps to the bottom of the stream crop`() {
        val r = assertNotNull("bottom", regionSquare(960f, 1079f))
        assertEquals(2625 - r.height, r.top) // bottom edge of stream crop
    }

    @Test
    fun `left edge tap maps to the left of the stream crop`() {
        val r = assertNotNull("left", regionSquare(0f, 540f))
        assertEquals(0, r.left)
    }

    @Test
    fun `right edge tap maps to the right of the stream crop`() {
        val r = assertNotNull("right", regionSquare(1919f, 540f))
        assertEquals(4000 - r.width, r.left)
    }

    @Test
    fun `front camera mirror flips the horizontal mapping in the stream crop`() {
        val back = assertNotNull("back left", regionSquare(200f, 540f))
        val front = assertNotNull("front left", regionSquare(200f, 540f, lensFacing = Camera2Constants.LENS_FACING_FRONT))
        assertTrue("mirror must flip the sensor x", (back.left + back.width / 2) < (front.left + front.width / 2))
    }

    @Test
    fun `zoomed stream crop shifts the mapping towards the crop origin`() {
        val full = assertNotNull("full", region(900f, 672f, viewWidth = 2992, viewHeight = 1344, displayRotation = 90))
        val scaler = IntRect(800, 600, 3200, 2400)
        val zoomed = assertNotNull("zoomed", region(900f, 672f, viewWidth = 2992, viewHeight = 1344, displayRotation = 90, scalerCrop = scaler))
        val fullCy = full.top + full.height / 2
        val zoomedCy = zoomed.top + zoomed.height / 2
        assertTrue("zoom must change the sensor mapping", fullCy != zoomedCy)
    }

    @Test
    fun `region stays inside the stream crop when zoomed`() {
        val scaler = IntRect(800, 600, 3200, 2400)
        val r = assertNotNull("zoomed", regionSquare(960f, 540f, scalerCrop = scaler))
        val crop = FocusMapper.streamCrop(4000, 3000, 1920, 1080, scaler)
        assertTrue(r.left >= crop.left)
        assertTrue(r.top >= crop.top)
        assertTrue(r.left + r.width <= crop.right)
        assertTrue(r.top + r.height <= crop.bottom)
    }

    @Test
    fun `degenerate streamCrop inputs do not crash`() {
        assertEquals(IntRect(0, 0, 0, 0), FocusMapper.streamCrop(0, 0, 1920, 1080))
        assertEquals(IntRect(0, 0, 10, 10), FocusMapper.streamCrop(10, 10, 0, 0))
    }

    private fun assertNotNull(message: String, region: FocusRegion?): FocusRegion {
        assertTrue(message, region != null)
        return region!!
    }
}
