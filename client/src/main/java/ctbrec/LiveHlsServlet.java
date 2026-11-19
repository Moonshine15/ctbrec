package ctbrec;

import static javax.servlet.http.HttpServletResponse.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ctbrec.io.HttpException;
import ctbrec.io.PlaylistPreprocessor;
import ctbrec.recorder.Recorder;
import ctbrec.recorder.download.HttpHeaderFactory;
import ctbrec.recorder.download.hls.AbstractHlsDownload;
import ctbrec.recorder.download.hls.PlaylistTimeoutException;
import ctbrec.sites.stripchat.Stripchat;
import okhttp3.Request;
import okhttp3.Request.Builder;
import okhttp3.Response;

// A proxy servlet that processes m3u8 playlist before giving it to the player process
public class LiveHlsServlet extends HttpServlet {

    private static final transient Logger LOG = LoggerFactory.getLogger(LiveHlsServlet.class);
    // Note: can be a fake recorder, not a real one. Only used to hold model data
    private final Recorder recorder = new PlayerModelCache();

    public LiveHlsServlet() {
    }

    public void start(Model model) throws Exception {
        recorder.addModel(model);
    }

    public void stop(Model model) throws Exception {
        recorder.stopRecording(model);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        // String contextPath = getServletContext().getContextPath();
        // String request = req.getRequestURI().substring(contextPath.length());

        try {
            var path = req.getPathInfo(); // /site/model/file
            var parts = path.split("/");
            var siteName = parts[1];
            var modelName = parts[2];
            var fileName = parts[3];

            if (fileName.equals("master.m3u8")) {
                // wrap passed video and audio stream URLs into our own master playlist
                // the player app will parse this playlist and then switch to normal operation through the passed links (original/source)
                // this method assumes that source media playlists are playable without custom (site specific) processing
                var videoUrl = req.getParameter("v"); // decoded video playlist URL
                var audioUrl = req.getParameter("a"); // decoded audio playlist URL

                String body;
                if (audioUrl != null) {
                    body = """
                        #EXTM3U
                        #EXT-X-VERSION:7
                        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="audio",DEFAULT=YES,AUTOSELECT=YES,URI="%s"
                        #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="audio"
                        %s
                        """.formatted(audioUrl, videoUrl);
                } else {
                    body = """
                        #EXTM3U
                        #EXT-X-VERSION:7
                        #EXT-X-STREAM-INF:BANDWIDTH=1
                        %s
                        """.formatted(videoUrl);
                }
                
                // some players refuse to accept the playlist without some headers
                resp.setContentType("application/vnd.apple.mpegurl");
                resp.setCharacterEncoding("UTF-8");
                writeResponse(resp, SC_OK, body);
            } else if (fileName.equals("video.m3u8")) {
                var hlsUrl = req.getParameter("src"); // decoded original URL

                // gen model params from 'recorder'. Assume it was added prior (i.e. by the
                // `Player` object)
                Model model = recorder.getModels().stream()
                        .filter(m -> m.getName().equals(modelName) && m.getSite().getName().equals(siteName))
                        .findFirst()
                        .get();
                var client = model.getSite().getHttpClient();
                var config = Config.getInstance();

                PlaylistPreprocessor playlistPreprocessor = null;
                if (siteName.equals("Stripchat")) {
                    playlistPreprocessor = Stripchat.PREPROCESSOR;
                }

                URL segmentsUrl = URI.create(hlsUrl).toURL();
                Builder builder = new Request.Builder().url(segmentsUrl);
                // try to be consistent with normal recording. TODO: check if this is necessary
                AbstractHlsDownload.addHeaders(builder, 
                    Optional
                    .ofNullable(model)
                    .map(Model::getHttpHeaderFactory)
                    .map(HttpHeaderFactory::createSegmentPlaylistHeaders)
                    .orElse(new HashMap<>()), model);
                Request request = builder.build();

                try (Response response = client.execute(request, config.getSettings().playlistRequestTimeout)) {
                    if (response.isSuccessful()) {
                        var originBody = Objects.requireNonNull(response.body());
                        // should we count it here? BandwidthMeter.add(Math.max(0,
                        // originBody.contentLength()));
                        String body = originBody.string();

                        // some players refuse to accept the playlist without some headers
                        resp.setContentType(response.header("Content-Type"));

                        if (playlistPreprocessor == null)
                            writeResponse(resp, SC_OK, body);
                        else
                            writeResponse(resp, SC_OK, playlistPreprocessor.process(body));
                    } else {
                        throw new HttpException(response.code(), response.message());
                    }
                } catch (SocketTimeoutException e) {
                    LOG.debug("Playlist request timed out ({}ms) for model {}:{}",
                            config.getSettings().playlistRequestTimeout, siteName, modelName);
                    // times out, return an empty playlist, so that the process can continue without
                    // wasting much more time
                    throw new PlaylistTimeoutException(e);
                }
            }
        } catch (IllegalStateException e1) {
            writeResponse(resp, SC_UNAUTHORIZED, "{\"status\": \"error\", \"msg\": \"Authentication failed\"}");
        }
    }

    private void writeResponse(HttpServletResponse resp, int code, String body) {
        try {
            resp.setStatus(code);
            resp.getWriter().write(body);
        } catch (IOException e) {
            LOG.error("Couldn't write HTTP response", e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        doGet(req, resp);
    }

}
