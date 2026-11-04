package com.streamcam.camera

import com.streamcam.imageproc.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        activeArray: IntRect = IntRect(0, 0, 4000, 3000),
        maxAfRegions: Int = 1,
        scalerCrop: IntRect? = null,
    ) = FocusMapper.toRegion(
        tapX = tapX, tapY = tapY,
        viewWidth = viewWidth, viewHeight = viewHeight,
        bufferWidth = bufferWidth, bufferHeight = bufferHeight,
        displayRotation = displayRotation,
        lensFacing = lensFacing,
        activeArray = activeArray,
        maxAfRegions = maxAfRegions,
        scalerCrop = scalerCrop,
    )

    /** View and buffer share the 1920x1080 aspect so view pixels map 1:1 onto buffer pixels. */
    private fun squareRegion(
        tapX: Float,
        tapY: Float,
        lensFacing: Int = Camera2Constants.LENS_FACING_BACK,
        activeArray: IntRect = IntRect(0, 0, 4000, 3000),
        scalerCrop: IntRect? = null,
    ) = FocusMapper.toRegion(
        tapX = tapX, tapY = tapY,
        viewWidth = 1920, viewHeight = 1080,
        bufferWidth = 1920, bufferHeight = 1080,
        displayRotation = 0,
        lensFacing = lensFacing,
        activeArray = activeArray,
        maxAfRegions = 1,
        scalerCrop = scalerCrop,
    )

    private fun centreX(r: FocusRegion) = r.left + r.width / 2
    private fun centreY(r: FocusRegion) = r.top + r.height / 2

    // --- Rotation / mirroring ------------------------------------------------------

    @Test
    fun `centre tap maps to the active array centre for all rotations`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val r = assertNotNull(
                "rotation=$rotation should produce a region",
                region(tapX = 1496f, tapY = 672f, displayRotation = rotation),
            )
            assertEquals("rotation=$rotation x", 2000.0, centreX(r).toDouble(), 2.0)
            assertEquals("rotation=$rotation y", 1500.0, centreY(r).toDouble(), 2.0)
        }
    }

    @Test
    fun `a tap above centre follows each display rotation`() {
        // view 2992x1344, buffer 1920x1080, active 4000x3000, stream crop (0,375,4000,2625).
        // Rotating the same above-centre tap must move around the sensor centre, never drift.
        val rot0 = assertNotNull("rot 0", region(1496f, 472f, displayRotation = 0))
        val rot90 = assertNotNull("rot 90", region(1496f, 472f, displayRotation = 90))
        val rot180 = assertNotNull("rot 180", region(1496f, 472f, displayRotation = 180))
        val rot270 = assertNotNull("rot 270", region(1496f, 472f, displayRotation = 270))

        assertEquals(2000.0, centreX(rot0).toDouble(), 2.0)
        assertEquals(1083.0, centreY(rot0).toDouble(), 3.0)

        assertEquals(2928.0, centreX(rot90).toDouble(), 3.0)
        assertEquals(1500.0, centreY(rot90).toDouble(), 2.0)

        assertEquals(2000.0, centreX(rot180).toDouble(), 2.0)
        assertEquals(1917.0, centreY(rot180).toDouble(), 3.0)

        assertEquals(1072.0, centreX(rot270).toDouble(), 3.0)
        assertEquals(1500.0, centreY(rot270).toDouble(), 2.0)
    }

    @Test
    fun `front camera mirror flips the horizontal mapping`() {
        val viewW = 2992
        val front = Camera2Constants.LENS_FACING_FRONT
        val back = Camera2Constants.LENS_FACING_BACK
        val left = assertNotNull("front left tap",
            region(1000f, 672f, viewWidth = viewW, displayRotation = 0, lensFacing = front))
        val centre = assertNotNull("front centre tap",
            region(1496f, 672f, displayRotation = 0, lensFacing = front))
        val backLeft = assertNotNull("back left tap",
            region(1000f, 672f, viewWidth = viewW, displayRotation = 0, lensFacing = back))

        assertTrue("front-left tap must land right of centre", centreX(left) > centreX(centre))
        assertTrue("back-left tap must land left of centre", centreX(backLeft) < centreX(centre))
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
        assertNotNull("centre tap inside content", region(1496f, 672f))
    }

    // --- Capability gating ---------------------------------------------------------

    @Test
    fun `camera without af regions returns null`() {
        assertNull(region(1496f, 672f, maxAfRegions = 0))
    }

    // --- Stream crop (preview/sensor aspect ratio) ----------------------------------

    @Test
    fun `streamCrop centres a 16 by 9 preview inside a 4 by 3 sensor`() {
        // Pixel 8 Pro rear: active array 4080x3072, preview 1920x1080.
        val crop = FocusMapper.streamCrop(IntRect(0, 0, 4080, 3072), 1920, 1080)
        assertEquals(IntRect(0, 388, 4080, 2683), crop)
    }

    @Test
    fun `streamCrop preserves a non-zero active-array origin`() {
        val crop = FocusMapper.streamCrop(IntRect(100, 200, 4100, 3200), 1920, 1080)
        assertEquals(IntRect(100, 575, 4100, 2825), crop)
    }

    @Test
    fun `streamCrop is the crop region when buffer matches the sensor aspect`() {
        val active = IntRect(0, 0, 4000, 3000)
        assertEquals(active, FocusMapper.streamCrop(active, 1440, 1080))
    }

    @Test
    fun `streamCrop pillarboxes a square preview inside a 4 by 3 sensor`() {
        val crop = FocusMapper.streamCrop(IntRect(0, 0, 4000, 3000), 1080, 1080)
        assertEquals(IntRect(500, 0, 3500, 3000), crop)
    }

    @Test
    fun `streamCrop is centred inside a zoomed scaler crop`() {
        val scaler = IntRect(800, 600, 3200, 2400)
        val crop = FocusMapper.streamCrop(IntRect(0, 0, 4000, 3000), 1920, 1080, scaler)
        assertEquals(IntRect(800, 825, 3200, 2175), crop)
    }

    @Test
    fun `streamCrop ignores a scaler crop outside the active array`() {
        val active = IntRect(0, 0, 4000, 3000)
        // Entirely outside -> falls back to the full active array.
        assertEquals(
            FocusMapper.streamCrop(active, 1920, 1080),
            FocusMapper.streamCrop(active, 1920, 1080, IntRect(5000, 5000, 6000, 6000)),
        )
        // Partially outside -> intersected first, then centred inside the intersection.
        val partial = FocusMapper.streamCrop(active, 1920, 1080, IntRect(-500, 1000, 2000, 4000))
        assertEquals(0, partial.left)
        assertEquals(2000, partial.right)
    }

    @Test
    fun `streamCrop falls back for an empty scaler crop`() {
        val active = IntRect(0, 0, 4000, 3000)
        assertEquals(
            FocusMapper.streamCrop(active, 1920, 1080),
            FocusMapper.streamCrop(active, 1920, 1080, IntRect(1000, 1000, 1000, 1000)),
        )
    }

    @Test
    fun `degenerate streamCrop inputs do not crash`() {
        assertEquals(IntRect(0, 0, 0, 0), FocusMapper.streamCrop(IntRect(0, 0, 0, 0), 1920, 1080))
        assertEquals(IntRect(0, 0, 0, 0), FocusMapper.streamCrop(IntRect(0, 0, 10, 10), 0, 0))
    }

    // --- Exact sensor coordinates at the stream-crop edges --------------------------

    @Test
    fun `centre tap maps to the stream crop centre`() {
        val r = assertNotNull("centre", squareRegion(960f, 540f))
        assertEquals(2000, centreX(r))
        assertEquals(1500, centreY(r))
    }

    @Test
    fun `top centre tap maps to the top of the stream crop`() {
        val r = assertNotNull("top", squareRegion(960f, 0f))
        assertEquals(375, r.top) // 4:3 sensor, 16:9 preview -> 375 px letterbox trim
    }

    @Test
    fun `bottom centre tap maps to the bottom of the stream crop`() {
        val r = assertNotNull("bottom", squareRegion(960f, 1079f))
        assertEquals(2625, r.top + r.height)
    }

    @Test
    fun `left edge tap maps to the left of the stream crop`() {
        val r = assertNotNull("left", squareRegion(0f, 540f))
        assertEquals(0, r.left)
    }

    @Test
    fun `right edge tap maps to the right of the stream crop`() {
        val r = assertNotNull("right", squareRegion(1919f, 540f))
        assertEquals(4000, r.left + r.width)
    }

    @Test
    fun `stream-crop edges follow a non-zero active-array origin`() {
        val active = IntRect(100, 200, 4100, 3200)
        val top = assertNotNull("top", squareRegion(960f, 0f, activeArray = active))
        val bottom = assertNotNull("bottom", squareRegion(960f, 1079f, activeArray = active))
        val left = assertNotNull("left", squareRegion(0f, 540f, activeArray = active))
        val right = assertNotNull("right", squareRegion(1919f, 540f, activeArray = active))

        assertEquals(575, top.top)
        assertEquals(2825, bottom.top + bottom.height)
        assertEquals(100, left.left)
        assertEquals(4100, right.left + right.width)
    }

    @Test
    fun `a 4 by 3 preview is not cropped vertically`() {
        val r = assertNotNull(
            "4:3 preview",
            FocusMapper.toRegion(
                tapX = 960f, tapY = 0f,
                viewWidth = 1440, viewHeight = 1080,
                bufferWidth = 1440, bufferHeight = 1080,
                displayRotation = 0,
                lensFacing = Camera2Constants.LENS_FACING_BACK,
                activeArray = IntRect(0, 0, 4000, 3000),
                maxAfRegions = 1,
            ),
        )
        assertEquals(0, r.top)
    }

    @Test
    fun `a square preview pillarboxes in the stream crop`() {
        val r = assertNotNull(
            "1:1 preview",
            FocusMapper.toRegion(
                tapX = 0f, tapY = 540f,
                viewWidth = 1080, viewHeight = 1080,
                bufferWidth = 1080, bufferHeight = 1080,
                displayRotation = 0,
                lensFacing = Camera2Constants.LENS_FACING_BACK,
                activeArray = IntRect(0, 0, 4000, 3000),
                maxAfRegions = 1,
            ),
        )
        assertEquals(500, r.left)
    }

    @Test
    fun `preview resolution does not change the sensor mapping for the same aspect`() {
        val hd = assertNotNull("1920x1080", squareRegion(300f, 300f))
        val sd = assertNotNull(
            "1280x720",
            FocusMapper.toRegion(
                tapX = 300f * 1280f / 1920f, tapY = 300f * 720f / 1080f,
                viewWidth = 1280, viewHeight = 720,
                bufferWidth = 1280, bufferHeight = 720,
                displayRotation = 0,
                lensFacing = Camera2Constants.LENS_FACING_BACK,
                activeArray = IntRect(0, 0, 4000, 3000),
                maxAfRegions = 1,
            ),
        )
        assertEquals(centreX(hd), centreX(sd))
        assertEquals(centreY(hd), centreY(sd))
    }

    // --- Zoom ----------------------------------------------------------------------

    @Test
    fun `zoomed stream crop shifts the mapping`() {
        val full = assertNotNull("full", squareRegion(300f, 540f))
        val scaler = IntRect(800, 600, 3200, 2400)
        val zoomed = assertNotNull("zoomed", squareRegion(300f, 540f, scalerCrop = scaler))
        assertNotEquals(centreX(full), centreX(zoomed))
    }

    @Test
    fun `region stays inside the stream crop when zoomed`() {
        val scaler = IntRect(800, 600, 3200, 2400)
        val r = assertNotNull("zoomed", squareRegion(960f, 540f, scalerCrop = scaler))
        val crop = FocusMapper.streamCrop(IntRect(0, 0, 4000, 3000), 1920, 1080, scaler)
        assertTrue(r.left >= crop.left)
        assertTrue(r.top >= crop.top)
        assertTrue(r.left + r.width <= crop.right)
        assertTrue(r.top + r.height <= crop.bottom)
    }

    @Test
    fun `zoom crop outside the active array falls back to the active array`() {
        val r = assertNotNull(
            "invalid zoom",
            squareRegion(960f, 540f, scalerCrop = IntRect(5000, 5000, 6000, 6000)),
        )
        assertEquals(2000, centreX(r))
        assertEquals(1500, centreY(r))
    }

    // --- Degenerate inputs ---------------------------------------------------------

    @Test
    fun `degenerate geometry returns null instead of a bogus region`() {
        assertNull(region(100f, 100f, viewWidth = 0))
        assertNull(region(100f, 100f, bufferWidth = 0))
        assertNull(region(100f, 100f, activeArray = IntRect(0, 0, 0, 0)))
        assertNull(region(100f, 100f, activeArray = IntRect(10, 10, 10, 10)))
    }

    private fun assertNotNull(message: String, region: FocusRegion?): FocusRegion {
        assertTrue(message, region != null)
        return region!!
    }
}
