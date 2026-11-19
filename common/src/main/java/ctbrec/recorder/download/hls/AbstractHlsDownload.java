package ctbrec.recorder.download.hls;

import com.iheartradio.m3u8.*;
import com.iheartradio.m3u8.data.EncryptionData;
import com.iheartradio.m3u8.data.MediaPlaylist;
import com.iheartradio.m3u8.data.Playlist;
import com.iheartradio.m3u8.data.TrackData;
import ctbrec.Config;
import ctbrec.Model;
import ctbrec.Model.State;
import ctbrec.StringUtil;
import ctbrec.io.BandwidthMeter;
import ctbrec.io.HttpClient;
import ctbrec.io.HttpConstants;
import ctbrec.io.HttpException;
import ctbrec.io.PlaylistPreprocessor;
import ctbrec.recorder.InvalidPlaylistException;
import ctbrec.recorder.SimplifiedLocalRecorder;
import ctbrec.recorder.download.AbstractDownload;
import ctbrec.recorder.download.HttpHeaderFactory;
import ctbrec.recorder.download.StreamSource;
import ctbrec.recorder.download.hls.SegmentPlaylist.Segment;
import ctbrec.sites.Site;
import lombok.Setter;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBException;
import java.io.*;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static ctbrec.io.HttpConstants.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

public abstract class AbstractHlsDownload extends AbstractDownload {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractHlsDownload.class);
    private static final int A_FEW_SECONDS = 10_000;
    private static final int MAX_SECONDS_WITHOUT_TRANSFER = 30;
    
    private static final PlaylistPreprocessor DEFAULT_PREPROCESSOR = new PlaylistPreprocessor();

    private final NumberFormat nf = new DecimalFormat("000000");
    private int playlistEmptyCount = 0;
    private int segmentCounter = 1;
    private int initCounter = 1;

    protected HttpClient client;
    protected volatile boolean running = true;

    protected int lastSegmentNumber = 0;
    protected int nextSegmentNumber = 0;
    protected String segmentPlaylistUrl;
    private String currentMapUrl;

    private Instant beforeLastPlaylistRequest = Instant.EPOCH;
    private int consecutivePlaylistTimeouts = 0;
    private int consecutivePlaylistErrors = 0;
    protected Instant lastSegmentDownload = Instant.MIN;
    private final List<Instant> segmentErrorTimestamps = new LinkedList<>();

    private final List<RecordingEvent> recordingEvents = new LinkedList<>();
    protected ExecutorCompletionService<SegmentDownload> segmentDownloadService;
    
    @Setter
    protected PlaylistPreprocessor playlistPreprocessor = DEFAULT_PREPROCESSOR;

    protected AbstractHlsDownload(HttpClient client) {
        this.client = client;
    }

    @Override
    public void init(Config config, Model model, Instant startTime, ExecutorService executorService) throws IOException {
        super.init(config, model, startTime, executorService);
        segmentDownloadService = new ExecutorCompletionService<>(downloadExecutor);
        lastSegmentDownload = Instant.now();
    }
    
    protected void initAsContinuation(AbstractHlsDownload other) throws IOException {
        init(other.config, other.model, Instant.now(), other.downloadExecutor);  // this calls the override if it exists
        nextSegmentNumber = other.nextSegmentNumber;
        playlistPreprocessor = other.playlistPreprocessor;
    }

    @Override
    public long getSizeInByte() {
        return getDownloadedBytes().get();
    }

    protected abstract OutputStream getSegmentOutputStream(Segment segment) throws IOException;

    protected void segmentDownloadFinished(SegmentDownload segmentDownload) { // NOSONAR
        getDownloadedBytes().addAndGet(segmentDownload.size());
        lastSegmentDownload = Instant.now();
    }

    protected void segmentDownloadFailed(SegmentDownload segmentDownload) {
        LOG.info("Segment download failed with", segmentDownload.getException());
        // Force media-playlist reselection (handles CDN/UUID rotation or short-window churn).
        segmentPlaylistUrl = null;
        rescheduleTime = java.time.Instant.now();
        stopRecordingOnHighSegmentErrorCount();
    }

    protected abstract AbstractHlsDownload createContinuation(SegmentPlaylist segmentPlaylist) throws Exception;
    protected abstract void internalStop();
    

    @Override
    public AbstractHlsDownload call() throws Exception {
        SimplifiedLocalRecorder.STATS.add(Duration.between(rescheduleTime, Instant.now()).toMillis());

        try {
            if (segmentPlaylistUrl == null) {
                segmentPlaylistUrl = getSegmentPlaylistUrl(model);
            }

            beforeLastPlaylistRequest = Instant.now();
            SegmentPlaylist segmentPlaylist = getNextSegments(segmentPlaylistUrl);
            emptyPlaylistCheck(segmentPlaylist);
            handleMissedSegments(segmentPlaylist, nextSegmentNumber);
            // Tail-safe: do not request segments older than the live window.
            if (nextSegmentNumber < segmentPlaylist.seq) {
                nextSegmentNumber = segmentPlaylist.seq;
            }
            
            var continuation = splitRecordingIfNecessary(segmentPlaylist);
            if (continuation != null) {
                continuation.segmentPlaylistUrl = segmentPlaylistUrl;
                continuation.beforeLastPlaylistRequest = beforeLastPlaylistRequest;
                continuation.processPlaylistInner(segmentPlaylist);
                return continuation;
            }
            
            processPlaylistInner(segmentPlaylist);

        } catch (ParseException e) {
            LOG.error("Couldn't parse HLS playlist for model {}\n{}", model, e.getInput(), e);
            model.delay();
            stop();
        } catch (PlaylistException e) {
            LOG.error("Couldn't parse HLS playlist for model {}", model, e);
            model.delay();
            stop();
        } catch (PlaylistTimeoutException e) {
            if (consecutivePlaylistTimeouts >= 5) {
                model.delay();
                stop();
            } else {
                rescheduleTime = Instant.now(); // try again as fast as possible
            }
        } catch (EOFException e) {
            // end of playlist reached
            LOG.debug("Reached end of playlist for model {}", model);
            stop();
        } catch (HttpException e) {
            consecutivePlaylistErrors++;
            handleHttpException(e);
        } catch (Exception e) {
            LOG.error("Couldn't download segment for model {}", model, e);
            model.delay();
            stop();
        } finally {            
            if (consecutivePlaylistErrors > 0) {
                LOG.debug("Consecutive playlist errors: {}", consecutivePlaylistErrors);
            }
        }
        
        if (Duration.between(lastSegmentDownload, Instant.now()).getSeconds() > MAX_SECONDS_WITHOUT_TRANSFER) {
            LOG.info("No video data received for {} seconds. Stopping recording for model {}", MAX_SECONDS_WITHOUT_TRANSFER, model);
            stop();
        }
        
        return this;
    }

    private void processPlaylistInner(SegmentPlaylist segmentPlaylist) throws IOException {
        enqueueNewSegments(segmentPlaylist, nextSegmentNumber);
        
        // by the spec we must wait `targetDuration` before next playlist request if there are changes
        // if there are none - half that amount
        calculateRescheduleTime(playlistChanged(segmentPlaylist, nextSegmentNumber) 
                                ? segmentPlaylist.targetDuration*1000 
                                : segmentPlaylist.targetDuration*500);
        processFinishedSegments();

        // this if-check makes sure, that we don't decrease nextSegment. for some reason
        // streamate playlists sometimes jump back. e.g. max sequence = 79 -> 80 -> 79
        lastSegmentNumber = segmentPlaylist.seq;
        var thisCount = (int)segmentPlaylist.segments.stream().filter(s -> !s.isInitSegment).count();
        if (lastSegmentNumber + thisCount > nextSegmentNumber) {
            nextSegmentNumber = lastSegmentNumber + thisCount;
        }

        while (recordingEvents.size() > 30) {
            recordingEvents.remove(0);
        }
    }
    
    private boolean playlistChanged(SegmentPlaylist segmentPlaylist, int nextSegmentNumber) {
        return segmentPlaylist.seq + segmentPlaylist.segments.size() > nextSegmentNumber;
    }
    

    protected void processFinishedSegments() {
        downloadExecutor.submit(() -> {
            Future<SegmentDownload> future;
            while ((future = segmentDownloadService.poll()) != null) {
                try {
                    SegmentDownload segmentDownload = future.get();
                    if (segmentDownload.isFailed()) {
                        segmentDownloadFailed(segmentDownload);
                    } else {
                        segmentDownloadFinished(segmentDownload);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Thread interrupted during segment download", e);
                } catch (ExecutionException e) {
                    // Something unexpected went wrong during the segment download.
                    // At this point we have taken care of that, but we take note of the error and if a certain threshold of errors is exceeded in a certain
                    // amount of time, we stop the download and hope for the best to get routed to a better server.
                    stopRecordingOnHighSegmentErrorCount();
                }
            }
        });
    }

    private void stopRecordingOnHighSegmentErrorCount() {
        segmentErrorTimestamps.add(Instant.now());
        int errorsInMeasurePeriod = 0;
        int measurePeriodInSecs = config.getSettings().segmentErrorMeasurePeriodInSecs;
        Instant measureStart = Instant.now().minusSeconds(measurePeriodInSecs);
        for (Iterator<Instant> iterator = segmentErrorTimestamps.iterator(); iterator.hasNext(); ) {
            Instant timestamp = iterator.next();
            if (timestamp.isAfter(measureStart)) {
                errorsInMeasurePeriod++;
            } else {
                // too old, can be removed
                iterator.remove();
            }
        }
        LOG.debug("Segment errors in last {} secs for {}: {}", measurePeriodInSecs, getModel(), errorsInMeasurePeriod);
        if (errorsInMeasurePeriod > config.getSettings().segmentErrorThresholdToStopRecording) {
            LOG.info("Too many ({}) segment errors for {} - stopping recording", errorsInMeasurePeriod, getModel());
            stop();
        }
    }

    protected abstract void execute(SegmentDownload segmentDownload);

    protected void handleHttpException(HttpException e) {
        if (e.getResponseCode() == 404) {
            checkIfModelIsStillOnline("Playlist not found (404). Model {} probably went offline. Model state: {}");
        } else if (e.getResponseCode() == 403) {
            checkIfModelIsStillOnline("Playlist access forbidden (403). Model {} probably went private or offline. Model state: {}");
        }
        if (consecutivePlaylistErrors >= 3) {
            LOG.info("Playlist could not be downloaded for model {} {} times. Stopping recording", model, consecutivePlaylistErrors, e);
            model.delay();
            stop();
        } else {
            LOG.info("Playlist could not be downloaded for model {} {} times: {}", model, consecutivePlaylistErrors, e.getLocalizedMessage());
        }
    }

    protected void checkIfModelIsStillOnline(String errorMsg) {
        ctbrec.Model.State modelState;
        try {
            modelState = model.updateOnlineState(false);
        } catch (Exception e1) {
            modelState = State.UNKNOWN;
        }
        LOG.info(errorMsg, model, modelState);
        if (modelState != State.ONLINE) {
            stop();
            return;
        }
        try {
            LOG.debug("Waiting {}ms before trying to update the playlist URL for {}", A_FEW_SECONDS, model);
            waitSomeTime();
            segmentPlaylistUrl = getSegmentPlaylistUrl(model);
            rescheduleTime = Instant.now();
        } catch (Exception e) {
            LOG.error("Playlist URL couldn't be updated after waiting for {}ms for {}", A_FEW_SECONDS, model, e);
        }
    }

    protected String getSegmentPlaylistUrl(Model model) throws IOException, ExecutionException, ParseException, PlaylistException, JAXBException {
        LOG.debug("{}:{} stream idx: {}", model.getSite().getName(), model.getName(), model.getStreamUrlIndex());
        List<StreamSource> streamSources = model.getStreamSources();
        Collections.sort(streamSources);
        for (StreamSource streamSource : streamSources) {
            LOG.debug("{}:{} src {}", model.getSite().getName(), model.getName(), streamSource);
        }
        StreamSource selectedStreamSource = selectStreamSource(streamSources);
        String url = selectedStreamSource.getMediaPlaylistUrl();
        selectedResolution = selectedStreamSource.getHeight();
        LOG.debug("Segment playlist url {}", url);
        return url;
    }

    protected SegmentPlaylist getNextSegments(String segmentPlaylistUrl) throws IOException, ParseException, PlaylistException {
        Instant start = Instant.now();
        recordingEvents.add(RecordingEvent.of("Playlist request"));
        URL segmentsUrl = URI.create(segmentPlaylistUrl).toURL();
        Builder builder = new Request.Builder().url(segmentsUrl);
        addHeaders(builder, Optional.ofNullable(model).map(Model::getHttpHeaderFactory).map(HttpHeaderFactory::createSegmentPlaylistHeaders).orElse(new HashMap<>()), model);
        Request request = builder.build();
        
        try (Response response = client.execute(request, config.getSettings().playlistRequestTimeout)) {
            if (response.isSuccessful()) {
                consecutivePlaylistTimeouts = 0;
                var originBody = Objects.requireNonNull(response.body());
                BandwidthMeter.add(Math.max(0, originBody.contentLength()));
                String body = originBody.string();
                if (!body.contains("#EXTINF")) {
                    // no segments, empty playlist
                    return new SegmentPlaylist(segmentPlaylistUrl);
                }
                SegmentPlaylist playList = parsePlaylist(response.request().url().toString(), playlistPreprocessor.process(body));
                consecutivePlaylistErrors = 0;
                recordingEvents.add(RecordingEvent.of("Sequence: " + StringUtil.grep(body, "MEDIA-SEQUENCE")));
                recordingEvents.add(RecordingEvent.of("Playlist downloaded in " + (Duration.between(start, Instant.now()).toMillis()) + "ms: "
                        + StringUtil.grep(body, "X-PROGRAM-DATE-TIME")));
                return playList;
            } else {
                recordingEvents.add(RecordingEvent.of("HTTP code " + response.code()));
                throw new HttpException(response.code(), response.message());
            }
        } catch (SocketTimeoutException e) {            
            LOG.debug("Playlist request timed out ({}ms) for model {}:{} {} time{} (took {}ms)", 
                config.getSettings().playlistRequestTimeout, 
                model.getSite().getName(), 
                model,
                ++consecutivePlaylistTimeouts, 
                (consecutivePlaylistTimeouts > 1) ? 's' : "",
                (Duration.between(start, Instant.now()).toMillis()));
            // times out, return an empty playlist, so that the process can continue without wasting much more time
            recordingEvents.add(RecordingEvent.of("Playlist request timed out " + consecutivePlaylistTimeouts));            
            throw new PlaylistTimeoutException(e);
        } catch (Exception e) {
            consecutivePlaylistErrors++;
            throw e;
        }
    }

    private SegmentPlaylist parsePlaylist(String segmentPlaylistUrl, String input) throws IOException, ParseException, PlaylistException {
        PlaylistParser parser = new PlaylistParser(new ByteArrayInputStream(input.getBytes(UTF_8)), Format.EXT_M3U, Encoding.UTF_8, ParsingMode.LENIENT);
        Playlist playlist = parser.parse();
        if (playlist.hasMediaPlaylist()) {
            MediaPlaylist mediaPlaylist = playlist.getMediaPlaylist();
            SegmentPlaylist lsp = new SegmentPlaylist(segmentPlaylistUrl);
            lsp.seq = mediaPlaylist.getMediaSequenceNumber();
            lsp.targetDuration = mediaPlaylist.getTargetDuration();

            List<TrackData> tracks = mediaPlaylist.getTracks();
            for (TrackData trackData : tracks) {
                parseSegmentData(lsp, trackData);
            }
            lsp.avgSegDuration = lsp.totalDuration / tracks.size();
            return lsp;
        }
        throw new InvalidPlaylistException("Playlist has no media playlist");
    }

    private void parseSegmentData(SegmentPlaylist lsp, TrackData trackData) {
        URI context = URI.create(segmentPlaylistUrl);
        
        if (trackData.hasMapInfo()) {
            var mapInfoUri = trackData.getMapInfo().getUri();
            if (!mapInfoUri.startsWith("http")) {
                mapInfoUri = context.resolve(mapInfoUri).toString();
            }
            lsp.segments.add(new Segment(mapInfoUri, true));
        }
        String uri = trackData.getUri();
        
        if (!uri.startsWith("http")) {
            uri = context.resolve(uri).toString();
        }
        lsp.totalDuration += trackData.getTrackInfo().duration;
        lsp.segments.add(new Segment(uri, Math.max(0, trackData.getTrackInfo().duration)));
        if (trackData.hasEncryptionData()) {
            lsp.encrypted = true;
            EncryptionData data = trackData.getEncryptionData();
            lsp.encryptionKeyUrl = data.getUri();
            lsp.encryptionMethod = data.getMethod().getValue();
        }
    }

    protected void emptyPlaylistCheck(SegmentPlaylist playlist) {
        if (playlist.segments.isEmpty()) {
            playlistEmptyCount++;
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            playlistEmptyCount = 0;
        }
        if (playlistEmptyCount == 10) {
            LOG.info("Last 10 playlists were empty for {}. Stopping recording!", getModel());
            model.delay();
            internalStop();
        }
    }

    private void handleMissedSegments(SegmentPlaylist playlist, int nextSegmentNumber) throws IOException {
        if (nextSegmentNumber > 0 && playlist.seq > nextSegmentNumber) {
            recordingEvents.add(RecordingEvent.of("Missed segments: " + nextSegmentNumber + " < " + playlist.seq));
            if (config.getSettings().logMissedSegments) {
                File hlsEventsFile = File.createTempFile("rec_evt_" + Instant.now() + "_" + model.getSanitizedNamed(), ".log");
                try (OutputStream outputStream = Files.newOutputStream(hlsEventsFile.toPath(), CREATE, WRITE, TRUNCATE_EXISTING)) {
                    BufferedWriter br = new BufferedWriter(new OutputStreamWriter(outputStream));
                    DateTimeFormatter dtf = DateTimeFormatter.ISO_LOCAL_TIME;
                    for (RecordingEvent recordingEvent : recordingEvents) {
                        ZonedDateTime dateTime = recordingEvent.timestamp.atZone(ZoneId.systemDefault());
                        br.append(dtf.format(dateTime)).append(' ').append(model.toString()).append(' ').append(recordingEvent.message);
                        br.newLine();
                    }
                    br.flush();
                } catch (IOException e) {
                    LOG.error("Couldn't write log file for missed segments", e);
                }
            }
        }
    }

    private AbstractHlsDownload splitRecordingIfNecessary(SegmentPlaylist segmentPlaylist) throws Exception {
        if (splittingStrategy.splitNecessary(this)) {
            var dl = createContinuation(segmentPlaylist);
            internalStop();
            return dl;
        }
        return null;
    }

    protected void enqueueNewSegments(SegmentPlaylist playlist, int nextSegmentNumber) throws IOException {
        int skip = nextSegmentNumber - playlist.seq;
        for (Segment segment : playlist.segments) {
            if (skip > 0 && !segment.isInitSegment) {
                skip--;
                continue;
            }
            
            // skip repeating map segments. Usually is gonna be the first in an iteration playlist
            if (segment.isInitSegment && segment.url.equals(currentMapUrl))
                continue;            
            
            if (segment.isInitSegment) {
                segment.prefix = "init_" + nf.format(initCounter++);
            } else {
                segment.prefix = nf.format(segmentCounter++);
            }
            var targetStream = getSegmentOutputStream(segment);
            var segmentDownload = getSegmentDownload(playlist, segment, targetStream);
            execute(segmentDownload);
            
            if (segment.isInitSegment)
                currentMapUrl = segment.url; 
        }
    }

    protected SegmentDownload getSegmentDownload(SegmentPlaylist playlist, Segment segment, OutputStream targetStream) throws MalformedURLException {
        return new SegmentDownload(model, playlist, segment, client, targetStream);
    }

    private void calculateRescheduleTime(float duration_ms) {
        rescheduleTime = beforeLastPlaylistRequest.plusMillis(Math.max((int)Math.ceil(duration_ms), 250));
        if (Instant.now().isAfter(rescheduleTime))
            rescheduleTime = Instant.now();
        recordingEvents.add(RecordingEvent.of("next playlist download scheduled for " + rescheduleTime.toString()));
    }

    /**
     * Causes the current thread to sleep for a short amount of time.
     * This is used to slow down retries, if something is wrong with the playlist.
     * E.g. HTTP 403 or 404
     */
    protected void waitSomeTime() {
        try {
            Thread.sleep(A_FEW_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (running) {
                LOG.error("Couldn't sleep. This might mess up the download!");
            }
        }
    }

    public static void addHeaders(Builder builder, Map<String, String> headers, Model model) {
        headers.putIfAbsent(ACCEPT, "*/*");
        headers.putIfAbsent(ACCEPT_LANGUAGE, Locale.ENGLISH.getLanguage());
        headers.putIfAbsent(USER_AGENT, Config.getInstance().getSettings().httpUserAgent);
        headers.putIfAbsent(CONNECTION, KEEP_ALIVE);
        headers.computeIfAbsent(HttpConstants.ORIGIN, k -> Optional.ofNullable(model).map(Model::getSite).map(Site::getBaseUrl).orElse(null));
        headers.computeIfAbsent(REFERER, k -> Optional.ofNullable(model).map(Model::getSite).map(Site::getBaseUrl).orElse(null));

        for (Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static class RecordingEvent {
        Instant timestamp;
        String message;

        public static RecordingEvent of(String message) {
            RecordingEvent evt = new RecordingEvent();
            evt.timestamp = Instant.now();
            evt.message = message;
            return evt;
        }
    }
}
