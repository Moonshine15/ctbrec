package ctbrec.sites.chaturbate;

import java.util.List;

public record ChaturbateLlhlsMediaPlaylist(String playlistUrl,
                                           int mediaSequence,
                                           int targetDuration,
                                           List<ChaturbateLlhlsSegment> segments) {

    public boolean isEmpty() {
        return segments.isEmpty();
    }
}
