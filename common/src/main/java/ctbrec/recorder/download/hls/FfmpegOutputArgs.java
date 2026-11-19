package ctbrec.recorder.download.hls;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class FfmpegOutputArgs {

    private static final int HLS_OUTPUT_SEGMENT_DURATION_SECONDS = 2;
    private static final Set<String> STRIP_NEXT_ARG_FLAGS = Set.of("-map", "-map_channel", "-i");
    private static final Set<String> STRIP_STANDALONE_FLAGS = Set.of("-an", "-vn", "-sn", "-dn");

    private FfmpegOutputArgs() {
    }

    public static List<String> buildPairInputArguments(Path videoPlaylist, Path audioPlaylist) {
        return buildPairInputArguments(videoPlaylist.toString(), Duration.ZERO, audioPlaylist.toString(), Duration.ZERO);
    }

    public static List<String> buildPairInputArguments(Path videoPlaylist,
                                                       Duration videoInputDelay,
                                                       Path audioPlaylist,
                                                       Duration audioInputDelay) {
        return buildPairInputArguments(videoPlaylist.toString(), videoInputDelay, audioPlaylist.toString(), audioInputDelay);
    }

    public static List<String> buildPairInputArguments(String videoInput,
                                                       Duration videoInputDelay,
                                                       String audioInput,
                                                       Duration audioInputDelay) {
        List<String> args = new ArrayList<>();
        appendInputArguments(args, videoInput, videoInputDelay);
        appendInputArguments(args, audioInput, audioInputDelay);
        args.add("-map");
        args.add("0:v:0");
        args.add("-map");
        args.add("1:a:0");
        return args;
    }

    public static List<String> buildSingleFileOutputArguments(String ffmpegOutputArgs, File targetFile, List<String> strippedFlags) {
        List<String> args = new ArrayList<>();
        args.addAll(sanitizeCustomOutputArguments(ffmpegOutputArgs, strippedFlags));
        args.add(targetFile.getAbsolutePath());
        return args;
    }

    public static List<String> sanitizeCustomOutputArguments(String ffmpegOutputArgs, List<String> strippedFlags) {
        List<String> sanitized = new ArrayList<>();
        List<String> tokens = Arrays.stream(ffmpegOutputArgs.split(" "))
                .filter(arg -> !arg.isBlank())
                .toList();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (STRIP_NEXT_ARG_FLAGS.contains(token)) {
                strippedFlags.add(token);
                if (i + 1 < tokens.size()) {
                    strippedFlags.add(tokens.get(++i));
                }
                continue;
            }
            if (STRIP_STANDALONE_FLAGS.contains(token)) {
                strippedFlags.add(token);
                continue;
            }
            sanitized.add(token);
        }
        return sanitized;
    }

    public static List<String> buildPlaylistOutputArguments(Path downloadDir) {
        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add("copy");
        args.add("-f");
        args.add("hls");
        args.add("-hls_time");
        args.add(Integer.toString(HLS_OUTPUT_SEGMENT_DURATION_SECONDS));
        args.add("-hls_list_size");
        args.add("0");
        args.add("-hls_playlist_type");
        args.add("event");
        args.add("-start_number");
        args.add("0");
        args.add("-hls_segment_filename");
        args.add(downloadDir.resolve("segment_%06d.ts").toString());
        args.add(downloadDir.resolve("playlist.m3u8").toString());
        return args;
    }

    private static void appendInputArguments(List<String> args, String input, Duration inputDelay) {
        if (inputDelay != null && !inputDelay.isZero()) {
            args.add("-itsoffset");
            args.add(formatDurationSeconds(inputDelay));
        }
        args.add("-i");
        args.add(input);
    }

    private static String formatDurationSeconds(Duration duration) {
        return String.format(Locale.US, "%.6f", duration.toNanos() / 1_000_000_000d);
    }
}
