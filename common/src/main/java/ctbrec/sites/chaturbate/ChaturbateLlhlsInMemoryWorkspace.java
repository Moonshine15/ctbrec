package ctbrec.sites.chaturbate;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.Format;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistWriter;
import com.iheartradio.m3u8.data.MapInfo;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistType;
import com.iheartradio.m3u8.data.TrackData;
import com.iheartradio.m3u8.data.TrackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * In-memory workspace for a single LL-HLS track (video or audio).
 * Stores segment data in memory and generates playlists on-the-fly,
 * eliminating all transient disk I/O. A sliding window limits memory
 * usage by evicting segments that FFmpeg has already consumed.
 *
 * Segments are served to FFmpeg via a local HTTP server
 * ({@link ChaturbateLlhlsMediaServer}).
 */
final class ChaturbateLlhlsInMemoryWorkspace {

    private static final Logger LOG = LoggerFactory.getLogger(ChaturbateLlhlsInMemoryWorkspace.class);
    static final int DEFAULT_WINDOW_SIZE = 30;

    private final String name;
    private final int windowSize;

    // Segment data keyed by local filename: "seg_000001.m4s" -> byte[]
    private final ConcurrentHashMap<String, byte[]> segmentData = new ConcurrentHashMap<>();
    // Init segment data keyed by local filename: "init_000001.mp4" -> byte[]
    private final ConcurrentHashMap<String, byte[]> initSegmentData = new ConcurrentHashMap<>();
    // Remote map URL -> local init filename
    private final Map<String, String> initSegmentIndex = new LinkedHashMap<>();
    // Ordered track entries for playlist generation
    private final List<TrackData> tracks = new ArrayList<>();
    // Local filenames in order of insertion for eviction
    private final List<String> segmentOrder = new ArrayList<>();
    // Effective local init filename for each track entry.
    private final List<String> trackInitSegments = new ArrayList<>();

    private int targetDuration;
    private int segmentCounter = 0;
    private int initCounter = 0;
    private String currentMapUrl;
    private int mediaSequenceBase = 0;
    private boolean ongoing = true;

    ChaturbateLlhlsInMemoryWorkspace(String name, int targetDuration) {
        this(name, targetDuration, DEFAULT_WINDOW_SIZE);
    }

    ChaturbateLlhlsInMemoryWorkspace(String name, int targetDuration, int windowSize) {
        this.name = name;
        this.targetDuration = Math.max(targetDuration, 1);
        this.windowSize = windowSize;
    }

    String getName() {
        return name;
    }

    void updateTargetDuration(int targetDuration) {
        synchronized (tracks) {
            this.targetDuration = Math.max(this.targetDuration, targetDuration);
        }
    }

    boolean hasInitSegment(String remoteMapUrl) {
        synchronized (tracks) {
            return remoteMapUrl == null || initSegmentIndex.containsKey(remoteMapUrl);
        }
    }

    void storeInitSegment(String remoteMapUrl, byte[] data) {
        synchronized (tracks) {
            if (remoteMapUrl == null || initSegmentIndex.containsKey(remoteMapUrl)) {
                return;
            }
            String localName = String.format("init_%06d%s", ++initCounter, detectExtension(remoteMapUrl, ".mp4"));
            initSegmentData.put(localName, data);
            initSegmentIndex.put(remoteMapUrl, localName);
        }
    }

    void appendSegment(ChaturbateLlhlsSegment segment, byte[] data) {
        synchronized (tracks) {
            String localName = String.format("seg_%06d%s", ++segmentCounter, detectExtension(segment.url(), ".m4s"));
            segmentData.put(localName, data);
            segmentOrder.add(localName);
            String localInit = segment.mapUrl() != null ? initSegmentIndex.get(segment.mapUrl()) : null;

            TrackData.Builder builder = new TrackData.Builder()
                    .withUri(localName)
                    .withTrackInfo(new TrackInfo(segment.duration(), localName));
            if (segment.discontinuity()) {
                builder.withDiscontinuity(true);
            }
            if (segment.programDateTime() != null && !segment.programDateTime().isBlank()) {
                builder.withProgramDateTime(segment.programDateTime());
            }
            if (segment.mapUrl() != null && !segment.mapUrl().equals(currentMapUrl)) {
                if (localInit != null) {
                    builder.withMapInfo(new MapInfo.Builder().withUri(localInit).build());
                }
                currentMapUrl = segment.mapUrl();
            }
            tracks.add(builder.build());
            trackInitSegments.add(localInit);

            evictOldSegments();
        }
    }

    /**
     * Mark the workspace as complete (no more segments will be added).
     * The playlist generated after this will include EXT-X-ENDLIST.
     */
    void markComplete() {
        synchronized (tracks) {
            ongoing = false;
        }
    }

    /**
     * Serve segment or init segment bytes by local filename.
     * Returns null if the segment is not found (evicted or never stored).
     *
     * Synchronized on tracks to coordinate with evictOldSegments() so that
     * an HTTP-serve read does not race with an eviction removal.
     */
    byte[] serveSegment(String localName) {
        synchronized (tracks) {
            byte[] data = segmentData.get(localName);
            if (data != null) {
                return data;
            }
            return initSegmentData.get(localName);
        }
    }

    /**
     * Generate the current playlist as bytes. Called on each HTTP request
     * from FFmpeg, so the playlist always reflects the latest state.
     */
    byte[] generatePlaylist() throws IOException {
        synchronized (tracks) {
            return generatePlaylistLocked();
        }
    }

    /**
     * Generate the current playlist as bytes. Caller must hold the {@code tracks} monitor.
     */
    private byte[] generatePlaylistLocked() throws IOException {
        try {
            List<TrackData> windowedTracks = buildPlaylistTracksLocked();
            int mediaSequence = mediaSequenceBase;
            int snapshotTargetDuration = targetDuration;
            boolean isOngoing = ongoing;

            // EVENT type is intentional: FFmpeg's HLS demuxer handles EVENT-to-VOD
            // transitions cleanly on finalization, and FFmpeg reads near the live
            // edge so the 30-segment sliding window provides ample buffer.
            MediaPlaylist playlist = new MediaPlaylist.Builder()
                    .withPlaylistType(isOngoing ? PlaylistType.EVENT : PlaylistType.VOD)
                    .withIsOngoing(isOngoing)
                    .withMediaSequenceNumber(mediaSequence)
                    .withTargetDuration(snapshotTargetDuration)
                    .withTracks(windowedTracks)
                    .build();

            Playlist wrapped = new Playlist.Builder()
                    .withCompatibilityVersion(7)
                    .withExtended(true)
                    .withMediaPlaylist(playlist)
                    .build();

            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            PlaylistWriter writer = new PlaylistWriter.Builder()
                    .withFormat(Format.EXT_M3U)
                    .withEncoding(Encoding.UTF_8)
                    .withOutputStream(out)
                    .build();
            writer.write(wrapped);
            return out.toByteArray();
        } catch (PlaylistException | ParseException e) {
            throw new IOException("Couldn't generate in-memory LL-HLS playlist for " + name, e);
        }
    }

    /**
     * Dump all in-memory data to disk for forensic analysis.
     * Used when preserveWorkspace is true (FFmpeg crash, capture error).
     */
    void dumpToDisk(Path workspaceDir) throws IOException {
        Path trackDir = workspaceDir.resolve(name);
        Files.createDirectories(trackDir);

        // Snapshot data and playlist under a single lock so the dumped playlist
        // is guaranteed to match the dumped segments.
        Map<String, byte[]> initSnapshot;
        Map<String, byte[]> segmentSnapshot;
        byte[] playlistBytes;
        synchronized (tracks) {
            initSnapshot = new LinkedHashMap<>(initSegmentData);
            segmentSnapshot = new LinkedHashMap<>(segmentData);
            playlistBytes = generatePlaylistLocked();
        }

        for (Map.Entry<String, byte[]> entry : initSnapshot.entrySet()) {
            Files.write(trackDir.resolve(entry.getKey()), entry.getValue(), CREATE, WRITE, TRUNCATE_EXISTING);
        }
        for (Map.Entry<String, byte[]> entry : segmentSnapshot.entrySet()) {
            Files.write(trackDir.resolve(entry.getKey()), entry.getValue(), CREATE, WRITE, TRUNCATE_EXISTING);
        }

        Files.write(trackDir.resolve("playlist.m3u8"), playlistBytes, CREATE, WRITE, TRUNCATE_EXISTING);
        LOG.info("Dumped in-memory LL-HLS {} workspace to {}", name, trackDir);
    }

    /**
     * Clear all in-memory data.
     */
    void clear() {
        synchronized (tracks) {
            segmentData.clear();
            initSegmentData.clear();
            tracks.clear();
            segmentOrder.clear();
            trackInitSegments.clear();
            initSegmentIndex.clear();
            currentMapUrl = null;
            mediaSequenceBase = 0;
        }
    }

    boolean isEmpty() {
        synchronized (tracks) {
            return tracks.isEmpty();
        }
    }

    private void evictOldSegments() {
        while (tracks.size() > windowSize && !segmentOrder.isEmpty()) {
            // Remove oldest track entry
            tracks.remove(0);
            trackInitSegments.remove(0);
            mediaSequenceBase++;

            // Remove oldest segment file from memory
            String oldestName = segmentOrder.remove(0);
            segmentData.remove(oldestName);
        }
        evictUnusedInitSegments();
    }

    private List<TrackData> buildPlaylistTracksLocked() {
        List<TrackData> playlistTracks = new ArrayList<>(tracks.size());
        String previousInit = null;
        for (int i = 0; i < tracks.size(); i++) {
            TrackData track = tracks.get(i);
            String activeInit = trackInitSegments.get(i);

            TrackData.Builder builder = new TrackData.Builder()
                    .withUri(track.getUri())
                    .withTrackInfo(track.getTrackInfo());
            if (track.hasDiscontinuity()) {
                builder.withDiscontinuity(true);
            }
            if (track.hasProgramDateTime()) {
                builder.withProgramDateTime(track.getProgramDateTime());
            }
            if (activeInit != null && !activeInit.equals(previousInit)) {
                builder.withMapInfo(new MapInfo.Builder().withUri(activeInit).build());
            }
            playlistTracks.add(builder.build());
            previousInit = activeInit;
        }
        return playlistTracks;
    }

    private void evictUnusedInitSegments() {
        Set<String> activeInitSegments = new HashSet<>();
        for (String localInit : trackInitSegments) {
            if (localInit != null) {
                activeInitSegments.add(localInit);
            }
        }

        initSegmentData.keySet().removeIf(localInit -> !activeInitSegments.contains(localInit));
        initSegmentIndex.entrySet().removeIf(entry -> !activeInitSegments.contains(entry.getValue()));
        if (tracks.isEmpty()) {
            currentMapUrl = null;
        }
    }

    private static String detectExtension(String url, String fallback) {
        try {
            String path = URI.create(url).getPath();
            int idx = path.lastIndexOf('.');
            if (idx < 0) {
                return fallback;
            }
            return path.substring(idx);
        } catch (Exception e) {
            return fallback;
        }
    }
}
