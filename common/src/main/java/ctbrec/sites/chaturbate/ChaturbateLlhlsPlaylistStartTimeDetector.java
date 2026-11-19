package ctbrec.sites.chaturbate;

import ctbrec.OS;
import ctbrec.io.DevNull;
import ctbrec.io.StreamRedirector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

final class ChaturbateLlhlsPlaylistStartTimeDetector {

    private static final Logger LOG = LoggerFactory.getLogger(ChaturbateLlhlsPlaylistStartTimeDetector.class);
    private static final Pattern START_TIME_PATTERN = Pattern.compile("start: ([+-]?\\d+(?:\\.\\d+)?),");
    private static final int PROCESS_TIMEOUT_SECONDS = 5;

    private ChaturbateLlhlsPlaylistStartTimeDetector() {
    }

    /**
     * Probe the start time of a local HLS playlist using FFmpeg.
     *
     * @param playlistInput file path or HTTP URL to the playlist
     */
    static OptionalDouble detectStartTime(String playlistInput) {
        try {
            String[] cmdline = OS.getFFmpegCommand(
                    "-hide_banner",
                    "-i", playlistInput,
                    "-f", "ffmetadata",
                    "-"
            );
            Process process = new ProcessBuilder(cmdline)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start();

            ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
            Thread stdout = Thread.ofVirtual().name("llhls-start-probe-out").start(
                    new StreamRedirector(process.getInputStream(), new DevNull()));
            Thread stderr = Thread.ofVirtual().name("llhls-start-probe-err").start(
                    new StreamRedirector(process.getErrorStream(), stderrBuffer));

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS) && process.isAlive()) {
                    process.destroyForcibly();
                }
            }

            stdout.join(TimeUnit.SECONDS.toMillis(1));
            stderr.join(TimeUnit.SECONDS.toMillis(1));
            return parseStartTime(stderrBuffer.toString(UTF_8));
        } catch (IOException e) {
            LOG.debug("Couldn't probe LL-HLS playlist start time for {}", playlistInput, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while probing LL-HLS playlist start time for {}", playlistInput, e);
        }
        return OptionalDouble.empty();
    }

    static OptionalDouble parseStartTime(String ffmpegStderr) {
        Matcher matcher = START_TIME_PATTERN.matcher(ffmpegStderr);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(matcher.group(1)));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }
}
