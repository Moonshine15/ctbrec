package ctbrec.sites.chaturbate;

import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsPlaylistStartTimeDetectorTest {

    @Test
    void parseStartTime_extractsPlaylistStartTimeFromFfmpegProbeOutput() {
        String stderr = """
                Input #0, hls, from '/tmp/video/playlist.m3u8':
                  Duration: N/A, start: 4388.000978, bitrate: 5192 kb/s
                  Program 0
                """;

        OptionalDouble startTime = ChaturbateLlhlsPlaylistStartTimeDetector.parseStartTime(stderr);

        assertTrue(startTime.isPresent());
        assertEquals(4388.000978, startTime.getAsDouble(), 0.000001);
    }
}
