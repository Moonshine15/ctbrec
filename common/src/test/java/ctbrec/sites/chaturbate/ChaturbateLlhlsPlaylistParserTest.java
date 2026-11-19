package ctbrec.sites.chaturbate;

import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsPlaylistParserTest {

    @Test
    void parse_resolvesMapSegmentsAndProgramDateTime() throws IOException, ParseException, PlaylistException {
        String playlistUrl = "https://edge1-lax.live.mmcdn.com/hls/model/video/chunklist.m3u8?session=abc";
        String body = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:2
                #EXT-X-MEDIA-SEQUENCE:120
                #EXT-X-MAP:URI="init.mp4"
                #EXT-X-PROGRAM-DATE-TIME:2026-03-14T15:00:00Z
                #EXTINF:2.000000,
                seg120.m4s
                #EXT-X-PROGRAM-DATE-TIME:2026-03-14T15:00:02Z
                #EXTINF:2.000000,
                /hls/model/video/seg121.m4s?session=abc
                """;

        ChaturbateLlhlsMediaPlaylist playlist = ChaturbateLlhlsPlaylistParser.parse(playlistUrl, body);

        assertEquals(120, playlist.mediaSequence());
        assertEquals(2, playlist.targetDuration());
        assertEquals(2, playlist.segments().size());
        assertEquals(120, playlist.segments().get(0).sequence());
        assertEquals("https://edge1-lax.live.mmcdn.com/hls/model/video/init.mp4", playlist.segments().get(0).mapUrl());
        assertEquals("https://edge1-lax.live.mmcdn.com/hls/model/video/seg120.m4s", playlist.segments().get(0).url());
        assertEquals("2026-03-14T15:00:00Z", playlist.segments().get(0).programDateTime());
        assertEquals("https://edge1-lax.live.mmcdn.com/hls/model/video/seg121.m4s?session=abc", playlist.segments().get(1).url());
    }

    @Test
    void parse_tracksDiscontinuityAndMapRotation() throws IOException, ParseException, PlaylistException {
        String playlistUrl = "https://edge1-lax.live.mmcdn.com/hls/model/audio/chunklist.m3u8?session=abc";
        String body = """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-TARGETDURATION:2
                #EXT-X-MEDIA-SEQUENCE:10
                #EXT-X-MAP:URI="init-a.mp4"
                #EXTINF:2.000000,
                seg10.m4s
                #EXT-X-DISCONTINUITY
                #EXT-X-MAP:URI="init-b.mp4"
                #EXTINF:2.000000,
                seg11.m4s
                """;

        ChaturbateLlhlsMediaPlaylist playlist = ChaturbateLlhlsPlaylistParser.parse(playlistUrl, body);

        assertEquals(2, playlist.segments().size());
        assertEquals("https://edge1-lax.live.mmcdn.com/hls/model/audio/init-a.mp4", playlist.segments().get(0).mapUrl());
        assertTrue(playlist.segments().get(1).discontinuity());
        assertEquals("https://edge1-lax.live.mmcdn.com/hls/model/audio/init-b.mp4", playlist.segments().get(1).mapUrl());
    }
}
