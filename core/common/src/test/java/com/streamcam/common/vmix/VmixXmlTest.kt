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

    @Test
    fun `parses inputs and bus numbers`() {
        val state = VmixXml.parse(xml)!!
        assertEquals(2, state.preview)
        assertEquals(3, state.active)
        assertEquals(listOf(1, 2, 3), state.inputs.map { it.number })
        assertEquals("Background", state.inputs[0].title)
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
    fun `missing input and malformed xml are safe`() {
        val state = VmixXml.parse(xml)!!
        assertNull(VmixXml.matchInput(state, listOf("absent")))
        assertEquals(VmixBus.NEUTRAL, VmixXml.busFor(state, null))
        assertNull(VmixXml.parse("<not-vmix"))
    }

    @Test
    fun `input missing a number is ignored`() {
        val state = VmixXml.parse(
            "<vmix><inputs><input title=\"No number\" /></inputs><preview>1</preview><active>2</active></vmix>",
        )!!
        assertEquals(emptyList<Int>(), state.inputs.map { it.number })
    }
}
