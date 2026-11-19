package ctbrec.sites.chaturbate;

import java.time.Duration;

record ChaturbateLlhlsInputAlignment(Duration videoInputDelay, Duration audioInputDelay) {

    private static final Duration MIN_DELAY_TO_APPLY = Duration.ofMillis(50);
    private static final Duration MAX_DELAY_TO_APPLY = Duration.ofSeconds(2);
    private static final ChaturbateLlhlsInputAlignment NONE =
            new ChaturbateLlhlsInputAlignment(Duration.ZERO, Duration.ZERO);

    static ChaturbateLlhlsInputAlignment none() {
        return NONE;
    }

    static ChaturbateLlhlsInputAlignment fromStartTimes(double videoStartTime, double audioStartTime) {
        Duration delta = durationFromSeconds(Math.abs(audioStartTime - videoStartTime));
        if (delta.compareTo(MIN_DELAY_TO_APPLY) < 0 || delta.compareTo(MAX_DELAY_TO_APPLY) > 0) {
            return none();
        }
        if (audioStartTime > videoStartTime) {
            return new ChaturbateLlhlsInputAlignment(Duration.ZERO, delta);
        }
        return new ChaturbateLlhlsInputAlignment(delta, Duration.ZERO);
    }

    boolean requiresAdjustment() {
        return !videoInputDelay.isZero() || !audioInputDelay.isZero();
    }

    private static Duration durationFromSeconds(double seconds) {
        long nanos = Math.round(seconds * 1_000_000_000d);
        return Duration.ofNanos(Math.max(0L, nanos));
    }
}
