package cn.weibo.filedownloader

import org.w3c.dom.Element
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal object RedditDashParser {
    fun variants(xml: String, manifestUrl: String): List<MediaVariant> = runCatching {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val adaptations = document.getElementsByTagNameNS("*", "AdaptationSet")
        val videos = mutableListOf<MediaVariant>()
        val audios = mutableListOf<Pair<String, Long>>()
        var declaresAudio = false
        for (i in 0 until adaptations.length) {
            val adaptation = adaptations.item(i) as? Element ?: continue
            val representations = adaptation.getElementsByTagNameNS("*", "Representation")
            val adaptationType = adaptation.getAttribute("contentType").ifBlank { adaptation.getAttribute("mimeType") }
            if (adaptationType.startsWith("audio")) declaresAudio = true
            for (j in 0 until representations.length) {
                val representation = representations.item(j) as? Element ?: continue
                val mime = representation.getAttribute("mimeType").ifBlank { adaptation.getAttribute("mimeType") }
                val type = representation.getAttribute("contentType").ifBlank { adaptationType }
                val codec = representation.getAttribute("codecs").ifBlank { adaptation.getAttribute("codecs") }
                val isAudio = type.startsWith("audio") || mime.startsWith("audio")
                if (isAudio) declaresAudio = true
                // The worker muxes progressive MP4 tracks. Segmented streams need a different downloader.
                if (representation.getElementsByTagNameNS("*", "SegmentTemplate").length > 0 ||
                    adaptation.getElementsByTagNameNS("*", "SegmentTemplate").length > 0 ||
                    adaptation.getElementsByTagNameNS("*", "SegmentList").length > 0) continue
                val url = baseUrl(representation, manifestUrl)
                if (url.isBlank()) continue
                val bandwidth = representation.getAttribute("bandwidth").toLongOrNull() ?: 0
                if (isAudio && mime == "audio/mp4" && (codec.isBlank() || codec.startsWith("mp4a"))) audios += url to bandwidth
                else if ((type.startsWith("video") || mime.startsWith("video")) && mime == "video/mp4" &&
                    (codec.isBlank() || codec.startsWith("avc1") || codec.startsWith("avc3"))) {
                    val height = representation.getAttribute("height").toIntOrNull() ?: 0
                    videos += MediaVariant(if (height > 0) "${height}P" else "source", url,
                        representation.getAttribute("width").toIntOrNull() ?: 0, height, bandwidth)
                }
            }
        }
        val audio = audios.maxByOrNull { it.second }?.first.orEmpty()
        if (declaresAudio && audio.isBlank()) return@runCatching emptyList()
        videos.sortedWith(compareByDescending<MediaVariant> { it.pixelArea }.thenByDescending { it.bitrate })
            .distinctBy { it.key }.map { it.copy(audioUrl = audio) }
    }.getOrDefault(emptyList())

    private fun baseUrl(representation: Element, manifestUrl: String): String {
        val chain = mutableListOf<Element>()
        var node: org.w3c.dom.Node? = representation
        while (node is Element) { chain.add(0, node); node = node.parentNode }
        var base = URI(manifestUrl)
        var found = false
        chain.forEach { element ->
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index) as? Element ?: continue
                if (child.localName == "BaseURL" || child.tagName == "BaseURL") {
                    base = base.resolve(child.textContent.trim())
                    found = true
                    break
                }
            }
        }
        return if (found) RedditMediaUrls.usable(base.toString()) else ""
    }
}
