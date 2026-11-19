package ctbrec.sites.chaturbate;

import com.iheartradio.m3u8.Encoding;
import com.iheartradio.m3u8.Format;
import com.iheartradio.m3u8.ParseException;
import com.iheartradio.m3u8.PlaylistException;
import com.iheartradio.m3u8.PlaylistParser;
import com.iheartradio.m3u8.ParsingMode;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class ChaturbateLlhlsPlaylistParser {

    private ChaturbateLlhlsPlaylistParser() {
    }

    public static ChaturbateLlhlsMediaPlaylist parse(String playlistUrl, String body) throws IOException, ParseException, PlaylistException {
        PlaylistParser parser = new PlaylistParser(new ByteArrayInputStream(body.getBytes(UTF_8)),
                Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT);
        Playlist playlist = parser.parse();
        if (!playlist.hasMediaPlaylist()) {
            throw new IOException("Playlist has no media playlist: " + playlistUrl);
        }

        MediaPlaylist mediaPlaylist = playlist.getMediaPlaylist();
        List<ChaturbateLlhlsSegment> segments = new ArrayList<>();
        String currentMapUrl = null;
        int sequence = mediaPlaylist.getMediaSequenceNumber();
        for (TrackData trackData : mediaPlaylist.getTracks()) {
            if (trackData.hasMapInfo()) {
                currentMapUrl = resolveUri(playlistUrl, trackData.getMapInfo().getUri());
            }
            if (!trackData.hasTrackInfo()) {
                continue;
            }

            segments.add(new ChaturbateLlhlsSegment(
                    sequence++,
                    resolveUri(playlistUrl, trackData.getUri()),
                    Math.max(0, trackData.getTrackInfo().duration),
                    trackData.hasProgramDateTime() ? trackData.getProgramDateTime() : null,
                    trackData.hasDiscontinuity(),
                    currentMapUrl
            ));
        }

        return new ChaturbateLlhlsMediaPlaylist(
                playlistUrl,
                mediaPlaylist.getMediaSequenceNumber(),
                mediaPlaylist.getTargetDuration(),
                segments
        );
    }

    private static String resolveUri(String playlistUrl, String uri) {
        if (uri.startsWith("http")) {
            return uri;
        }
        return URI.create(playlistUrl).resolve(uri).toString();
    }
}
