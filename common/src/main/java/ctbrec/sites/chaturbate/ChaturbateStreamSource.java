package ctbrec.sites.chaturbate;

import ctbrec.recorder.download.StreamSource;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChaturbateStreamSource extends StreamSource {
    private boolean llhls;
    private String videoPlaylistUrl;
    private String audioPlaylistUrl;
    private String audioGroupId;
    private String audioRenditionName;

    public boolean hasSeparateAudio() {
        return audioPlaylistUrl != null && !audioPlaylistUrl.isBlank();
    }

    @Override
    public ChaturbateStreamSource copy() {
        ChaturbateStreamSource copy = new ChaturbateStreamSource();
        copy.setBandwidth(getBandwidth());
        copy.setWidth(getWidth());
        copy.setHeight(getHeight());
        copy.setMediaPlaylistUrl(getMediaPlaylistUrl());
        copy.setMasterPlaylistUrl(getMasterPlaylistUrl());
        copy.setFfmpegProgramIndex(getFfmpegProgramIndex());
        copy.setLlhls(llhls);
        copy.setVideoPlaylistUrl(videoPlaylistUrl);
        copy.setAudioPlaylistUrl(audioPlaylistUrl);
        copy.setAudioGroupId(audioGroupId);
        copy.setAudioRenditionName(audioRenditionName);
        return copy;
    }
}
