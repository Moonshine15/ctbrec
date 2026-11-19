package ctbrec.sites.chaturbate;

import ctbrec.Config;
import ctbrec.Model;
import ctbrec.OS;
import ctbrec.Recording;
import ctbrec.io.BandwidthMeter;
import ctbrec.io.HttpClient;
import ctbrec.io.HttpException;
import ctbrec.io.IoUtils;
import ctbrec.recorder.FFmpeg;
import ctbrec.recorder.download.AbstractDownload;
import ctbrec.recorder.download.HttpHeaderFactory;
import ctbrec.recorder.download.ProcessExitedUncleanException;
import ctbrec.recorder.download.StreamSource;
import ctbrec.recorder.download.hls.FfmpegOutputArgs;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static ctbrec.io.HttpConstants.ACCEPT;
import static ctbrec.io.HttpConstants.ACCEPT_LANGUAGE;
import static ctbrec.io.HttpConstants.CONNECTION;
import static ctbrec.io.HttpConstants.KEEP_ALIVE;
import static ctbrec.io.HttpConstants.ORIGIN;
import static ctbrec.io.HttpConstants.REFERER;
import static ctbrec.io.HttpConstants.USER_AGENT;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ChaturbateLlhlsDownload extends AbstractDownload {

    private static final Logger LOG = LoggerFactory.getLogger(ChaturbateLlhlsDownload.class);
    private static final int LIVE_EDGE_DELAY_SEGMENTS = 2;
    private static final int MAX_SECONDS_WITHOUT_TRANSFER = 30;
    private static final int STARTUP_PLAYLIST_READINESS_ATTEMPTS = 5;
    private static final long STARTUP_PLAYLIST_READINESS_DELAY_MS = 1000;
    private static final int FFMPEG_GRACEFUL_STOP_WAIT_SECONDS = 5;
    private static final int FFMPEG_TERMINATE_WAIT_SECONDS = 2;
    private static final int FFMPEG_FORCE_TERMINATE_WAIT_SECONDS = 2;

    private final HttpClient httpClient;
    private final boolean outputAsPlaylist;
    // Coordinates local workspace mutation against stop/finalization cleanup.
    private final Object workspaceLifecycleLock = new Object();
    private ChaturbateStreamSource selectedStreamSource;

    private volatile boolean running = true;
    private boolean preserveWorkspace;
    private boolean stopCompleted;
    private Instant lastTransfer = Instant.MIN;
    private int nextVideoSequence;
    private int nextAudioSequence;

    private File targetFile;
    private Path downloadDir;
    private Path workspaceDir;
    private IncompleteHolder videoIncompleteDownloads = new IncompleteHolder();
    private IncompleteHolder audioIncompleteDownloads = new IncompleteHolder();
    private ChaturbateLlhlsInMemoryWorkspace videoWorkspace;
    private ChaturbateLlhlsInMemoryWorkspace audioWorkspace;
    private ChaturbateLlhlsMediaServer mediaServer;
    private ChaturbateLlhlsInputAlignment inputAlignment = ChaturbateLlhlsInputAlignment.none();

    private FFmpeg ffmpeg;
    private Process ffmpegProcess;
    private OutputStream ffmpegStdIn;
    
    // Gets the value from future and throws original exception on error
    // instead of ExecutionException wrapper
    private static <T> T unwrapFuture(Future<T> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof Exception) {
                throw (Exception)ee.getCause();
            } else {
                throw ee;
            }
        }
    }
    
    // Stores incomplete requests between iterations
    private static class IncompleteHolder {
        List<FetchResult> segFutures = new ArrayList<>();
        Map<String, Future<byte[]>> mapFutures = new HashMap<>();
    }
    
    
    public String getStats() {
        String text = (running ? "RUN" : "stp") 
                    + String.format(" lag=%6dms seq=%06d/%06d", 
                                    Duration.between(lastTransfer, Instant.now()).toMillis(),
                                    nextVideoSequence, nextAudioSequence);
        text += " VMaps: ";
        for (var elem : videoIncompleteDownloads.mapFutures.values()) {
            text += elem.isDone() ? "|" : "-";
        }
        text += " VSegs: ";
        for (var elem : videoIncompleteDownloads.segFutures) {
            text += elem.data.isDone() ? "|" : "-";
        }
        text += " AMaps: ";
        for (var elem : audioIncompleteDownloads.mapFutures.values()) {
            text += elem.isDone() ? "|" : "-";
        }
        text += " ASegs: ";
        for (var elem : audioIncompleteDownloads.segFutures) {
            text += elem.data.isDone() ? "|" : "-";
        }
        return text;
    }

    public ChaturbateLlhlsDownload(HttpClient httpClient, boolean outputAsPlaylist, ChaturbateStreamSource selectedStreamSource) {
        this.httpClient = httpClient;
        this.outputAsPlaylist = outputAsPlaylist;
        this.selectedStreamSource = selectedStreamSource;
    }

    @Override
    public void init(Config config, Model model, Instant startTime, ExecutorService executorService) throws IOException {
        super.init(config, model, startTime, executorService);
        if (!(model instanceof ChaturbateModel)) {
            throw new IOException("Expected ChaturbateModel but got " + model.getClass().getSimpleName());
        }
    
        initializePaths();
        initializeWorkspace();
    }
    
    void initAsContinuationOf(ChaturbateLlhlsDownload previous, ChaturbateLlhlsMediaPlaylist videoPlaylist, ChaturbateLlhlsMediaPlaylist audioPlaylist) throws IOException {
        init(previous.config, previous.model, Instant.now(), previous.downloadExecutor);        
        LOG.debug("Starting as continuation for {} at {}/{}", model, previous.nextVideoSequence, previous.nextAudioSequence);
    
        selectedStreamSource = previous.selectedStreamSource;
        selectedResolution = selectedStreamSource.getHeight();
        try {
            captureStartupWindow(videoPlaylist, audioPlaylist, new ChaturbateLlhlsStartPosition(previous.nextVideoSequence, previous.nextAudioSequence, true));
            // Ensure we have written something to the workspaces before calling determineInputAlignment()
            storeRemainingSegments(videoIncompleteDownloads, videoWorkspace);
            storeRemainingSegments(audioIncompleteDownloads, audioWorkspace);
            determineInputAlignment();
        } catch (Exception e) {
            // preserveWorkspace = true;
            throw new IOException("Couldn't initialize Chaturbate LL-HLS startup state for " + model, e);
        }
        startFfmpegProcess();
        lastTransfer = Instant.now();
        rescheduleTime = Instant.now().plusMillis(calculatePollDelayMs(videoPlaylist, audioPlaylist, true));
    }

    @Override
    public ChaturbateLlhlsDownload call() throws Exception {
        if (!running) {
            rescheduleTime = Instant.now();
            return this;
        }
        
        // we can't do long operations in init() as it's called under a global recorder lock
        if (ffmpegProcess == null) {
            selectedStreamSource = ((ChaturbateModel)model).refreshSelectedStreamSource(selectedStreamSource);
            selectedResolution = selectedStreamSource.getHeight();
            try {
                captureStartupWindow();
                // Ensure we have written something to the workspaces before calling determineInputAlignment()
                storeRemainingSegments(videoIncompleteDownloads, videoWorkspace);
                storeRemainingSegments(audioIncompleteDownloads, audioWorkspace);
                determineInputAlignment();
            } catch (Exception e) {
                // preserveWorkspace = true;
                throw new IOException("Couldn't initialize Chaturbate LL-HLS startup state for " + model, e);
            }
            startFfmpegProcess();
            lastTransfer = Instant.now();
        }

        if (ffmpegProcess != null && !ffmpegProcess.isAlive()) {
            LOG.debug("FFmpeg suddenly died for {} {}", model, ffmpegProcess.getErrorStream().readAllBytes());
            
            int exitCode = ffmpegProcess.exitValue();
            if (exitCode != 0) {
                preserveWorkspace = true;
                LOG.error("FFmpeg exited with code {} for {}", exitCode, model);
            }
            internalStop();
            return this;
        }

        try {
            if (!model.isOnline()) {
                LOG.debug("Model {} not online. Stop recording.", model);
                internalStop();
                return this;
            }

            var videoPlaylistFut = downloadExecutor.submit(() -> loadMediaPlaylist(selectedStreamSource.getVideoPlaylistUrl()));
            var audioPlaylistFut = downloadExecutor.submit(() -> loadMediaPlaylist(selectedStreamSource.getAudioPlaylistUrl()));
            ChaturbateLlhlsMediaPlaylist videoPlaylist = unwrapFuture(videoPlaylistFut);
            ChaturbateLlhlsMediaPlaylist audioPlaylist = unwrapFuture(audioPlaylistFut);
            
            // split after getting the playlists. Traffic attribution does not care about where we do this
            // Errors should not affect us either as any error would affect the continuation just as much (TODO: test this)
            if (splittingStrategy.splitNecessary(this)) {
                LOG.debug("Split necessary for model {}", model);                
                var dl = new ChaturbateLlhlsDownload(httpClient, outputAsPlaylist, selectedStreamSource);
                dl.initAsContinuationOf(this, videoPlaylist, audioPlaylist);                
                internalStop();
                return dl;
            }
            
            // check playlists before processing them (and modifying object state) that we have new segments
            // by the spec this needs to be independent, per playlist. But as we process them here in combined fashion, simply use OR (this is incorrect in fact)
            // (the spec requires us to wait 1*targetDuration if the playlist changed before requesting it again, and 0.5*targetDuration if it did not)
            var gotNewSegments = hasNewSegments(videoPlaylist, nextVideoSequence) || hasNewSegments(audioPlaylist, nextAudioSequence);
            
            var continuation = captureAvailableSegments(videoPlaylist, audioPlaylist);
            
            if (continuation != null) {
                internalStop();
                return continuation;
            }
            
            if (!running || !isStillRunning()) {
                rescheduleTime = Instant.now();
                return this;
            }
            rescheduleTime = Instant.now().plusMillis(calculatePollDelayMs(videoPlaylist, audioPlaylist, gotNewSegments));
        } catch (SessionRefreshRequiredException e) {
            handleSessionRefreshRequired(e);
        } catch (Exception e) {
            LOG.error("Chaturbate LL-HLS capture failed for {}", model, e);
            // preserveWorkspace = true;
            model.delay();
            internalStop();
        }

        if (Duration.between(lastTransfer, Instant.now()).getSeconds() > MAX_SECONDS_WITHOUT_TRANSFER) {
            LOG.info("No LL-HLS media received for {} seconds. Stopping recording for {}", MAX_SECONDS_WITHOUT_TRANSFER, model);
            // preserveWorkspace = true;
            internalStop();
        }

        return this;
    }
    
    protected boolean hasNewSegments(ChaturbateLlhlsMediaPlaylist playlist, int currentSequenceNumber) {
        return playlist.mediaSequence() + playlist.segments().size() > currentSequenceNumber;
    }

    @Override
    public void stop() {
        if (running || !stopCompleted) {
            internalStop();
        }
    }

    @Override
    public void finalizeDownload() {
        internalStop();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public File getTarget() {
        return outputAsPlaylist ? downloadDir.toFile() : targetFile;
    }

    @Override
    public String getPath(Model model) {
        String absolutePath = getTarget().getAbsolutePath();
        String recordingsDir = Config.getInstance().getSettings().recordingsDir;
        return absolutePath.replaceFirst(Pattern.quote(recordingsDir), "");
    }

    @Override
    public boolean isSingleFile() {
        return !outputAsPlaylist;
    }

    @Override
    public boolean isFailedCapture() {
        return preserveWorkspace;
    }

    @Override
    public Optional<StreamSource> getLivePlaybackStreamSource() {
        if (mediaServer == null) {
            return Optional.empty();
        }

        StreamSource playbackSource = new StreamSource();
        playbackSource.setBandwidth(selectedStreamSource != null ? selectedStreamSource.getBandwidth() : StreamSource.UNKNOWN);
        playbackSource.setWidth(selectedStreamSource != null ? selectedStreamSource.getWidth() : StreamSource.UNKNOWN);
        playbackSource.setHeight(selectedStreamSource != null ? selectedStreamSource.getHeight() : StreamSource.UNKNOWN);
        playbackSource.setMediaPlaylistUrl(mediaServer.getMasterPlaylistUrl());
        playbackSource.setMasterPlaylistUrl(mediaServer.getMasterPlaylistUrl());
        return Optional.of(playbackSource);
    }

    @Override
    public Set<File> getManagedArtifacts() {
        return workspaceDir != null ? Set.of(workspaceDir.toFile()) : Set.of();
    }

    @Override
    public long getSizeInByte() {
        long size = 0;
        File target = getTarget();
        if (target != null && target.exists()) {
            size += target.isDirectory() ? IoUtils.getDirectorySize(target) : target.length();
        }
        if (preserveWorkspace && workspaceDir != null && Files.exists(workspaceDir)) {
            size += IoUtils.getDirectorySize(workspaceDir.toFile());
        }
        return size;
    }

    private void initializePaths() throws IOException {
        if (outputAsPlaylist) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Config.RECORDING_DATE_FORMAT);
            String formattedStartTime = formatter.format(ZonedDateTime.ofInstant(this.startTime, ZoneId.systemDefault()));
            Path modelDir = FileSystems.getDefault().getPath(config.getSettings().recordingsDir, model.getSanitizedNamed());
            downloadDir = FileSystems.getDefault().getPath(modelDir.toString(), formattedStartTime);
            workspaceDir = downloadDir.resolveSibling(downloadDir.getFileName() + ".llhls-work");
            Files.createDirectories(downloadDir);
        } else {
            String fileSuffix = config.getSettings().ffmpegFileSuffix;
            targetFile = config.getFileForRecording(model, fileSuffix, startTime);
            Files.createDirectories(targetFile.getParentFile().toPath());
            workspaceDir = targetFile.toPath().getParent().resolve(targetFile.getName() + ".llhls-work");
        }
    }

    private void initializeWorkspace() throws IOException {
        videoWorkspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        audioWorkspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        mediaServer = new ChaturbateLlhlsMediaServer(videoWorkspace, audioWorkspace);
        mediaServer.start();
    }
    
    private record BothPlaylists(ChaturbateLlhlsMediaPlaylist videoPlaylist, ChaturbateLlhlsMediaPlaylist audioPlaylist){}
    
    private BothPlaylists captureBothPlaylists() throws Exception {
        for (int attempt = 1; attempt <= STARTUP_PLAYLIST_READINESS_ATTEMPTS; attempt++) {
            if (!running) {
                throw new IOException("Chaturbate LL-HLS startup was interrupted for " + model);
            }
            
            var videoPlaylist = loadMediaPlaylist(selectedStreamSource.getVideoPlaylistUrl());
            var audioPlaylist = loadMediaPlaylist(selectedStreamSource.getAudioPlaylistUrl());
            
            if (!videoPlaylist.isEmpty() && !audioPlaylist.isEmpty()) {
                return new BothPlaylists(videoPlaylist, audioPlaylist);
            }
            
            if (attempt < STARTUP_PLAYLIST_READINESS_ATTEMPTS) {            
                LOG.info("Chaturbate LL-HLS playlists not ready yet for {} (attempt {}/{}), waiting...",
                        model.getName(), attempt, STARTUP_PLAYLIST_READINESS_ATTEMPTS);
                Thread.sleep(STARTUP_PLAYLIST_READINESS_DELAY_MS);
            }
        }
        
        throw new IOException("Chaturbate LL-HLS playlists have no complete segments after "
                + STARTUP_PLAYLIST_READINESS_ATTEMPTS + " attempts for " + model);
    }

    private void captureStartupWindow() throws Exception {
        BothPlaylists both = captureBothPlaylists();
        
        // FIXME: we are potentially skipping important content here, as the start position is 
        // offset from the end of playlist, and it can have more segments than the offset 
        ChaturbateLlhlsStartPosition startPosition = ChaturbateLlhlsStartupStrategy.chooseStartPosition(both.videoPlaylist, both.audioPlaylist, LIVE_EDGE_DELAY_SEGMENTS);
        
        captureStartupWindow(both.videoPlaylist, both.audioPlaylist, startPosition);
    }
    
    private void captureStartupWindow(ChaturbateLlhlsMediaPlaylist videoPlaylist, 
                                        ChaturbateLlhlsMediaPlaylist audioPlaylist, 
                                        ChaturbateLlhlsStartPosition startPosition) throws Exception {
        LOG.info("Chaturbate LL-HLS startup for {}: video seq {}, audio seq {}, alignedByProgramDateTime={}",
                model.getName(), startPosition.videoSequence(), startPosition.audioSequence(), startPosition.alignedByProgramDateTime());
                
        var v = downloadExecutor.submit(() -> captureNewSegments(videoPlaylist, startPosition.videoSequence(), videoWorkspace, videoIncompleteDownloads));
        var a = downloadExecutor.submit(() -> captureNewSegments(audioPlaylist, startPosition.audioSequence(), audioWorkspace, audioIncompleteDownloads));
        try {
            nextVideoSequence = unwrapFuture(v);
            nextAudioSequence = unwrapFuture(a);
        } finally {
            v.cancel(true);
            a.cancel(true);
        }
        
        if (!isStillRunning()) {
            throw new IOException("Chaturbate LL-HLS startup was interrupted for " + model);
        }
    }
    
    
    private ChaturbateLlhlsDownload captureAvailableSegments(ChaturbateLlhlsMediaPlaylist videoPlaylist,
                                          ChaturbateLlhlsMediaPlaylist audioPlaylist) throws Exception {
        var gotNewSegments = false;
        
        try {
            verifyLiveWindow("video", nextVideoSequence, videoPlaylist);
            verifyLiveWindow("audio", nextAudioSequence, audioPlaylist);
            
            var v = downloadExecutor.submit(() -> captureNewSegments(videoPlaylist, nextVideoSequence, videoWorkspace, videoIncompleteDownloads));
            var a = downloadExecutor.submit(() -> captureNewSegments(audioPlaylist, nextAudioSequence, audioWorkspace, audioIncompleteDownloads));
            try {
                gotNewSegments = nextVideoSequence != unwrapFuture(v) || nextAudioSequence != unwrapFuture(a);
                nextVideoSequence = unwrapFuture(v);
                nextAudioSequence = unwrapFuture(a);
            } finally {
                v.cancel(true);
                a.cancel(true);
            }
        } catch (RecordingSplitRequiredException es) {
            LOG.debug(es.getMessage());
            var dl = new ChaturbateLlhlsDownload(httpClient, outputAsPlaylist, selectedStreamSource);
            dl.initAsContinuationOf(this, videoPlaylist, audioPlaylist);                     
            return dl;
        }
        
        return null;
    }
    
    record FetchResult(ChaturbateLlhlsSegment segment, Future<byte[]> data) {} 

    private int captureNewSegments(ChaturbateLlhlsMediaPlaylist playlist,
                                   int nextSequence,
                                   ChaturbateLlhlsInMemoryWorkspace workspace,
                                   IncompleteHolder incompletes) throws Exception {
        workspace.updateTargetDuration(playlist.targetDuration());
        int updatedSequence = nextSequence;
        
        // create requests to run in parallel
        // assume we are backed by virtual threads and run them as basic sequential code (if not then the logic around Request class has to be redone)
        for (ChaturbateLlhlsSegment segment : playlist.segments()) {
            if (segment.sequence() < nextSequence) {
                continue;
            }
            
            if (!workspace.hasInitSegment(segment.mapUrl()) && !incompletes.mapFutures.containsKey(segment.mapUrl())) {
                incompletes.mapFutures.put(segment.mapUrl(), downloadExecutor.submit(() -> fetchBytes(segment.mapUrl(), true)));
            }
            
            incompletes.segFutures.add(new FetchResult(segment, downloadExecutor.submit(() -> fetchBytes(segment.url(), false))));
        }
        
        if (!playlist.segments().isEmpty())
            updatedSequence = playlist.segments().getLast().sequence()+1;

        // store complete segments (possibly from previous invocations) in proper order
        while (!incompletes.segFutures.isEmpty()) {
            var fetchedSegment = incompletes.segFutures.getFirst();
            if (!fetchedSegment.data.isDone()) break;
            
            var mapFut = incompletes.mapFutures.get(fetchedSegment.segment.mapUrl());
            if (mapFut != null && !mapFut.isDone()) break;                
            var mapData = mapFut != null ? unwrapFuture(mapFut) : null;
            
            applyFetchedSegmentIfRunning(workspace, fetchedSegment.segment, mapData, unwrapFuture(fetchedSegment.data));
            
            // remove written
            incompletes.segFutures.removeFirst();
            if (mapFut != null) {
                incompletes.mapFutures.remove(fetchedSegment.segment.mapUrl());
            }
        }
        
        return updatedSequence;
    }


    boolean applyFetchedSegmentIfRunning(ChaturbateLlhlsInMemoryWorkspace workspace,
                                         ChaturbateLlhlsSegment segment,
                                         byte[] initData,
                                         byte[] segmentData) {
        synchronized (workspaceLifecycleLock) {
            if (!running) {
                return false;
            }
            if (initData != null && !workspace.hasInitSegment(segment.mapUrl())) {
                workspace.storeInitSegment(segment.mapUrl(), initData);
            }
            workspace.appendSegment(segment, segmentData);
            lastTransfer = Instant.now();
            return true;
        }
    }

    /**
     * Check if the recording is still running. Playlists are generated
     * on-the-fly by the media server, so no disk writes are needed.
     */
    boolean isStillRunning() {
        synchronized (workspaceLifecycleLock) {
            return running;
        }
    }

    private void verifyLiveWindow(String trackName, int nextSequence, ChaturbateLlhlsMediaPlaylist playlist) throws IOException {
        if (!playlist.isEmpty() && nextSequence < playlist.mediaSequence()) {
            throw new RecordingSplitRequiredException("Detected a " + trackName + " playlist gap for " + model + ": expected seq "
                    + nextSequence + " but live window starts at " + playlist.mediaSequence());
        }
    }

    private ChaturbateLlhlsMediaPlaylist loadMediaPlaylist(String playlistUrl) throws Exception {
        String body = fetchPlaylistBody(playlistUrl);
        ChaturbateLlhlsMediaPlaylist playlist = ChaturbateLlhlsPlaylistParser.parse(playlistUrl, body);
        if (playlist.isEmpty()) {
            LOG.debug("Chaturbate LL-HLS playlist has no complete segments yet for {}", playlistUrl);
        }
        return playlist;
    }

    private String fetchPlaylistBody(String playlistUrl) throws Exception {
        Request request = addHeaders(new Request.Builder().url(playlistUrl),
                Optional.ofNullable(model.getHttpHeaderFactory()).map(HttpHeaderFactory::createSegmentPlaylistHeaders).orElse(Map.of()))
                .build();
        try (Response response = httpClient.execute(request, config.getSettings().playlistRequestTimeout)) {
            if (response.isSuccessful()) {
                var body = response.body().string();
                BandwidthMeter.add(body.length());
                return body;
            }
            if (response.code() == 403 || response.code() == 404) {
                throw new SessionRefreshRequiredException(response.code() + " while fetching playlist " + playlistUrl);
            }
            throw new HttpException(response.code(), response.message());
        }
    }

    private byte[] fetchBytes(String url, boolean initSegment) throws Exception {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request request = addHeaders(new Request.Builder().url(url),
                    Optional.ofNullable(model.getHttpHeaderFactory()).map(HttpHeaderFactory::createSegmentHeaders).orElse(Map.of()))
                    .build();
            try (Response response = httpClient.execute(request, config.getSettings().httpTimeout)) {
                if (response.isSuccessful()) {
                    var body = response.body().bytes();
                    BandwidthMeter.add(body.length);
                    return body;
                }
                if (response.code() == 403 || response.code() == 404) {
                    throw new SessionRefreshRequiredException(response.code() + " while fetching " + (initSegment ? "init" : "segment") + ' ' + url);
                }
                if (attempt == maxAttempts) {
                    throw new HttpException(response.code(), response.message());
                }
            } catch (SessionRefreshRequiredException e) {
                throw e;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw e;
                }
                LOG.debug("Retrying {} download for {} (attempt {}/{})", initSegment ? "init segment" : "segment", model, attempt + 1, maxAttempts);
            }
        }
        throw new IOException("Couldn't fetch " + url);
    }

    private Request.Builder addHeaders(Request.Builder builder, Map<String, String> headers) {
        var baseUrl = Optional.ofNullable(model).map(Model::getSite).map(site -> site.getBaseUrl()).orElse(null);
        Map<String, String> resolvedHeaders = new HashMap<>(headers);
        resolvedHeaders.putIfAbsent(ACCEPT, "*/*");
        resolvedHeaders.putIfAbsent(ACCEPT_LANGUAGE, Locale.ENGLISH.getLanguage());
        resolvedHeaders.putIfAbsent(USER_AGENT, httpClient.getEffectiveUserAgent(URI.create(baseUrl).getHost()));
        resolvedHeaders.putIfAbsent(CONNECTION, KEEP_ALIVE);
        resolvedHeaders.computeIfAbsent(ORIGIN, k -> baseUrl);
        resolvedHeaders.computeIfAbsent(REFERER, k -> baseUrl);
        resolvedHeaders.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .forEach(entry -> builder.header(entry.getKey(), entry.getValue()));
        return builder;
    }

    void refreshSelectedStreamSource() throws IOException {
        selectedStreamSource = ((ChaturbateModel) model).refreshSelectedStreamSource(selectedStreamSource);
        LOG.info("Refreshed Chaturbate LL-HLS URLs for {}: video {}, audio {}", model.getName(),
                selectedStreamSource.getVideoPlaylistUrl(), selectedStreamSource.getAudioPlaylistUrl());
    }

    void handleSessionRefreshRequired(IOException cause) {
        try {
            refreshSelectedStreamSource();
            rescheduleTime = Instant.now();
        } catch (IOException refreshError) {
            Model.State modelState = resolveModelState();
            if (modelState != Model.State.ONLINE) {
                LOG.info("Couldn't refresh Chaturbate LL-HLS URLs for {} after {}. Model state: {}. Stopping recording.",
                        model, cause.getMessage(), modelState, refreshError);
                internalStop();
                return;
            }

            // preserveWorkspace = true;
            LOG.error("Couldn't refresh Chaturbate LL-HLS URLs for {} while model still appears online", model, refreshError);
            model.delay();
            internalStop();
        }
    }

    private long calculatePollDelayMs(ChaturbateLlhlsMediaPlaylist videoPlaylist, ChaturbateLlhlsMediaPlaylist audioPlaylist, boolean hasNewSegments) {
        int targetDuration = Math.max(1, Math.min(videoPlaylist.targetDuration(), audioPlaylist.targetDuration()));
        return Math.max(500L, targetDuration * (hasNewSegments ? 1000L : 500L));
    }

    private Model.State resolveModelState() {
        try {
            return model.updateOnlineState(false);
        } catch (Exception e) {
            LOG.debug("Couldn't resolve model state while handling LL-HLS session refresh for {}", model, e);
            return Model.State.UNKNOWN;
        }
    }

    private void startFfmpegProcess() throws IOException {
        List<String> args = new ArrayList<>(FfmpegOutputArgs.buildPairInputArguments(
                mediaServer.getVideoPlaylistUrl(), inputAlignment.videoInputDelay(),
                mediaServer.getAudioPlaylistUrl(), inputAlignment.audioInputDelay()));
        if (outputAsPlaylist) {
            args.addAll(FfmpegOutputArgs.buildPlaylistOutputArguments(downloadDir));
        } else {
            List<String> strippedFlags = new ArrayList<>();
            args.addAll(FfmpegOutputArgs.buildSingleFileOutputArguments(config.getSettings().ffmpegMergedDownloadArgs, targetFile, strippedFlags));
            if (!strippedFlags.isEmpty()) {
                LOG.warn("Ignoring incompatible FFmpeg flags for Chaturbate LL-HLS local muxing: {}", strippedFlags);
            }
        }

        String[] cmdline = OS.getFFmpegCommand(args.toArray(String[]::new));
        try {
            ffmpeg = new FFmpeg.Builder()
                    .logOutput(config.getSettings().logFFmpegOutput)
                    .onStarted(process -> {
                        ffmpegProcess = process;
                        ffmpegStdIn = process.getOutputStream();
                    })
                    .build();
            File executionDir = outputAsPlaylist ? downloadDir.toFile() : targetFile.getParentFile();
            ffmpeg.exec(cmdline, new String[0], executionDir);
        } catch (ProcessExitedUncleanException e) {
            throw new IOException("Couldn't start FFmpeg for " + model, e);
        }
    }
    
    private void storeRemainingSegments(IncompleteHolder incompletes, ChaturbateLlhlsInMemoryWorkspace workspace) {
        int numStored = 0;
        for (var fetchedSegment : incompletes.segFutures) {            
            try {
                var mapFut = incompletes.mapFutures.get(fetchedSegment.segment.mapUrl());
                var mapData = mapFut != null ? unwrapFuture(mapFut) : null;
                
                applyFetchedSegmentIfRunning(workspace, fetchedSegment.segment, mapData, unwrapFuture(fetchedSegment.data));
                numStored++;
            } catch (Exception e) {
                LOG.error("Error storing remaining segment {}/{} for {}", numStored, incompletes.segFutures.size(), model, e);
            }
        }
        
        incompletes.segFutures.clear();
        incompletes.mapFutures.clear();
    }

    private void internalStop() {
        synchronized (workspaceLifecycleLock) {
            if (stopCompleted) {
                return;
            }
            
            // dump anything remaining in hopes to loose as little data as possible (TODO test this properly)
            if (videoWorkspace != null) {
                storeRemainingSegments(videoIncompleteDownloads, videoWorkspace);
            }
            if (audioWorkspace != null) {
                storeRemainingSegments(audioIncompleteDownloads, audioWorkspace);
            }
            
            stopCompleted = true;
            running = false;

            if (videoWorkspace != null) {
                videoWorkspace.markComplete();
            }
            if (audioWorkspace != null) {
                audioWorkspace.markComplete();
            }
        }

        waitForFfmpegToExit();
        cleanupWorkspaceIfNeeded();
        rescheduleTime = Instant.now();
    }

    private void waitForFfmpegToExit() {
        Process process = ffmpegProcess;
        if (process == null) {
            return;
        }

        try {
            requestFfmpegStop(process);
            boolean terminated = !process.isAlive()
                    || process.waitFor(FFMPEG_GRACEFUL_STOP_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!terminated && process.isAlive()) {
                process.destroy();
                terminated = process.waitFor(FFMPEG_TERMINATE_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            if (!terminated && process.isAlive()) {
                process.destroyForcibly();
                terminated = process.waitFor(FFMPEG_FORCE_TERMINATE_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            if (!terminated) {
                // preserveWorkspace = true;
                LOG.error("FFmpeg is still alive after forced shutdown for {}", model);
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                // preserveWorkspace = true;
                LOG.error("FFmpeg exited with code {} for {}", exitCode, model);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // preserveWorkspace = true;
            LOG.error("Interrupted while waiting for FFmpeg to exit for {}", model, e);
        } finally {
            closeFfmpegControlStream(process);
            ffmpegProcess = null;
        }
    }

    private void requestFfmpegStop(Process process) {
        if (ffmpegStdIn == null) {
            return;
        }
        if (!process.isAlive()) {
            closeFfmpegControlStream(process);
            return;
        }
        try {
            ffmpegStdIn.write("q\n".getBytes(UTF_8));
            ffmpegStdIn.flush();
        } catch (IOException e) {
            if (process.isAlive()) {
                LOG.debug("Couldn't send quit command to FFmpeg for {}", model, e);
            }
        } finally {
            closeFfmpegControlStream(process);
        }
    }

    private void closeFfmpegControlStream(Process process) {
        OutputStream controlStream = ffmpegStdIn;
        ffmpegStdIn = null;
        if (controlStream == null) {
            return;
        }
        try {
            controlStream.close();
        } catch (IOException e) {
            if (process == null || process.isAlive()) {
                LOG.debug("Couldn't close FFmpeg stdin for {}", model, e);
            }
        }
    }

    private void determineInputAlignment() {
        var videoStartTime = ChaturbateLlhlsPlaylistStartTimeDetector.detectStartTime(mediaServer.getVideoPlaylistUrl());
        var audioStartTime = ChaturbateLlhlsPlaylistStartTimeDetector.detectStartTime(mediaServer.getAudioPlaylistUrl());
        if (videoStartTime.isEmpty() || audioStartTime.isEmpty()) {
            LOG.warn("Couldn't determine LL-HLS local input start times for {}. Proceeding without startup input delay.", model.getName());
            inputAlignment = ChaturbateLlhlsInputAlignment.none();
            return;
        }

        inputAlignment = ChaturbateLlhlsInputAlignment.fromStartTimes(videoStartTime.getAsDouble(), audioStartTime.getAsDouble());
        if (inputAlignment.requiresAdjustment()) {
            LOG.info("Chaturbate LL-HLS local input start times for {}: video={}, audio={}. Applying FFmpeg input delay video={}, audio={}",
                    model.getName(),
                    formatSeconds(videoStartTime.getAsDouble()),
                    formatSeconds(audioStartTime.getAsDouble()),
                    formatDuration(inputAlignment.videoInputDelay()),
                    formatDuration(inputAlignment.audioInputDelay()));
        } else {
            LOG.info("Chaturbate LL-HLS local input start times for {}: video={}, audio={}. No FFmpeg input delay applied.",
                    model.getName(),
                    formatSeconds(videoStartTime.getAsDouble()),
                    formatSeconds(audioStartTime.getAsDouble()));
        }
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.US, "%.6f", seconds);
    }

    private static String formatDuration(Duration duration) {
        return String.format(Locale.US, "%.6f", duration.toNanos() / 1_000_000_000d);
    }

    private void cleanupWorkspaceIfNeeded() {
        if (preserveWorkspace) {
            dumpWorkspaceToDisk();
        }
        if (mediaServer != null) {
            mediaServer.stop();
            mediaServer = null;
        }
        if (videoWorkspace != null) {
            videoWorkspace.clear();
        }
        if (audioWorkspace != null) {
            audioWorkspace.clear();
        }
    }

    private void dumpWorkspaceToDisk() {
        if (workspaceDir == null) {
            LOG.warn("Couldn't preserve Chaturbate LL-HLS workspace for {} because no workspace path is available", model);
            return;
        }
        try {
            Files.createDirectories(workspaceDir);
            if (videoWorkspace != null) {
                videoWorkspace.dumpToDisk(workspaceDir);
            }
            if (audioWorkspace != null) {
                audioWorkspace.dumpToDisk(workspaceDir);
            }
            LOG.warn("Preserved Chaturbate LL-HLS in-memory workspace for {} at {}", model, workspaceDir);
        } catch (IOException e) {
            LOG.error("Couldn't dump LL-HLS in-memory workspace to disk for {}", model, e);
        }
    }

    private static final class SessionRefreshRequiredException extends IOException {
        private SessionRefreshRequiredException(String message) {
            super(message);
        }
    }

    private static final class RecordingSplitRequiredException extends IOException {
        private RecordingSplitRequiredException(String message) {
            super(message);
        }
    }
}
