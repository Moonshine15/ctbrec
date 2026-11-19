package ctbrec.sites.chaturbate;

import java.time.Instant;
import java.util.List;

final class ChaturbateLlhlsStartupStrategy {

    private ChaturbateLlhlsStartupStrategy() {
    }

    static ChaturbateLlhlsStartPosition chooseStartPosition(ChaturbateLlhlsMediaPlaylist videoPlaylist,
                                                            ChaturbateLlhlsMediaPlaylist audioPlaylist,
                                                            int liveEdgeDelaySegments) {
        if (videoPlaylist.isEmpty()) {
            throw new IllegalStateException("Video playlist has no complete segments");
        }
        if (audioPlaylist.isEmpty()) {
            throw new IllegalStateException("Audio playlist has no complete segments");
        }

        ChaturbateLlhlsSegment videoCandidate = chooseTailSegment(videoPlaylist.segments(), liveEdgeDelaySegments);
        ChaturbateLlhlsSegment audioCandidate = chooseTailSegment(audioPlaylist.segments(), liveEdgeDelaySegments);

        Instant videoTime = parseProgramDateTime(videoCandidate.programDateTime());
        Instant audioTime = parseProgramDateTime(audioCandidate.programDateTime());
        if (videoTime != null && audioTime != null) {
            Instant commonStart = videoTime.isAfter(audioTime) ? videoTime : audioTime;
            int videoSequence = findFirstSequenceAtOrAfter(videoPlaylist.segments(), commonStart, videoCandidate.sequence());
            int audioSequence = findFirstSequenceAtOrAfter(audioPlaylist.segments(), commonStart, audioCandidate.sequence());
            return new ChaturbateLlhlsStartPosition(videoSequence, audioSequence, true);
        }

        return new ChaturbateLlhlsStartPosition(videoCandidate.sequence(), audioCandidate.sequence(), false);
    }

    private static ChaturbateLlhlsSegment chooseTailSegment(List<ChaturbateLlhlsSegment> segments, int liveEdgeDelaySegments) {
        int index = Math.max(0, segments.size() - Math.max(liveEdgeDelaySegments, 1));
        return segments.get(index);
    }

    private static int findFirstSequenceAtOrAfter(List<ChaturbateLlhlsSegment> segments, Instant timestamp, int fallbackSequence) {
        return segments.stream()
                .filter(segment -> parseProgramDateTime(segment.programDateTime()) != null)
                .filter(segment -> !parseProgramDateTime(segment.programDateTime()).isBefore(timestamp))
                .findFirst()
                .map(ChaturbateLlhlsSegment::sequence)
                .orElse(fallbackSequence);
    }

    private static Instant parseProgramDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }
}
