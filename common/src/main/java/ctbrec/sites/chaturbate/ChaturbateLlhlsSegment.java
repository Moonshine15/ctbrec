package ctbrec.sites.chaturbate;

public record ChaturbateLlhlsSegment(int sequence,
                                     String url,
                                     float duration,
                                     String programDateTime,
                                     boolean discontinuity,
                                     String mapUrl) {
}
