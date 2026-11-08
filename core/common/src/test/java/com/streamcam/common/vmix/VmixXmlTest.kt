package com.streamcam.common.vmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmixXmlTest {

    private val xml = """
        <vmix>
          <version>28.0.0.45</version>
          <inputs>
            <input key="abc" number="1" type="Colour" title="Background" state="Running" />
            <input key="def" number="2" type="RTSP" title="rtsp://192.168.0.101:8554/stream" state="Running" />
            <input key="ghi" number="3" type="VideoDelay" title="StreamCam Delay" state="Running" />
          </inputs>
          <preview>2</preview>
          <active>3</active>
        </vmix>
    """.trimIndent()

    /** vMix 28 names an RTSP input "Stream" and the user has renamed it to a friendly title. */
    private val renamed = """
        <vmix>
          <inputs>
            <input key="a" number="1" type="Capture" title="Wide Cam" state="Running" />
            <input key="b" number="2" type="Stream" title="Roving Cam" state="Running" />
            <input key="c" number="3" type="Image" title="Service Graphic" state="Running" />
          </inputs>
          <preview>2</preview>
          <active>1</active>
        </vmix>
    """.trimIndent()

    @Test
    fun `parses inputs and bus numbers`() {
        val state = VmixXml.parse(xml)!!
        assertEquals(2, state.preview)
        assertEquals(3, state.active)
        assertEquals(listOf(1, 2, 3), state.inputs.map { it.number })
        assertEquals("Background", state.inputs[0].title)
        assertEquals("RTSP", state.inputs[1].type)
    }

    @Test
    fun `matches the rtsp url and reports preview`() {
        val state = VmixXml.parse(xml)!!
        val input = VmixXml.matchInput(
            state,
            listOf("rtsp://192.168.0.101:8554/stream", "192.168.0.101:8554", "StreamCam"),
        )!!
        assertEquals(2, input.number)
        assertEquals(VmixBus.PREVIEW, VmixXml.busFor(state, input))
    }

    @Test
    fun `falls back to a later candidate and reports program`() {
        val state = VmixXml.parse(xml)!!
        val input = VmixXml.matchInput(state, listOf("nope", "streamcam delay"))!!
        assertEquals(3, input.number)
        assertEquals(VmixBus.PROGRAM, VmixXml.busFor(state, input))
    }

    @Test
    fun `input on neither bus is neutral`() {
        val state = VmixXml.parse(xml)!!
        val input = state.inputs.first { it.number == 1 }
        assertEquals(VmixBus.NEUTRAL, VmixXml.busFor(state, input))
    }

    @Test
    fun `renamed input falls back to the only stream input`() {
        // Friendly titles match nothing, but the single network stream can only be this app.
        val state = VmixXml.parse(renamed)!!
        val input = VmixXml.matchInput(
            state,
            listOf("rtsp://192.168.1.9:8088/stream", "192.168.1.9:8088/stream", ":8088/", "streamcam"),
        )!!
        assertEquals(2, input.number)
        assertEquals("Roving Cam", input.title)
        assertEquals(VmixBus.PREVIEW, VmixXml.busFor(state, input))
    }

    @Test
    fun `title match wins over the stream fallback`() {
        // "StreamCam Delay" is a VideoDelay, not the stream; the explicit title must still win.
        val state = VmixXml.parse(xml)!!
        val input = VmixXml.matchInput(state, listOf("streamcam"))!!
        assertEquals(3, input.number)
    }

    @Test
    fun `rtsp type is treated as a network stream`() {
        val state = VmixXml.parse(xml)!!
        val input = VmixXml.matchInput(state, listOf("nothing-matches"))!!
        assertEquals(2, input.number)
    }

    @Test
    fun `ambiguous stream inputs stay unmatched`() {
        val state = VmixXml.parse(
            """
            <vmix>
              <inputs>
                <input number="1" type="Stream" title="Cam A" />
                <input number="2" type="Stream" title="Cam B" />
              </inputs>
              <preview>1</preview>
              <active>2</active>
            </vmix>
            """.trimIndent(),
        )!!
        assertNull(VmixXml.matchInput(state, listOf("rtsp://nope")))
    }

    @Test
    fun `missing input and malformed xml are safe`() {
        assertEquals(VmixBus.NEUTRAL, VmixXml.busFor(VmixXml.parse(xml)!!, null))
        assertNull(VmixXml.parse("<not-vmix"))
    }

    @Test
    fun `no stream input and no title match is safe`() {
        val state = VmixXml.parse(
            """
            <vmix>
              <inputs>
                <input number="1" type="Colour" title="Background" />
                <input number="2" type="Image" title="Logo" />
              </inputs>
              <preview>1</preview>
              <active>1</active>
            </vmix>
            """.trimIndent(),
        )!!
        assertNull(VmixXml.matchInput(state, listOf("absent")))
    }

    @Test
    fun `input missing a number is ignored`() {
        val state = VmixXml.parse(
            "<vmix><inputs><input title=\"No number\" /></inputs><preview>1</preview><active>2</active></vmix>",
        )!!
        assertEquals(emptyList<Int>(), state.inputs.map { it.number })
    }
}
