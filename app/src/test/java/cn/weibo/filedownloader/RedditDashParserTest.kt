package cn.weibo.filedownloader

import org.junit.Assert.*
import org.junit.Test

class RedditDashParserTest {
    @Test fun highestVideoPairsWithAudioAndResolvesRelativeUrls() {
        val streams = RedditDashParser.variants("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period>
            <AdaptationSet contentType="video" mimeType="video/mp4" codecs="avc1.4d401f">
              <Representation width="640" height="360" bandwidth="200000"><BaseURL>DASH_360.mp4</BaseURL></Representation>
              <Representation width="1920" height="1080" bandwidth="2000000"><BaseURL>DASH_1080.mp4</BaseURL></Representation>
            </AdaptationSet>
            <AdaptationSet contentType="audio" mimeType="audio/mp4" codecs="mp4a.40.2">
              <Representation bandwidth="64000"><BaseURL>DASH_AUDIO_64.mp4</BaseURL></Representation>
              <Representation bandwidth="128000"><BaseURL>DASH_AUDIO_128.mp4</BaseURL></Representation>
            </AdaptationSet></Period></MPD>""", "https://v.redd.it/abc/DASHPlaylist.mpd")
        assertEquals(listOf("1080P", "360P"), streams.map { it.key })
        assertEquals("https://v.redd.it/abc/DASH_1080.mp4", streams.first().url)
        assertTrue(streams.all { it.audioUrl == "https://v.redd.it/abc/DASH_AUDIO_128.mp4" })
    }
    @Test fun silentVideoDoesNotInventAnAudioUrl() {
        val streams = RedditDashParser.variants("""<MPD><Period><AdaptationSet mimeType="video/mp4"><Representation width="640" height="360"><BaseURL>DASH_360.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>""", "https://v.redd.it/abc/DASHPlaylist.mpd")
        assertEquals("", streams.single().audioUrl)
    }
    @Test fun unsupportedAudioDoesNotProduceASilentDownload() {
        assertTrue(RedditDashParser.variants("""<MPD><Period><AdaptationSet mimeType="video/mp4"><Representation><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet><AdaptationSet mimeType="audio/webm"><Representation><BaseURL>audio.webm</BaseURL></Representation></AdaptationSet></Period></MPD>""", "https://v.redd.it/abc/DASHPlaylist.mpd").isEmpty())
    }
    @Test fun segmentedTracksAreRejectedByProgressiveDownloader() {
        assertTrue(RedditDashParser.variants("""<MPD><Period><AdaptationSet mimeType="video/mp4"><SegmentTemplate media="segment-${'$'}Number${'$'}.m4s"/><Representation><BaseURL>video.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>""", "https://v.redd.it/abc/DASHPlaylist.mpd").isEmpty())
    }
    @Test fun xmlEntityInputIsNotRead() {
        assertTrue(RedditDashParser.variants("""<!DOCTYPE MPD [<!ENTITY x SYSTEM "file:///sensitive">]><MPD>&x;</MPD>""", "https://v.redd.it/abc/DASHPlaylist.mpd").isEmpty())
    }
}
