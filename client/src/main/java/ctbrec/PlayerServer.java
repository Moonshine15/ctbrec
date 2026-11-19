package ctbrec;

import ctbrec.recorder.Recorder;
import ctbrec.recorder.download.StreamSource;
import ctbrec.servlet.AbstractDocServlet;
import ctbrec.servlet.MarkdownServlet;
import ctbrec.servlet.SearchServlet;
import ctbrec.servlet.StaticFileServlet;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;

import java.net.BindException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

// A proxy server to modify HLS playlist for a media player when needed
@Slf4j
public class PlayerServer {
    private static volatile boolean started = false;    
    private static final LiveHlsServlet servlet = new LiveHlsServlet();
    
    private static int PORT = 0; // 0 for random port

    private PlayerServer() {
    }
    
    // must be called after start()
    public static String wrapURL(Model model, String url) {
        assert(PORT != 0); // port is chosen randomly in start()
        return "http://localhost:%d/livehls/%s/%s/video.m3u8?src=%s".formatted(
            PORT, 
            model.getSite().getName(),
            model.getName(),
            URLEncoder.encode(url, StandardCharsets.UTF_8)
        );
    }
    
    // must be called after start()
    public static String wrap(Model model, StreamSource src) {
        assert(PORT != 0); // port is chosen randomly in start()
        
        // transforms relative paths into proper http urls
        var context = URI.create(src.getMasterPlaylistUrl());
                
        return "http://localhost:%d/livehls/%s/%s/master.m3u8?v=%s%s".formatted(
            PORT,
            model.getSite().getName(),
            model.getName(),
            URLEncoder.encode(context.resolve(src.getMediaPlaylistUrl()).toString(), StandardCharsets.UTF_8),
            src.getAudio() != null ? "&a=" + URLEncoder.encode(context.resolve(src.getAudio().getPlaylistUrl()).toString(), StandardCharsets.UTF_8) : ""
        );
    }
    
    public static synchronized void start(Model model) throws Exception {
        servlet.start(model);
        
        start(() -> {
        });
    }
    
    public static synchronized void stop(Model model) throws Exception {
        servlet.stop(model);
    }
    
    public static synchronized void start() throws Exception {
        start(() -> {
        });
    }

    public static synchronized void start(Runnable callback) throws Exception {
        log.info("PlayerServer.start");
        if (started) {
            log.info("if started");
            callback.run();
            return;
        }

        started = true;
        var server = new Server();

        var config = new HttpConfiguration();
        config.setSendServerVersion(false);
        var http = new ServerConnector(server, new HttpConnectionFactory(config));
        http.setPort(PORT);
        server.addConnector(http);

        var handler = new ServletHandler();
        server.setHandler(handler);
        var handlers = new HandlerList();
        handlers.setHandlers(new Handler[]{handler});
        server.setHandler(handlers);

        var holder = new ServletHolder(servlet);
        handler.addServletWithMapping(holder, "/livehls/*");

        server.addLifeCycleListener(new LifeCycle.Listener() {
            @Override
            public void lifeCycleStarted(LifeCycle event) {
                callback.run();
            }
        });

        try {
            server.start();
            PORT = http.getLocalPort();
            // FIXME: we are just yeeting the thread here. Maybe put a proper shutdown somewhere?
            //server.join();
        } catch (BindException e) {
            log.error("Port {} is already in use", http.getPort(), e);
        }
    }
}
