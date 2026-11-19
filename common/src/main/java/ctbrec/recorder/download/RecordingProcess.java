package ctbrec.recorder.download;

import ctbrec.Config;
import ctbrec.Model;
import ctbrec.Recording;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

public interface RecordingProcess extends Callable<RecordingProcess> {

    void init(Config config, Model model, Instant startTime, ExecutorService executorService) throws IOException;

    void stop();

    void finalizeDownload();

    boolean isRunning();

    Model getModel();

    Instant getStartTime();

    Instant getRescheduleTime();

    void postProcess(Recording recording);

    default int getSelectedResolution() {
        return StreamSource.UNKNOWN;
    }

    /**
     * Returns the path to the recording in the filesystem as file object
     *
     * @return
     * @see #getPath(Model)
     */
    File getTarget();

    /**
     * Returns the path to the recording starting from the configured recordings directory
     *
     * @param model
     * @return
     * @see #getTarget()
     */
    String getPath(Model model);

    /**
     * Specifies, if the final result of this recording is a single file or consists of segments + playlist
     *
     * @return true, if the recording is only a single file
     */
    boolean isSingleFile();

    long getSizeInByte();

    void awaitEnd();

    AtomicLong getDownloadedBytes();

    /**
     * Returns true if the recording process stopped due to a capture failure
     * rather than a clean exit. The recorder uses this to set the recording
     * status to FAILED instead of routing through normal post-processing.
     */
    default boolean isFailedCapture() {
        return false;
    }

    /**
     * Returns additional files or directories that belong to the recording and
     * should be persisted and deleted together with it.
     */
    default Set<File> getManagedArtifacts() {
        return Set.of();
    }

    /**
     * Returns a live playback source for an active recording process when the
     * UI can reuse recorder-owned state instead of fetching a fresh upstream
     * stream URL. Processes without a special live playback source should
     * return {@link Optional#empty()} so the player falls back to the normal
     * model lookup path.
     */
    default Optional<StreamSource> getLivePlaybackStreamSource() {
        return Optional.empty();
    }
    
    default String getStats() {
        return "";
    }
}
