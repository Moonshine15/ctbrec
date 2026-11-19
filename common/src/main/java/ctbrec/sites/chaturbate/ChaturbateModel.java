package ctbrec.sites.chaturbate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iheartradio.m3u8.*;
import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import ctbrec.AbstractModel;
import ctbrec.Config;
import ctbrec.StringUtil;
import ctbrec.io.HttpException;
import ctbrec.io.json.ObjectMapperFactory;
import ctbrec.recorder.download.AbstractDownload;
import ctbrec.recorder.download.RecordingProcess;
import ctbrec.recorder.download.StreamSource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static ctbrec.Model.State.*;
import static ctbrec.io.HttpConstants.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.regex.Pattern;

@Slf4j
public class ChaturbateModel extends AbstractModel {

    private static final String PUBLIC = "public";
    private static final Pattern serverPattern = Pattern.compile("live-.+amlst");
    
    private int[] resolution = new int[2];
    private transient StreamInfo streamInfo;
    private transient Instant lastStreamInfoRequest = Instant.EPOCH;
    private transient MasterPlaylist masterPlaylist;
    private transient Instant lastMasterPlaylistRequest = Instant.EPOCH;
    private static final Random RNG = new Random();
    private static int offlineImageSize = 0;
    private final transient ObjectMapper mapper = ObjectMapperFactory.getMapper();

    /**
     * This constructor exists only for deserialization. Please don't call it directly
     */
    @SuppressWarnings("unused")
    public ChaturbateModel() {
    }

    ChaturbateModel(Chaturbate site) {
        this.site = site;
    }

    @Override
    public boolean isOnline(boolean ignoreCache) throws IOException, ExecutionException, InterruptedException {
        String roomStatus;
        if (ignoreCache) {
            if (isOffline()) {
                roomStatus = "offline";
                onlineState = State.OFFLINE;
                log.trace("Model {} offline", getName());
            } else {
                StreamInfo info = getStreamInfo();
                roomStatus = Optional.ofNullable(info).map(i -> i.room_status).orElse("Unknown");
                log.trace("Model {} room status: {}", getName(), Optional.ofNullable(info).map(i -> i.room_status).orElse("Unknown"));
            }
        } else {
            StreamInfo info = getStreamInfo(true);
            roomStatus = Optional.ofNullable(info).map(i -> i.room_status).orElse("");
        }
        return Objects.equals(PUBLIC, roomStatus);
    }

    private boolean isOffline() {
        String normalizedName = getName().toLowerCase().trim();
        String previewUrl = "https://thumb.live.mmcdn.com/riw/" + normalizedName + ".jpg?" + Instant.now().getEpochSecond();
        if (offlineImageSize == 0) {
            offlineImageSize = getOfflineImageSize(); // NOSONAR
        }
        return getImageSize(previewUrl) == offlineImageSize;
    }

    private int getOfflineImageSize() {
        String[] names = {"Sophia", "Helena", "Olivia", "Natasha", "Emmy", "Jenny", "Diana", "Teresa", "Julia", "Polly", "Amanda"};
        String randomName = names[RNG.nextInt(names.length)] + RNG.nextInt(99);
        String previewUrl = "https://thumb.live.mmcdn.com/riw/" + randomName + ".jpg?" + Instant.now().getEpochSecond();
        int imageSize = getImageSize(previewUrl);
        if (imageSize == 0) {
            imageSize = 21971;
        }
        return imageSize;
    }

    private int getImageSize(String url) {
        int imageSize = 0;
        Request req = new Request.Builder()
                .url(url)
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(url).getHost()))
                .head()
                .build();
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                imageSize = Integer.parseInt(response.header("Content-Length", "0"));
                if (StringUtil.isNotBlank(response.header("Cf-Polished"))) {
                    String[] parts = response.header("Cf-Polished").split("=");
                    if (parts.length > 1) {
                        imageSize = Integer.parseInt(parts[1].trim());
                    }
                }
            }
        } catch (Exception ex) {
            // fail silently
        }
        return imageSize;
    }

    @Override
    public int[] getStreamResolution(boolean failFast) throws ExecutionException {
        if (failFast) {
            return resolution;
        }
        try {
            resolution = getResolution();
        } catch (Exception e) {
            throw new ExecutionException(e);
        }
        return resolution;
    }

    /**
     * Invalidates the entries in StreamInfo and resolution cache for this model
     * and thus causes causes the LoadingCache to update them
     */
    @Override
    public void invalidateCacheEntries() {
        streamInfo = null;
    }

    @Override
    public State updateOnlineState(boolean failFast) throws IOException, ExecutionException {
        if (failFast) {
            if (onlineState != UNCHECKED) {
                return onlineState;
            } else {
                setOnlineStateByRoomStatus(Optional.ofNullable(streamInfo).map(si -> si.room_status).orElse(null));
            }
        } else {
            if (isOffline()) {
                onlineState = OFFLINE;
            } else {
                streamInfo = loadStreamInfo();
                setOnlineStateByRoomStatus(streamInfo.room_status);
            }
        }
        return onlineState;
    }

    private void setOnlineStateByRoomStatus(String roomStatus) {
        if (roomStatus != null) {
            switch (roomStatus) {
                case PUBLIC, "Unknown" -> onlineState = ONLINE;
                case "offline" -> onlineState = OFFLINE;
                case "private", "hidden", "password protected" -> onlineState = PRIVATE;
                case "away" -> onlineState = AWAY;
                case "group" -> onlineState = State.GROUP;
                default -> {
                    log.debug("Unknown show type {}", roomStatus);
                    onlineState = State.UNKNOWN;
                }
            }
        }
    }

    @Override
    public void receiveTip(Double tokens) throws IOException {
        if (!Objects.equals(System.getenv("CTBREC_DEV"), "1")) {
            RequestBody body = new FormBody.Builder()
                    .add("csrfmiddlewaretoken", ((ChaturbateHttpClient) getSite().getHttpClient()).getToken())
                    .add("tip_amount", Integer.toString(tokens.intValue()))
                    .add("tip_room_type", PUBLIC)
                    .build();
            Request req = new Request.Builder()
                    .url("https://chaturbate.com/tipping/send_tip/" + getName() + "/")
                    .post(body)
                    .header(REFERER, "https://chaturbate.com/" + getName() + "/")
                    .header(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                    .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent("chaturbate.com"))
                    .build();
            try (Response response = getSite().getHttpClient().execute(req)) {
                if (!response.isSuccessful()) {
                    throw new IOException(response.code() + " " + response.message());
                }
            }
        }
    }

    @Override
    public List<StreamSource> getStreamSources() throws IOException, ExecutionException, ParseException, PlaylistException {
        var streamInfo = loadStreamInfo();
        var masterPlaylist = getMasterPlaylist();
        return new ArrayList<>(extractStreamSources(streamInfo.hls_source, masterPlaylist));
    }

    public ChaturbateStreamSource refreshSelectedStreamSource(ChaturbateStreamSource selectedSource) throws IOException {
        Objects.requireNonNull(selectedSource, "selectedSource");
        StreamInfo freshStreamInfo = loadStreamInfo(true);
        
        if (freshStreamInfo == null || freshStreamInfo.hls_source == null || !freshStreamInfo.hls_source.startsWith("http")) {
            throw new IOException("Fresh Chaturbate master playlist URL unavailable for " + getName());
        }
        
        try {
            List<StreamSource> refreshedSources = getStreamSources();
            ChaturbateStreamSource refreshedSource = ChaturbatePlaylistResolver.findMatchingStreamSource(selectedSource, refreshedSources);
            
            if (refreshedSource == null) {
                throw new IOException("Couldn't refresh the selected Chaturbate stream source for " + getName());
            }
            
            return refreshedSource;
        } catch (ExecutionException | ParseException | PlaylistException e) {
            throw new IOException("Couldn't refresh the selected Chaturbate stream source for " + getName(), e);
        }
    }

    static List<ChaturbateStreamSource> extractStreamSources(String masterPlaylistUrl, MasterPlaylist masterPlaylist) {
        return ChaturbatePlaylistResolver.extractStreamSources(masterPlaylistUrl, masterPlaylist);
    }

    /**
     * Builds the full media playlist URL from the master playlist URL and a playlist URI entry.
     * <p>
     * Chaturbate uses two HLS formats:
     * <ul>
     *   <li><b>Legacy HLS</b> — relative URIs (e.g. {@code chunklist_w...m3u8}). Resolved by
     *       prepending the base directory of the master playlist URL.</li>
     *   <li><b>LLHLS</b> — absolute path URIs starting with {@code /} and including a
     *       {@code ?session=} query parameter. Resolved by prepending the origin
     *       (scheme + host) of the master playlist URL.</li>
     * </ul>
     */
    static String buildMediaPlaylistUrl(String masterUrl, String playlistUri) {
        return ChaturbatePlaylistResolver.resolvePlaylistUrl(masterUrl, playlistUri);
    }

    @Override
    public boolean follow() throws IOException {
        return follow(true);
    }

    @Override
    public boolean unfollow() throws IOException {
        return follow(false);
    }

    @Override
    public RecordingProcess createDownload() {
        boolean outputAsPlaylist = Config.isServerMode() && !Config.getInstance().getSettings().recordSingleFile;
        
        try {
            var selectedSource = resolveSelectedStreamSource();
            
            if (selectedSource.isLlhls() && selectedSource.hasSeparateAudio()) {
                log.info("Using Chaturbate LL-HLS downloader for {} with video {} and audio {}", getName(),
                        selectedSource.getVideoPlaylistUrl(), selectedSource.getAudioPlaylistUrl());
                return new ChaturbateLlhlsDownload(getSite().getHttpClient(), outputAsPlaylist, selectedSource.copy());
            }
        } catch (IOException e) {
            log.warn("Error creating ChaturbateLlhlsDownload for {}", getName(), e);
        }
        
        log.info("Using legacy Chaturbate HLS downloader for {}", getName());
        return super.createDownload();
    }
    
    private ChaturbateStreamSource resolveSelectedStreamSource() throws IOException {
        try {
            List<StreamSource> streamSources = getStreamSources();
            Collections.sort(streamSources);
            for (StreamSource streamSource : streamSources) {
                log.debug("{}:{} src {}", getSite().getName(), getName(), streamSource);
            }
            StreamSource selectedSource = AbstractDownload.selectStreamSource(this, streamSources);
            if (!(selectedSource instanceof ChaturbateStreamSource chaturbateStreamSource)) {
                throw new IOException("Expected Chaturbate stream source metadata for " + this);
            }
            return chaturbateStreamSource;
        } catch (ExecutionException | ParseException | PlaylistException e) {
            throw new IOException("Couldn't resolve Chaturbate stream source for " + this, e);
        }
    }

    private boolean follow(boolean follow) throws IOException {
        // do an initial request to get cookies
        Request req = new Request.Builder()
                .url(getUrl())
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(getUrl()).getHost()))
                .build();
        Response resp = site.getHttpClient().execute(req);
        resp.close();

        String url;
        if (follow) {
            url = getSite().getBaseUrl() + "/follow/follow/" + getName() + "/";
        } else {
            url = getSite().getBaseUrl() + "/follow/unfollow/" + getName() + "/";
        }

        RequestBody body = RequestBody.create(new byte[0]);
        req = new Request.Builder()
                .url(url)
                .method("POST", body)
                .header(ACCEPT, "*/*")
                .header(ACCEPT_LANGUAGE, "en-US,en;q=0.5")
                .header(REFERER, getUrl())
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(url).getHost()))
                .header("X-CSRFToken", ((ChaturbateHttpClient) site.getHttpClient()).getToken())
                .header(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                .build();
        try (Response resp2 = site.getHttpClient().execute(req)) {
            if (resp2.isSuccessful()) {
                String responseBody = resp2.body().string();
                JSONObject json = new JSONObject(responseBody);
                if (!json.has("following")) {
                    log.debug(responseBody);
                    throw new IOException("Response was " + responseBody.substring(0, Math.min(responseBody.length(), 500)));
                } else {
                    log.debug("Follow/Unfollow -> {}", responseBody);
                    return json.getBoolean("following") == follow;
                }
            } else {
                throw new HttpException(resp2.code(), resp2.message());
            }
        }
    }

    private StreamInfo getStreamInfo() throws IOException {
        return getStreamInfo(false);
    }

    private StreamInfo getStreamInfo(boolean failFast) throws IOException {
        if (failFast) {
            return streamInfo;
        } else {
            return Optional.ofNullable(streamInfo).orElse(loadStreamInfo());
        }
    }

    private StreamInfo loadStreamInfo() throws IOException {
        return loadStreamInfo(false);
    }

    private StreamInfo loadStreamInfo(boolean forceRefresh) throws IOException {
        if (!forceRefresh && streamInfo != null && Duration.between(lastStreamInfoRequest, Instant.now()).getSeconds() < 5) {
            return streamInfo;
        }
        streamInfo = requestStreamInfo();
        lastStreamInfoRequest = Instant.now();
        return streamInfo;
    }

    protected StreamInfo requestStreamInfo() throws IOException {
        Request req = new Request.Builder()
                .url(getSite().getBaseUrl() + "/api/chatvideocontext/" + getName() + '/')
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(getSite().getBaseUrl()).getHost()))
                .header(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                .build();
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                String content = response.body().string();
                log.trace("Raw stream info for model {}: {}", getName(), content);
                streamInfo = mapper.readValue(content, StreamInfo.class);
                
                if (streamInfo.cmaf_edge) {
                    if (streamInfo.hls_source.contains("playlist.m3u8")) {
                        streamInfo.hls_source = streamInfo.hls_source.replace("playlist.m3u8", "playlist_sfm4s.m3u8");
                    } else if (streamInfo.hls_source.contains("playlist_sfm4s.m3u8")) {
                        streamInfo.hls_source = streamInfo.hls_source.replace("playlist_sfm4s.m3u8", "playlist.m3u8");
                    }
                    
                    var match = serverPattern.matcher(streamInfo.hls_source);
                    
                    if (getSite().getHttpClient().isLoggedIn()) {
                        streamInfo.hls_source = match.replaceFirst("live-fhls/amlst");  
                    } else {
                        streamInfo.hls_source = match.replaceFirst("live-c-fhls/amlst");
                    }
                }
                
                return streamInfo;
            } else {
                int code = response.code();
                String message = response.message();
                throw new HttpException(code, message);
            }
        }
    }

    private int[] getResolution() throws IOException, ParseException, PlaylistException {
        int[] res = new int[2];
        if (!getStreamInfo().hls_source.startsWith("http")) {
            return res;
        }

        EOFException ex = null;
        for (int i = 0; i < 2; i++) {
            try {
                MasterPlaylist master = getMasterPlaylist();
                for (PlaylistData playlistData : master.getPlaylists()) {
                    if (playlistData.hasStreamInfo() && playlistData.getStreamInfo().hasResolution()) {
                        int h = playlistData.getStreamInfo().getResolution().height;
                        int w = playlistData.getStreamInfo().getResolution().width;
                        if (w > res[1]) {
                            res[0] = w;
                            res[1] = h;
                        }
                    }
                }
                ex = null;
                break; // this attempt worked, exit loop
            } catch (EOFException e) {
                // the cause might be, that the playlist url in streaminfo is outdated,
                // so let's remove it from cache and retry in the next iteration
                ex = e;
            }
        }

        if (ex != null) {
            throw ex;
        }

        return res;
    }

    public MasterPlaylist getMasterPlaylist() throws IOException, ParseException, PlaylistException {
        // return previously loaded playlist as it is bound to stream info request - 
        // StreamInfo.hls_source is a one-time use link (it has a generated token by the server)
        if (lastMasterPlaylistRequest.isAfter(lastStreamInfoRequest) && masterPlaylist != null)
            return masterPlaylist;
        
        masterPlaylist = loadMasterPlaylist(getStreamInfo());
        lastMasterPlaylistRequest = Instant.now();
        return masterPlaylist;
    }

    private MasterPlaylist loadMasterPlaylist(StreamInfo streamInfo) throws IOException, ParseException, PlaylistException {        
        if (streamInfo.hls_source == null || streamInfo.hls_source.isEmpty())
            throw new IllegalArgumentException("Empty or null playlist url");
        
        log.trace("Loading master playlist {}", streamInfo.hls_source);
        Request req = new Request.Builder()
                .url(streamInfo.hls_source)
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(streamInfo.hls_source).getHost()))
                .build();
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                String body = response.body().string();
                log.trace(body);
                InputStream inputStream = new ByteArrayInputStream(body.getBytes(UTF_8));
                PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT);
                Playlist playlist = parser.parse();
                return playlist.getMasterPlaylist();
            } else {
                throw new HttpException(response.code(), response.message());
            }
        }
    }

    @Override
    public boolean exists() throws IOException {
        Request req = new Request.Builder() // @formatter:off
                .url(getUrl())
                .header(USER_AGENT, site.getHttpClient().getEffectiveUserAgent(URI.create(getUrl()).getHost()))
                .header(ACCEPT_LANGUAGE, Locale.ENGLISH.getLanguage())
                .build(); // @formatter:on
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (!response.isSuccessful() && response.code() == 404) {
                return false;
            } else {
                String body = response.body().string();
                boolean banned = body.contains("This room has been banned");
                boolean deleted = body.contains("This account has been deleted");
                boolean redirectedToRoot = Objects.equals("/", response.request().url().encodedPath());
                return !(banned || deleted || redirectedToRoot);
            }
        }
    }

    @Override
    public void setName(String name) {
        super.setName(name.toLowerCase().trim());
    }

    @Override
    public void setUrl(String url) {
        super.setUrl(url.toLowerCase().trim());
    }
}
