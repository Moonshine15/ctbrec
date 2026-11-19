package ctbrec.sites.chaturbate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsStartupStrategyTest {

    @Test
    void chooseStartPosition_alignsByProgramDateTimeWhenAvailable() {
        ChaturbateLlhlsMediaPlaylist videoPlaylist = new ChaturbateLlhlsMediaPlaylist(
                "video",
                10,
                2,
                List.of(
                        new ChaturbateLlhlsSegment(10, "v10", 2f, "2026-03-14T15:00:00Z", false, "map"),
                        new ChaturbateLlhlsSegment(11, "v11", 2f, "2026-03-14T15:00:02Z", false, "map"),
                        new ChaturbateLlhlsSegment(12, "v12", 2f, "2026-03-14T15:00:04Z", false, "map")
                )
        );
        ChaturbateLlhlsMediaPlaylist audioPlaylist = new ChaturbateLlhlsMediaPlaylist(
                "audio",
                50,
                2,
                List.of(
                        new ChaturbateLlhlsSegment(50, "a50", 2f, "2026-03-14T15:00:01Z", false, "map"),
                        new ChaturbateLlhlsSegment(51, "a51", 2f, "2026-03-14T15:00:03Z", false, "map"),
                        new ChaturbateLlhlsSegment(52, "a52", 2f, "2026-03-14T15:00:05Z", false, "map")
                )
        );

        ChaturbateLlhlsStartPosition startPosition = ChaturbateLlhlsStartupStrategy.chooseStartPosition(videoPlaylist, audioPlaylist, 2);

        assertTrue(startPosition.alignedByProgramDateTime());
        assertEquals(12, startPosition.videoSequence());
        assertEquals(51, startPosition.audioSequence());
    }

    @Test
    void chooseStartPosition_fallsBackToTailOffsetWithoutProgramDateTime() {
        ChaturbateLlhlsMediaPlaylist videoPlaylist = new ChaturbateLlhlsMediaPlaylist(
                "video",
                10,
                2,
                List.of(
                        new ChaturbateLlhlsSegment(10, "v10", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(11, "v11", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(12, "v12", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(13, "v13", 2f, null, false, "map")
                )
        );
        ChaturbateLlhlsMediaPlaylist audioPlaylist = new ChaturbateLlhlsMediaPlaylist(
                "audio",
                20,
                2,
                List.of(
                        new ChaturbateLlhlsSegment(20, "a20", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(21, "a21", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(22, "a22", 2f, null, false, "map"),
                        new ChaturbateLlhlsSegment(23, "a23", 2f, null, false, "map")
                )
        );

        ChaturbateLlhlsStartPosition startPosition = ChaturbateLlhlsStartupStrategy.chooseStartPosition(videoPlaylist, audioPlaylist, 2);

        assertFalse(startPosition.alignedByProgramDateTime());
        assertEquals(12, startPosition.videoSequence());
        assertEquals(22, startPosition.audioSequence());
    }
}
