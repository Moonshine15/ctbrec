package ctbrec.recorder.download.hls;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfmpegOutputArgsTest {

    @Test
    void buildPairInputArguments_mapsVideoAndAudioInputsExplicitly() {
        List<String> args = FfmpegOutputArgs.buildPairInputArguments(
                Path.of("/tmp/video/playlist.m3u8"),
                Path.of("/tmp/audio/playlist.m3u8")
        );

        assertEquals(List.of(
                "-i", "/tmp/video/playlist.m3u8",
                "-i", "/tmp/audio/playlist.m3u8",
                "-map", "0:v:0",
                "-map", "1:a:0"
        ), args);
    }

    @Test
    void buildPairInputArguments_appliesPositiveInputDelaysBeforeRespectiveInputs() {
        List<String> args = FfmpegOutputArgs.buildPairInputArguments(
                Path.of("/tmp/video/playlist.m3u8"),
                Duration.ZERO,
                Path.of("/tmp/audio/playlist.m3u8"),
                Duration.ofMillis(799)
        );

        assertEquals(List.of(
                "-i", "/tmp/video/playlist.m3u8",
                "-itsoffset", "0.799000",
                "-i", "/tmp/audio/playlist.m3u8",
                "-map", "0:v:0",
                "-map", "1:a:0"
        ), args);
    }

    @Test
    void buildPlaylistOutputArguments_createsEventPlaylistOutput() {
        List<String> args = FfmpegOutputArgs.buildPlaylistOutputArguments(Path.of("/tmp/recording"));

        assertEquals("-c", args.get(0));
        assertEquals("copy", args.get(1));
        assertTrue(args.contains("event"));
        assertTrue(args.contains("/tmp/recording/playlist.m3u8"));
        assertTrue(args.contains("/tmp/recording/segment_%06d.ts"));
    }

    @Test
    void buildSingleFileOutputArguments_stripsCustomMapAndDropFlags() {
        File target = new File("/tmp/recording.ts");
        List<String> strippedFlags = new ArrayList<>();

        List<String> args = FfmpegOutputArgs.buildSingleFileOutputArguments(
                "-map 0:v -c:v copy -c:a copy -an -f mpegts",
                target,
                strippedFlags
        );

        assertEquals(List.of("-c:v", "copy", "-c:a", "copy", "-f", "mpegts", "/tmp/recording.ts"), args);
        assertEquals(List.of("-map", "0:v", "-an"), strippedFlags);
    }

    @Test
    void sanitizeCustomOutputArguments_stripsAdditionalInputFlags() {
        List<String> strippedFlags = new ArrayList<>();

        List<String> args = FfmpegOutputArgs.sanitizeCustomOutputArguments(
                "-i second-input.m3u8 -vn -sn -c copy -movflags faststart",
                strippedFlags
        );

        assertEquals(List.of("-c", "copy", "-movflags", "faststart"), args);
        assertEquals(List.of("-i", "second-input.m3u8", "-vn", "-sn"), strippedFlags);
    }
}
