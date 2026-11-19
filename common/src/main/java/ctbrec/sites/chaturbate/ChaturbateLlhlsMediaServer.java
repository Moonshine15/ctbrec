package ctbrec.sites.chaturbate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP server that serves in-memory LL-HLS playlists and segments
 * to FFmpeg. Eliminates all transient disk I/O during recording.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code /video/playlist.m3u8} — video track playlist (generated on-the-fly)</li>
 *   <li>{@code /video/<filename>} — video segment or init segment bytes</li>
 *   <li>{@code /audio/playlist.m3u8} — audio track playlist</li>
 *   <li>{@code /audio/<filename>} — audio segment or init segment bytes</li>
 * </ul>
 *
 * <p>One server instance per recording session, bound to {@code 127.0.0.1}
 * on an auto-allocated port.
 */
final class ChaturbateLlhlsMediaServer {

    private static final Logger LOG = LoggerFactory.getLogger(ChaturbateLlhlsMediaServer.class);

    private final ChaturbateLlhlsInMemoryWorkspace videoWorkspace;
    private final ChaturbateLlhlsInMemoryWorkspace audioWorkspace;
    private HttpServer server;
    private ExecutorService executor;
    private int port;

    ChaturbateLlhlsMediaServer(ChaturbateLlhlsInMemoryWorkspace videoWorkspace,
                               ChaturbateLlhlsInMemoryWorkspace audioWorkspace) {
        this.videoWorkspace = videoWorkspace;
        this.audioWorkspace = audioWorkspace;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new WorkspaceHandler());
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "llhls-media-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        port = server.getAddress().getPort();
        LOG.debug("LL-HLS media server started on port {}", port);
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        LOG.debug("LL-HLS media server stopped (port {})", port);
    }

    /**
     * Returns the HTTP URL for FFmpeg to read the video playlist.
     */
    String getVideoPlaylistUrl() {
        return "http://127.0.0.1:" + port + "/video/playlist.m3u8";
    }

    /**
     * Returns the HTTP URL for players to read a combined master playlist
     * that references the local video and audio tracks.
     */
    String getMasterPlaylistUrl() {
        return "http://127.0.0.1:" + port + "/master.m3u8";
    }

    /**
     * Returns the HTTP URL for FFmpeg to read the audio playlist.
     */
    String getAudioPlaylistUrl() {
        return "http://127.0.0.1:" + port + "/audio/playlist.m3u8";
    }

    private class WorkspaceHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if ("/master.m3u8".equals(path)) {
                    handleMasterPlaylistRequest(exchange);
                } else if (path.startsWith("/video/")) {
                    handleTrackRequest(exchange, videoWorkspace, path.substring("/video/".length()), false);
                } else if (path.startsWith("/audio/")) {
                    handleTrackRequest(exchange, audioWorkspace, path.substring("/audio/".length()), true);
                } else {
                    sendError(exchange, 404, "Not found: " + path);
                }
            } catch (Exception e) {
                LOG.debug("Error serving LL-HLS media request: {}", exchange.getRequestURI(), e);
                try {
                    sendError(exchange, 500, "Internal error");
                } catch (IOException ignored) {
                    // Client may have disconnected
                }
            } finally {
                exchange.close();
            }
        }

        private void handleMasterPlaylistRequest(HttpExchange exchange) throws IOException {
            byte[] playlist = generateMasterPlaylist();
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
            exchange.sendResponseHeaders(200, playlist.length);
            exchange.getResponseBody().write(playlist);
        }

        private void handleTrackRequest(HttpExchange exchange,
                                        ChaturbateLlhlsInMemoryWorkspace workspace,
                                        String filename,
                                        boolean isAudio) throws IOException {
            if ("playlist.m3u8".equals(filename)) {
                byte[] playlist = workspace.generatePlaylist();
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.apple.mpegurl");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
                exchange.sendResponseHeaders(200, playlist.length);
                exchange.getResponseBody().write(playlist);
                return;
            }

            byte[] data = workspace.serveSegment(filename);
            if (data == null) {
                sendError(exchange, 404, "Segment not found: " + filename);
                return;
            }

            String contentType = filename.endsWith(".m3u8")
                    ? "application/vnd.apple.mpegurl"
                    : isAudio ? "audio/mp4" : "video/mp4";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }

        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            byte[] body = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
        }

        private byte[] generateMasterPlaylist() {
            String body = """
                    #EXTM3U
                    #EXT-X-VERSION:7
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="audio",DEFAULT=YES,AUTOSELECT=YES,URI="audio/playlist.m3u8"
                    #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="audio"
                    video/playlist.m3u8
                    """;
            return body.getBytes(StandardCharsets.UTF_8);
        }
    }
}
