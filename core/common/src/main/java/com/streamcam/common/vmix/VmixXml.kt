package com.streamcam.common.vmix

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Which vMix bus the StreamCam input is currently on. */
enum class VmixBus { PREVIEW, PROGRAM, NEUTRAL }

data class VmixInput(val number: Int, val title: String)

data class VmixState(
    val preview: Int?,
    val active: Int?,
    val inputs: List<VmixInput>,
)

/**
 * Parser for vMix's Web Controller XML (`GET http://<host>:8088/api/`), kept free of Android types
 * so the mapping is unit-tested on the JVM.
 */
object VmixXml {

    fun parse(xml: String): VmixState? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val preview = doc.getElementsByTagName("preview").item(0)?.textContent?.trim()?.toIntOrNull()
        val active = doc.getElementsByTagName("active").item(0)?.textContent?.trim()?.toIntOrNull()
        val nodes = doc.getElementsByTagName("input")
        val inputs = buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val number = element.getAttribute("number").toIntOrNull() ?: continue
                add(VmixInput(number, element.getAttribute("title").orEmpty()))
            }
        }
        VmixState(preview = preview, active = active, inputs = inputs)
    }.getOrNull()

    /**
     * First input whose title contains any candidate (case-insensitive). vMix normally names an RTSP
     * input after its URL, so the app passes the full URL plus a few fallbacks.
     */
    fun matchInput(state: VmixState, candidates: List<String>): VmixInput? {
        for (needle in candidates.map { it.trim() }.filter { it.isNotEmpty() }) {
            state.inputs.firstOrNull { it.title.contains(needle, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    fun busFor(state: VmixState, input: VmixInput?): VmixBus = when {
        input == null -> VmixBus.NEUTRAL
        state.active == input.number -> VmixBus.PROGRAM
        state.preview == input.number -> VmixBus.PREVIEW
        else -> VmixBus.NEUTRAL
    }
}
