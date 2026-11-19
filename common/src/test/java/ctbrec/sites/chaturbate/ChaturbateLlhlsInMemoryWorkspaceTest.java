package ctbrec.sites.chaturbate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsInMemoryWorkspaceTest {

    @TempDir
    Path tempDir;

    private ChaturbateLlhlsSegment segment(int seq, String url, float duration) {
        return new ChaturbateLlhlsSegment(seq, url, duration, null, false, null);
    }

    private ChaturbateLlhlsSegment segmentWithMap(int seq, String url, float duration, String mapUrl) {
        return new ChaturbateLlhlsSegment(seq, url, duration, null, false, mapUrl);
    }

    @Test
    void appendSegment_storesDataRetrievableViaServeSegment() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);

        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{10, 20, 30});

        byte[] data = workspace.serveSegment("seg_000001.m4s");
        assertNotNull(data);
        assertArrayEquals(new byte[]{10, 20, 30}, data);
    }

    @Test
    void storeInitSegment_storesDataRetrievableViaServeSegment() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        String mapUrl = "https://cdn.example.com/init.mp4";

        workspace.storeInitSegment(mapUrl, new byte[]{99});

        byte[] data = workspace.serveSegment("init_000001.mp4");
        assertNotNull(data);
        assertArrayEquals(new byte[]{99}, data);
    }

    @Test
    void storeInitSegment_ignoresDuplicateForSameUrl() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        String mapUrl = "https://cdn.example.com/init.mp4";

        workspace.storeInitSegment(mapUrl, new byte[]{1});
        workspace.storeInitSegment(mapUrl, new byte[]{2});

        byte[] data = workspace.serveSegment("init_000001.mp4");
        assertArrayEquals(new byte[]{1}, data, "Second store for same URL should be ignored");
        assertNull(workspace.serveSegment("init_000002.mp4"), "No second init should exist");
    }

    @Test
    void hasInitSegment_returnsTrueForNullMapUrl() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        assertTrue(workspace.hasInitSegment(null));
    }

    @Test
    void hasInitSegment_returnsFalseForUnknownUrl() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        assertFalse(workspace.hasInitSegment("https://cdn.example.com/unknown.mp4"));
    }

    @Test
    void hasInitSegment_returnsTrueAfterStoring() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        String url = "https://cdn.example.com/init.mp4";
        workspace.storeInitSegment(url, new byte[]{1});
        assertTrue(workspace.hasInitSegment(url));
    }

    @Test
    void evictOldSegments_removesOldestBeyondWindowSize() {
        int windowSize = 3;
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2, windowSize);

        for (int i = 1; i <= 5; i++) {
            workspace.appendSegment(
                    segment(i, "https://cdn.example.com/seg_" + i + ".m4s", 2.0f),
                    new byte[]{(byte) i});
        }

        // Segments 1 and 2 should be evicted (5 added, window=3, so 2 evicted)
        assertNull(workspace.serveSegment("seg_000001.m4s"), "Segment 1 should be evicted");
        assertNull(workspace.serveSegment("seg_000002.m4s"), "Segment 2 should be evicted");
        // Segments 3, 4, 5 should remain
        assertNotNull(workspace.serveSegment("seg_000003.m4s"), "Segment 3 should remain");
        assertNotNull(workspace.serveSegment("seg_000004.m4s"), "Segment 4 should remain");
        assertNotNull(workspace.serveSegment("seg_000005.m4s"), "Segment 5 should remain");
    }

    @Test
    void generatePlaylist_producesValidHlsPlaylist() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{1});
        workspace.appendSegment(segment(2, "https://cdn.example.com/seg_002.m4s", 2.0f), new byte[]{2});

        byte[] playlist = workspace.generatePlaylist();
        String content = new String(playlist);

        assertTrue(content.contains("#EXTM3U"), "Playlist must start with #EXTM3U");
        assertTrue(content.contains("#EXT-X-TARGETDURATION:2"), "Target duration should be 2");
        assertTrue(content.contains("seg_000001.m4s"), "Should reference first segment");
        assertTrue(content.contains("seg_000002.m4s"), "Should reference second segment");
        assertFalse(content.contains("#EXT-X-ENDLIST"), "Ongoing playlist should not have ENDLIST");
    }

    @Test
    void generatePlaylist_includesEndlistAfterFinalize() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{1});
        workspace.markComplete();

        byte[] playlist = workspace.generatePlaylist();
        String content = new String(playlist);

        assertTrue(content.contains("#EXT-X-ENDLIST"), "Finalized playlist should have ENDLIST");
        assertTrue(content.contains("#EXT-X-PLAYLIST-TYPE:VOD"), "Finalized playlist should be VOD");
    }

    @Test
    void generatePlaylist_mediaSequenceAdvancesWithEviction() throws IOException {
        int windowSize = 2;
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2, windowSize);

        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_1.m4s", 2.0f), new byte[]{1});
        workspace.appendSegment(segment(2, "https://cdn.example.com/seg_2.m4s", 2.0f), new byte[]{2});
        workspace.appendSegment(segment(3, "https://cdn.example.com/seg_3.m4s", 2.0f), new byte[]{3});

        byte[] playlist = workspace.generatePlaylist();
        String content = new String(playlist);

        // 1 segment evicted, media sequence should be 1
        assertTrue(content.contains("#EXT-X-MEDIA-SEQUENCE:1"),
                "Media sequence should advance to 1 after evicting 1 segment");
    }

    @Test
    void dumpToDisk_writesSegmentsAndPlaylist() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        String mapUrl = "https://cdn.example.com/init.mp4";
        workspace.storeInitSegment(mapUrl, new byte[]{99});
        workspace.appendSegment(
                segmentWithMap(1, "https://cdn.example.com/seg_001.m4s", 2.0f, mapUrl),
                new byte[]{10, 20});
        workspace.markComplete();

        workspace.dumpToDisk(tempDir);

        Path trackDir = tempDir.resolve("video");
        assertTrue(Files.exists(trackDir.resolve("init_000001.mp4")), "Init segment should be dumped");
        assertTrue(Files.exists(trackDir.resolve("seg_000001.m4s")), "Segment should be dumped");
        assertTrue(Files.exists(trackDir.resolve("playlist.m3u8")), "Playlist should be dumped");
        assertArrayEquals(new byte[]{99}, Files.readAllBytes(trackDir.resolve("init_000001.mp4")));
        assertArrayEquals(new byte[]{10, 20}, Files.readAllBytes(trackDir.resolve("seg_000001.m4s")));
    }

    @Test
    void isEmpty_returnsTrueInitially() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        assertTrue(workspace.isEmpty());
    }

    @Test
    void isEmpty_returnsFalseAfterAppend() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{1});
        assertFalse(workspace.isEmpty());
    }

    @Test
    void clear_removesAllData() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.storeInitSegment("https://cdn.example.com/init.mp4", new byte[]{1});
        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{2});

        workspace.clear();

        assertTrue(workspace.isEmpty());
        assertNull(workspace.serveSegment("init_000001.mp4"));
        assertNull(workspace.serveSegment("seg_000001.m4s"));
    }

    @Test
    void serveSegment_returnsNullForEvictedSegment() {
        int windowSize = 2;
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2, windowSize);

        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_1.m4s", 2.0f), new byte[]{1});
        workspace.appendSegment(segment(2, "https://cdn.example.com/seg_2.m4s", 2.0f), new byte[]{2});
        workspace.appendSegment(segment(3, "https://cdn.example.com/seg_3.m4s", 2.0f), new byte[]{3});

        assertNull(workspace.serveSegment("seg_000001.m4s"), "Evicted segment should return null");
        assertNotNull(workspace.serveSegment("seg_000002.m4s"), "Recent segment should be available");
    }

    @Test
    void updateTargetDuration_onlyIncreases() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.updateTargetDuration(4);
        workspace.appendSegment(segment(1, "https://cdn.example.com/seg_001.m4s", 2.0f), new byte[]{1});

        byte[] playlist = workspace.generatePlaylist();
        String content = new String(playlist);
        assertTrue(content.contains("#EXT-X-TARGETDURATION:4"), "Target duration should increase to 4");

        // Try to decrease — should not change
        workspace.updateTargetDuration(1);
        playlist = workspace.generatePlaylist();
        content = new String(playlist);
        assertTrue(content.contains("#EXT-X-TARGETDURATION:4"), "Target duration should not decrease");
    }

    @Test
    void appendSegment_includesMapInfoWhenInitSegmentStored() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        String mapUrl = "https://cdn.example.com/init.mp4";
        workspace.storeInitSegment(mapUrl, new byte[]{99});
        workspace.appendSegment(
                segmentWithMap(1, "https://cdn.example.com/seg_001.m4s", 2.0f, mapUrl),
                new byte[]{1});

        byte[] playlist = workspace.generatePlaylist();
        String content = new String(playlist);

        assertTrue(content.contains("init_000001.mp4"), "Playlist should reference init segment");
    }

    @Test
    void generatePlaylist_keepsActiveMapInfoAfterOriginalTaggedSegmentIsEvicted() throws IOException {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2, 2);
        String mapUrl = "https://cdn.example.com/init.mp4";
        workspace.storeInitSegment(mapUrl, new byte[]{99});
        workspace.appendSegment(segmentWithMap(1, "https://cdn.example.com/seg_001.m4s", 2.0f, mapUrl), new byte[]{1});
        workspace.appendSegment(segmentWithMap(2, "https://cdn.example.com/seg_002.m4s", 2.0f, mapUrl), new byte[]{2});
        workspace.appendSegment(segmentWithMap(3, "https://cdn.example.com/seg_003.m4s", 2.0f, mapUrl), new byte[]{3});

        String content = new String(workspace.generatePlaylist());

        assertTrue(content.contains("init_000001.mp4"), "Playlist window should still expose the active init segment");
        assertTrue(content.contains("seg_000002.m4s"), "Playlist should keep recent segments after eviction");
        assertTrue(content.contains("seg_000003.m4s"), "Playlist should keep recent segments after eviction");
    }

    @Test
    void evictOldSegments_discardsInitSegmentsNoLongerReferencedByWindow() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2, 2);
        String mapA = "https://cdn.example.com/init-a.mp4";
        String mapB = "https://cdn.example.com/init-b.mp4";
        workspace.storeInitSegment(mapA, new byte[]{1});
        workspace.appendSegment(segmentWithMap(1, "https://cdn.example.com/seg_001.m4s", 2.0f, mapA), new byte[]{10});
        workspace.storeInitSegment(mapB, new byte[]{2});
        workspace.appendSegment(segmentWithMap(2, "https://cdn.example.com/seg_002.m4s", 2.0f, mapB), new byte[]{20});
        workspace.appendSegment(segmentWithMap(3, "https://cdn.example.com/seg_003.m4s", 2.0f, mapB), new byte[]{30});

        assertNull(workspace.serveSegment("init_000001.mp4"), "Unused init segment should be evicted with the old window");
        assertNotNull(workspace.serveSegment("init_000002.mp4"), "Current init segment should remain available");
    }

    @Test
    void appendSegment_detectsFileExtensionFromUrl() {
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        workspace.appendSegment(
                segment(1, "https://cdn.example.com/seg_001.ts", 2.0f),
                new byte[]{1});

        assertNotNull(workspace.serveSegment("seg_000001.ts"),
                "Extension should be detected from URL (.ts)");
    }
}
