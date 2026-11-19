package ctbrec.sites.chaturbate;

import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.MediaData;
import com.iheartradio.m3u8.data.MediaType;
import com.iheartradio.m3u8.data.PlaylistData;
import ctbrec.recorder.download.StreamSource;
import ctbrec.recorder.download.StreamSource.AudioSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ChaturbatePlaylistResolver {

    private ChaturbatePlaylistResolver() {
    }

    static List<ChaturbateStreamSource> extractStreamSources(String masterPlaylistUrl, MasterPlaylist masterPlaylist) {
        Map<String, List<MediaData>> audioGroups = extractAudioGroups(masterPlaylist);
        List<ChaturbateStreamSource> sources = new ArrayList<>();
        int programIndex = 0;
        for (PlaylistData playlist : masterPlaylist.getPlaylists()) {
            if (!playlist.hasStreamInfo()) {
                programIndex++;
                continue;
            }

            ChaturbateStreamSource source = new ChaturbateStreamSource();
            source.setBandwidth(playlist.getStreamInfo().getBandwidth());
            if (playlist.getStreamInfo().hasResolution()) {
                source.setHeight(playlist.getStreamInfo().getResolution().height);
                source.setWidth(playlist.getStreamInfo().getResolution().width);
            }
            source.setMasterPlaylistUrl(masterPlaylistUrl);
            source.setFfmpegProgramIndex(programIndex);

            String videoPlaylistUrl = resolvePlaylistUrl(masterPlaylistUrl, playlist.getUri());
            source.setVideoPlaylistUrl(videoPlaylistUrl);
            String playbackPlaylistUrl = videoPlaylistUrl;

            boolean llhls = masterPlaylistUrl.contains("llhls") || playlist.getUri().contains("llhls") || playlist.getUri().startsWith("/");
            if (playlist.getStreamInfo().hasAudio()) {
                String audioGroupId = playlist.getStreamInfo().getAudio();
                source.setAudioGroupId(audioGroupId);
                MediaData audioRendition = selectAudioRendition(audioGroups.get(audioGroupId));
                if (audioRendition == null) {
                    throw new IllegalStateException("Chaturbate variant references missing audio group \"" + audioGroupId + "\"");
                }
                if (!audioRendition.hasUri()) {
                    throw new IllegalStateException("Chaturbate audio rendition \"" + audioRendition.getName() + "\" has no URI");
                }
                source.setAudioRenditionName(audioRendition.getName());
                source.setAudioPlaylistUrl(resolvePlaylistUrl(masterPlaylistUrl, audioRendition.getUri()));
                //FIXME playbackPlaylistUrl = masterPlaylistUrl;
                llhls = true;
                
                var audio = new StreamSource.AudioSource();
                audio.setGroupId(audioGroupId);
                audio.setPlaylistUrl(source.getAudioPlaylistUrl());
                source.setAudio(audio);
            }

            source.setMediaPlaylistUrl(playbackPlaylistUrl);
            source.setLlhls(llhls);
            sources.add(source);
            programIndex++;
        }
        return sources;
    }

    static ChaturbateStreamSource findMatchingStreamSource(ChaturbateStreamSource selectedSource, List<? extends StreamSource> streamSources) {
        return streamSources.stream()
                .filter(ChaturbateStreamSource.class::isInstance)
                .map(ChaturbateStreamSource.class::cast)
                .filter(candidate -> candidate.getWidth() == selectedSource.getWidth())
                .filter(candidate -> candidate.getHeight() == selectedSource.getHeight())
                .filter(candidate -> candidate.getBandwidth() == selectedSource.getBandwidth())
                .filter(candidate -> Objects.equals(candidate.getAudioGroupId(), selectedSource.getAudioGroupId()))
                .filter(candidate -> Objects.equals(candidate.getAudioRenditionName(), selectedSource.getAudioRenditionName()))
                .findFirst()
                .orElseGet(() -> streamSources.stream()
                        .filter(ChaturbateStreamSource.class::isInstance)
                        .map(ChaturbateStreamSource.class::cast)
                        .filter(candidate -> candidate.getWidth() == selectedSource.getWidth())
                        .filter(candidate -> candidate.getHeight() == selectedSource.getHeight())
                        .filter(candidate -> Objects.equals(candidate.getAudioGroupId(), selectedSource.getAudioGroupId()))
                        .filter(candidate -> Objects.equals(candidate.getAudioRenditionName(), selectedSource.getAudioRenditionName()))
                        .min(Comparator.comparingInt(candidate -> Math.abs(candidate.getBandwidth() - selectedSource.getBandwidth())))
                        .orElse(null));
    }

    static String resolvePlaylistUrl(String masterUrl, String playlistUri) {
        if (playlistUri.startsWith("http://") || playlistUri.startsWith("https://")) {
            return playlistUri;
        }
        return URI.create(masterUrl).resolve(playlistUri).toString();
    }

    private static Map<String, List<MediaData>> extractAudioGroups(MasterPlaylist masterPlaylist) {
        Map<String, List<MediaData>> groups = new HashMap<>();
        for (MediaData mediaData : masterPlaylist.getMediaData()) {
            if (mediaData.getType() != MediaType.AUDIO) {
                continue;
            }
            groups.computeIfAbsent(mediaData.getGroupId(), unused -> new ArrayList<>()).add(mediaData);
        }
        return groups;
    }

    private static MediaData selectAudioRendition(List<MediaData> renditions) {
        if (renditions == null || renditions.isEmpty()) {
            return null;
        }
        return renditions.stream()
                .filter(MediaData::isDefault)
                .findFirst()
                .or(() -> renditions.stream().filter(MediaData::isAutoSelect).findFirst())
                .orElse(renditions.get(0));
    }
}
