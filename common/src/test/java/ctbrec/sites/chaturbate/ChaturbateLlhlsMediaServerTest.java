package ctbrec.sites.chaturbate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsMediaServerTest {

    private ChaturbateLlhlsInMemoryWorkspace videoWorkspace;
    private ChaturbateLlhlsInMemoryWorkspace audioWorkspace;
    private ChaturbateLlhlsMediaServer server;

    @BeforeEach
    void setUp() throws IOException {
        videoWorkspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        audioWorkspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        server = new ChaturbateLlhlsMediaServer(videoWorkspace, audioWorkspace);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void start_providesValidPlaylistUrls() {
        String masterUrl = server.getMasterPlaylistUrl();
        String videoUrl = server.getVideoPlaylistUrl();
        String audioUrl = server.getAudioPlaylistUrl();

        assertTrue(masterUrl.startsWith("http://127.0.0.1:"), "Master URL should be on localhost");
        assertTrue(masterUrl.endsWith("/master.m3u8"), "Master URL should end with playlist path");
        assertTrue(videoUrl.startsWith("http://127.0.0.1:"), "Video URL should be on localhost");
        assertTrue(videoUrl.endsWith("/video/playlist.m3u8"), "Video URL should end with playlist path");
        assertTrue(audioUrl.startsWith("http://127.0.0.1:"), "Audio URL should be on localhost");
        assertTrue(audioUrl.endsWith("/audio/playlist.m3u8"), "Audio URL should end with playlist path");
    }

    @Test
    void getMasterPlaylist_returnsCombinedLocalTracks() throws Exception {
        HttpURLConnection connection = openConnection(server.getMasterPlaylistUrl());
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("application/vnd.apple.mpegurl", connection.getContentType());
            String body = new String(connection.getInputStream().readAllBytes());
            assertTrue(body.contains("#EXT-X-MEDIA:TYPE=AUDIO"), "Master playlist should advertise audio rendition");
            assertTrue(body.contains("audio/playlist.m3u8"), "Master playlist should reference the local audio playlist");
            assertTrue(body.contains("video/playlist.m3u8"), "Master playlist should reference the local video playlist");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getVideoPlaylist_returnsPlaylistContent() throws Exception {
        videoWorkspace.appendSegment(
                new ChaturbateLlhlsSegment(1, "https://cdn.example.com/seg_001.m4s", 2.0f, null, false, null),
                new byte[]{1, 2, 3});

        HttpURLConnection connection = openConnection(server.getVideoPlaylistUrl());
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("application/vnd.apple.mpegurl", connection.getContentType());
            String body = new String(connection.getInputStream().readAllBytes());
            assertTrue(body.contains("#EXTM3U"), "Response should be a valid HLS playlist");
            assertTrue(body.contains("seg_000001.m4s"), "Playlist should reference the segment");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getAudioPlaylist_returnsPlaylistContent() throws Exception {
        audioWorkspace.appendSegment(
                new ChaturbateLlhlsSegment(1, "https://cdn.example.com/seg_001.m4s", 2.0f, null, false, null),
                new byte[]{4, 5, 6});

        HttpURLConnection connection = openConnection(server.getAudioPlaylistUrl());
        try {
            assertEquals(200, connection.getResponseCode());
            String body = new String(connection.getInputStream().readAllBytes());
            assertTrue(body.contains("#EXTM3U"));
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getSegment_returnsSegmentBytes() throws Exception {
        videoWorkspace.appendSegment(
                new ChaturbateLlhlsSegment(1, "https://cdn.example.com/seg_001.m4s", 2.0f, null, false, null),
                new byte[]{10, 20, 30});

        String segmentUrl = server.getVideoPlaylistUrl().replace("playlist.m3u8", "seg_000001.m4s");
        HttpURLConnection connection = openConnection(segmentUrl);
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("video/mp4", connection.getContentType());
            byte[] body = connection.getInputStream().readAllBytes();
            assertEquals(3, body.length);
            assertEquals(10, body[0]);
            assertEquals(20, body[1]);
            assertEquals(30, body[2]);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getMissingSegment_returns404() throws Exception {
        String segmentUrl = server.getVideoPlaylistUrl().replace("playlist.m3u8", "nonexistent.m4s");
        HttpURLConnection connection = openConnection(segmentUrl);
        try {
            assertEquals(404, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getUnknownTrackPath_returns404() throws Exception {
        String baseUrl = server.getVideoPlaylistUrl().replace("/video/playlist.m3u8", "/unknown/test");
        HttpURLConnection connection = openConnection(baseUrl);
        try {
            assertEquals(404, connection.getResponseCode());
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void stop_stopsCleanly() throws Exception {
        // Verify server is running
        HttpURLConnection running = openConnection(server.getVideoPlaylistUrl());
        assertEquals(200, running.getResponseCode());
        running.disconnect();

        // Stop and verify no more connections accepted
        server.stop();
        server = null;
    }

    @Test
    void getInitSegment_returnsInitBytes() throws Exception {
        String mapUrl = "https://cdn.example.com/init.mp4";
        videoWorkspace.storeInitSegment(mapUrl, new byte[]{99, 88});

        String initUrl = server.getVideoPlaylistUrl().replace("playlist.m3u8", "init_000001.mp4");
        HttpURLConnection connection = openConnection(initUrl);
        try {
            assertEquals(200, connection.getResponseCode());
            byte[] body = connection.getInputStream().readAllBytes();
            assertEquals(2, body.length);
            assertEquals(99, body[0]);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void playlistResponse_includesNoCacheHeaders() throws Exception {
        videoWorkspace.appendSegment(
                new ChaturbateLlhlsSegment(1, "https://cdn.example.com/seg.m4s", 2.0f, null, false, null),
                new byte[]{1});

        HttpURLConnection connection = openConnection(server.getVideoPlaylistUrl());
        try {
            assertEquals(200, connection.getResponseCode());
            String cacheControl = connection.getHeaderField("Cache-Control");
            assertNotNull(cacheControl);
            assertTrue(cacheControl.contains("no-cache"), "Playlist should have no-cache");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void getAudioSegment_returnsAudioMp4ContentType() throws Exception {
        audioWorkspace.appendSegment(
                new ChaturbateLlhlsSegment(1, "https://cdn.example.com/seg_001.m4s", 2.0f, null, false, null),
                new byte[]{7, 8, 9});

        String segmentUrl = server.getAudioPlaylistUrl().replace("playlist.m3u8", "seg_000001.m4s");
        HttpURLConnection connection = openConnection(segmentUrl);
        try {
            assertEquals(200, connection.getResponseCode());
            assertEquals("audio/mp4", connection.getContentType(),
                    "Audio segments should be served as audio/mp4");
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        return connection;
    }
}
