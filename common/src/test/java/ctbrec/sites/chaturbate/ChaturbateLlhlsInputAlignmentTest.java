package ctbrec.sites.chaturbate;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChaturbateLlhlsInputAlignmentTest {

    @Test
    void fromStartTimes_delaysLaterAudioInputToPreserveMeasuredTrackOffset() {
        ChaturbateLlhlsInputAlignment alignment =
                ChaturbateLlhlsInputAlignment.fromStartTimes(4388.000978, 4388.800000);

        assertTrue(alignment.requiresAdjustment());
        assertEquals(Duration.ZERO, alignment.videoInputDelay());
        assertEquals(Duration.ofNanos(799_022_000), alignment.audioInputDelay());
    }

    @Test
    void fromStartTimes_ignoresTinyTimingDifferences() {
        ChaturbateLlhlsInputAlignment alignment =
                ChaturbateLlhlsInputAlignment.fromStartTimes(100.000000, 100.010000);

        assertFalse(alignment.requiresAdjustment());
    }
}
