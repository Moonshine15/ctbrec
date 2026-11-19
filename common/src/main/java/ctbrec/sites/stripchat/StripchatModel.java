package ctbrec.sites.stripchat;

import com.iheartradio.m3u8.*;
import com.iheartradio.m3u8.data.MasterPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.PlaylistData;
import ctbrec.AbstractModel;
import ctbrec.Config;
import ctbrec.StringUtil;
import ctbrec.io.HttpException;
import ctbrec.recorder.download.RecordingProcess;
import ctbrec.recorder.download.StreamSource;
import ctbrec.recorder.download.hls.HlsdlDownload;
import ctbrec.recorder.download.hls.MergedFfmpegHlsDownload;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.apache.commons.text.StringSubstitutor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.HashMap;
import java.util.Map;

import static ctbrec.Model.State.*;
import static ctbrec.io.HttpConstants.*;
import static ctbrec.sites.stripchat.StripchatHttpClient.JSON;
import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class StripchatModel extends AbstractModel {
    private static final String KEY_MODEL_TOKEN = "modelToken";
    private int[] resolution = new int[]{0, 0};
    private transient JSONObject modelInfo;
    private transient Instant lastInfoRequest = Instant.EPOCH;

    
    @Override
    public boolean isOnline(boolean ignoreCache) throws IOException, ExecutionException, InterruptedException {
        if (ignoreCache) {
            JSONObject jsonResponse = getModelInfo();
            if (jsonResponse.has("user")) {
                JSONObject user = jsonResponse.getJSONObject("user").getJSONObject("user");
                String status = user.optString("status");
                mapOnlineState(status);
                if (onlineState == OFFLINE) {
                    setLastSeen(user.optString("statusChangedAt"));
                }
                if (isBanned(user)) {
                    log.debug("Model inactive or deleted: {}", getName());
                    setMarkedForLaterRecording(true);
                }
                if ((onlineState == PRIVATE) && jsonResponse.has("cam")) {
                    JSONObject cam = jsonResponse.getJSONObject("cam");
                    if (StringUtil.isNotBlank(cam.optString(KEY_MODEL_TOKEN))) {
                        setOnlineState(ONLINE);
                        return true;
                    }
                }
            }
            if (jsonResponse.optString("error").equals("Not Found")) {
                setMarkedForLaterRecording(true);
                setOnlineState(OFFLINE);
            }
        }
        return onlineState == ONLINE;
    }

    private boolean isBanned(JSONObject user) {
        boolean isDeleted = user.optBoolean("isDeleted", false);
        boolean isApprovedModel = user.optBoolean("isApprovedModel", true);
        return (!isApprovedModel && isDeleted);
    }

    private void setLastSeen(String date) {
        try {
            if (StringUtil.isNotBlank(date)) {
                setLastSeen(Instant.parse(date));
            }
        } catch (Exception e) {
            // fail silently
        }
    }

    private void mapOnlineState(String status) {
        switch (status) {
            case "public" -> setOnlineState(ONLINE);
            case "idle" -> setOnlineState(AWAY);
            case "private", "p2p", "groupShow", "virtualPrivate" -> setOnlineState(PRIVATE);
            case "off" -> setOnlineState(OFFLINE);
            default -> {
                log.debug("Unknown online state {} for model {}", status, getName());
                setOnlineState(OFFLINE);
            }
        }
    }

    private JSONObject getModelInfo() throws IOException {
        if (Objects.nonNull(modelInfo) && Duration.between(lastInfoRequest, Instant.now()).getSeconds() < 5) {
            return modelInfo;
        }
        lastInfoRequest = Instant.now();
        modelInfo = loadModelInfo();
        return modelInfo;
    }

    private JSONObject loadModelInfo() throws IOException {
        String url = getSite().getBaseUrl() + "/api/front/v2/models/username/" + getName() + "/cam?timezoneOffset=0&triggerRequest=loadCam&uniq=" + getUniq();
        Request req = new Request.Builder()
                .url(url)
                .header(ACCEPT, MIMETYPE_APPLICATION_JSON)
                .header(ACCEPT_LANGUAGE, Locale.ENGLISH.getLanguage())
                .header(X_REQUESTED_WITH, XML_HTTP_REQUEST)
                .header(USER_AGENT, Config.getInstance().getSettings().httpUserAgent)
                .header(REFERER, getUrl())
                .header(ORIGIN, getSite().getBaseUrl())
                .build();
        try (Response response = site.getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                JSONObject jsonResponse = new JSONObject(response.body().string());
                return jsonResponse;
            } else {
                throw new HttpException(response.code(), response.message());
            }
        }
    }

    @Override
    public List<StreamSource> getStreamSources() throws IOException, ExecutionException, ParseException, PlaylistException {
        String url = getMasterPlaylistUrl();
        MasterPlaylist masterPlaylist = getMasterPlaylist(url);
        List<StreamSource> streamSources = extractStreamSources(masterPlaylist);
        try {
            String originalUrl = url.replace("_auto", "");
            masterPlaylist = getMasterPlaylist(originalUrl);
            for (StreamSource original : extractStreamSources(masterPlaylist)) {
                boolean found = false;
                for (StreamSource source : streamSources) {
                    if (source.getHeight() == original.getHeight()) {
                        found = true;
                        break;
                    }
                }
                if (!found) streamSources.add(original);
            }
        } catch (Exception e) {
            log.warn("Original stream quality not available", e);
        }
        return streamSources;
    }

    private List<StreamSource> extractStreamSources(MasterPlaylist masterPlaylist) throws IOException {        
        // find mouflon scheme tag
        String mouflonVer = "";
        String mouflonKey = "";  
        boolean pschFound = false;              
        for (var tag : masterPlaylist.getUnknownTags()) {
            var m = StripchatPlaylistPreprocessor.PSCH_TAG_PAT.matcher(tag);
            if (m.matches()) {
                pschFound = true;              
                var ver = m.group(1);
                var key = m.group(2);
                if (Stripchat.getMOUFLON_DECRYPTERS().containsKey(new Stripchat.MouflonKey(ver, key))) {
                    mouflonVer = ver;
                    mouflonKey = key;
                    break;
                } else {
                    log.debug("Unknown scheme {}:{}. Skipping", ver, key);
                }
            }
        }     
        
        if (pschFound && mouflonKey.isEmpty()) {
            // fallback if no match for version-key pair - attempt to match just on key
            log.debug("Proper scheme not found, attempting fallback");
            for (var tag : masterPlaylist.getUnknownTags()) {
                var m = StripchatPlaylistPreprocessor.PSCH_TAG_PAT.matcher(tag);
                if (m.matches()) {
                    pschFound = true;              
                    var ver = m.group(1);
                    var key = m.group(2);
                    if (Stripchat.getMOUFLON_DECRYPTERS().containsKey(new Stripchat.MouflonKey("v1", key))) {
                        mouflonVer = "v1";
                        mouflonKey = key;
                        break;
                    } else {
                        log.debug("Unknown (fallback) key {}:{}. Skipping", ver, key);
                    }
                }
            }
        }

        if (pschFound && (mouflonVer.isEmpty() || mouflonKey.isEmpty())) {
            throw new IOException("Corruption scheme found but none is supported. Recording will not work");
        }
        
        List<StreamSource> sources = new ArrayList<>();
        for (PlaylistData playlist : masterPlaylist.getPlaylists()) {
            if (playlist.hasStreamInfo()) {
                StreamSource src = new StreamSource();
                src.setBandwidth(playlist.getStreamInfo().getBandwidth());
                src.setHeight(playlist.getStreamInfo().getResolution().height);
                src.setWidth(playlist.getStreamInfo().getResolution().width);
                src.setMediaPlaylistUrl(playlist.getUri());
                // if (src.getMediaPlaylistUrl().contains("?")) {
                //     src.setMediaPlaylistUrl(src.getMediaPlaylistUrl().substring(0, src.getMediaPlaylistUrl().lastIndexOf('?')));
                // }
                
                if (!mouflonVer.isEmpty() && !mouflonKey.isEmpty()) {
                    src.setMediaPlaylistUrl(
                        src.getMediaPlaylistUrl() 
                        + ( src.getMediaPlaylistUrl().contains("?") ? "&" : "?" )
                        + "psch=" + mouflonVer + "&pkey=" + mouflonKey
                    );
                }
                
                log.trace("Media playlist {}", src.getMediaPlaylistUrl());
                sources.add(src);
            }
        }
        return sources;
    }

    private MasterPlaylist getMasterPlaylist(String url) throws IOException, ParseException, PlaylistException {
        log.trace("Loading master playlist {}", url);
        Request req = new Request.Builder()
                .url(url)
                .header(USER_AGENT, Config.getInstance().getSettings().httpUserAgent)
                .build();
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                String body = response.body().string();
                log.trace(body);
                InputStream inputStream = new ByteArrayInputStream(Stripchat.PREPROCESSOR.process(body).getBytes(UTF_8));
                PlaylistParser parser = new PlaylistParser(inputStream, Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT);
                Playlist playlist = parser.parse();
                MasterPlaylist master = playlist.getMasterPlaylist();
                return master;
            } else {
                throw new HttpException(response.code(), response.message());
            }
        }
    }
    
    private String getHlsUrlTemplate() throws IOException {
        log.trace("Loading URL template for {}", getName());
        Request req = new Request.Builder()
                .url(getSite().getBaseUrl() + "/api/front/v3/config/initial?requestPath=%2F" + getName() + "&timezoneOffset=0")
                .header(USER_AGENT, Config.getInstance().getSettings().httpUserAgent)
                .build();
        try (Response response = getSite().getHttpClient().execute(req)) {
            if (response.isSuccessful()) {
                JSONObject jsonResponse = new JSONObject(response.body().string());
                var common = jsonResponse.getJSONObject("initial").getJSONObject("common");
                var urlTemplate = common.getString("hlsStreamUrlTemplate");
                var healthCheckUrlTemplate = common.optString("hlsHealthCheckUrlTemplate", common.optString("hlsHealtchCheckUrlTemplate", "")); // typo is in original json
                var host = common.getString("hlsStreamHost");
                
                var stripchat = (Stripchat)getSite();
                var hlsFallback = stripchat.getStaticInfo().featureSettings.hlsFallback;
                
                // select a fallback domain if given one is unhealthy
                if (healthCheckUrlTemplate.length() != 0 && !stripchat.isHealthy(healthCheckUrlTemplate.replace("{cdnHost}", host)) && hlsFallback.isEnabled) {
                    boolean selected = false;
                    
                    for (var fbHost : hlsFallback.fallbackDomains) {
                        log.debug("Trying fallback domain {}...", fbHost);
                        if (stripchat.isHealthy(healthCheckUrlTemplate.replace("{cdnHost}", fbHost))) {
                            host = fbHost;
                            selected = true;
                            break;
                        }
                    }
                    
                    if (!selected)
                        throw new IOException("No healthy CDN available");
                }
                                
                return urlTemplate.replace("{cdnHost}", host);
            } else {
                throw new HttpException(response.code(), response.message());
            }
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    private String getMasterPlaylistUrl() throws IOException {
        JSONObject info = getModelInfo();
        if (info.has("user")) {
            JSONObject user = info.getJSONObject("user").getJSONObject("user");
            long id = user.optLong("id");

            boolean saveVR = Config.getInstance().getSettings().stripchatVR;
            boolean isVRStream = user.optBoolean("isVr", false);
            String vrSuffix = (saveVR && isVRStream) ? "_vr" : "";

            String token = "";
            if (info.has("cam")) {
                JSONObject cam = info.getJSONObject("cam");
                if (StringUtil.isNotBlank(cam.optString(KEY_MODEL_TOKEN))) {
                    token = "&aclAuth=" + cam.getString(KEY_MODEL_TOKEN);
                    log.debug("Spy start for {}", getName());
                }
            }
            
            // the master playlist host can vary per model
            String hlsUrlTemplate = getHlsUrlTemplate();
            Map<String, Object> params = new HashMap<>();
            params.put("streamName", String.valueOf(id) + vrSuffix);
            params.put("suffix", "_auto");
            
            var result = StringSubstitutor.replace(hlsUrlTemplate, params, "{", "}") + "?playlistType=Standart" + token;
            return result;
        } else {
            throw new IOException("Playlist URL not found");
        }
    }

    @Override
    public void invalidateCacheEntries() {
        resolution = new int[]{0, 0};
        lastInfoRequest = Instant.EPOCH;
        modelInfo = null;
    }

    @Override
    public void receiveTip(Double tokens) throws IOException {
        // not implemented
    }

    @Override
    public int[] getStreamResolution(boolean failFast) throws ExecutionException {
        if (!failFast) {
            try {
                List<StreamSource> sources = getStreamSources();
                if (!sources.isEmpty()) {
                    StreamSource best = sources.getLast();
                    resolution = new int[]{best.getWidth(), best.getHeight()};
                }
            } catch (IOException | ParseException | PlaylistException e) {
                throw new ExecutionException(e);
            }
        }
        return resolution;
    }

    @Override
    public boolean follow() throws IOException {
        StripchatHttpClient client = (StripchatHttpClient) getSite().getHttpClient();
        client.login();
        JSONObject info = getModelInfo();
        JSONObject user = info.getJSONObject("user").getJSONObject("user");
        long id = user.optLong("id");

        String url = Stripchat.getBaseUri() + "/api/front/users/" + client.getUserId() + "/favorites/" + id;
        JSONObject requestParams = new JSONObject()
                .put("csrfToken", client.getCsrfToken())
                .put("csrfTimestamp", client.getCsrfTimestamp())
                .put("csrfNotifyTimestamp", client.getCsrfNotifyTimestamp())
                .put("uniq", getUniq())
                .put("ampl", client.getAmpl());
        RequestBody body = RequestBody.Companion.create(requestParams.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .header(ACCEPT, "*/*")
                .header(USER_AGENT, Config.getInstance().getSettings().httpUserAgent)
                .header(ORIGIN, Stripchat.getBaseUri())
                .header(REFERER, Stripchat.getBaseUri() + '/' + getName())
                .header(CONTENT_TYPE, MIMETYPE_APPLICATION_JSON)
                .put(body)
                .build();
        try (Response response = client.execute(request)) {
            if (response.isSuccessful()) {
                return true;
            } else {
                throw new HttpException(response.code(), response.message());
            }
        }
    }

    @Override
    public boolean unfollow() throws IOException {
        StripchatHttpClient client = (StripchatHttpClient) getSite().getHttpClient();
        client.login();
        JSONObject info = getModelInfo();
        JSONObject user = info.getJSONObject("user").getJSONObject("user");
        long id = user.optLong("id");
        JSONArray favoriteIds = new JSONArray().put(id);
        favoriteIds.put(id);

        String url = Stripchat.getBaseUri() + "/api/front/users/" + client.getUserId() + "/favorites";
        JSONObject requestParams = new JSONObject()
                .put("favoriteIds", favoriteIds)
                .put("csrfToken", client.getCsrfToken())
                .put("csrfTimestamp", client.getCsrfTimestamp())
                .put("csrfNotifyTimestamp", client.getCsrfNotifyTimestamp())
                .put("uniq", getUniq());
        RequestBody body = RequestBody.Companion.create(requestParams.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .header(ACCEPT, MIMETYPE_APPLICATION_JSON)
                .header(USER_AGENT, Config.getInstance().getSettings().httpUserAgent)
                .header(ORIGIN, Stripchat.getBaseUri())
                .header(REFERER, Stripchat.getBaseUri())
                .header(CONTENT_TYPE, MIMETYPE_APPLICATION_JSON)
                .delete(body)
                .build();
        try (Response response = client.execute(request)) {
            if (response.isSuccessful()) {
                return true;
            } else {
                throw new HttpException(response.code(), response.message());
            }
        }
    }

    @Override
    public boolean exists() throws IOException {
        JSONObject jsonResponse = getModelInfo();
        if (jsonResponse.optString("error").equals("Not Found")) {
            log.info("Model not found: {}", getName());
            return false;
        }
        if (jsonResponse.has("user")) {
            JSONObject user = jsonResponse.getJSONObject("user");
            if (isBanned(user)) {
                log.info("Model inactive or deleted: {}", getName());
                return false;
            }
        }
        return true;
    }

    @Override
    public RecordingProcess createDownload() {
        if (Config.getInstance().getSettings().useHlsdl) {
            return new HlsdlDownload();
        } else {
            var dl = new MergedFfmpegHlsDownload(getSite().getHttpClient());
            dl.setPlaylistPreprocessor(Stripchat.PREPROCESSOR);
            return dl;
        }
    }

    private String getUniq() {
        return ((Stripchat) getSite()).getUniq();
    }
}
