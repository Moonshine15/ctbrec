package ctbrec.recorder;

import ctbrec.Config;
import ctbrec.Model;
import ctbrec.Recording;
import ctbrec.Settings;
import ctbrec.recorder.download.RecordingProcess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimplifiedLocalRecorderStopWorkflowTest {

    @TempDir
    Path tempDir;

    private SimplifiedLocalRecorder recorder;

    @AfterEach
    void afterEach() {
        if (recorder != null) {
            recorder.shutdown(true);
        }
    }

    @Test
    void stopRecordingProcess_releasesRecorderLockBeforeBlockingStopReturns() throws Exception {
        Settings settings = new Settings();
        settings.recordingsDir = tempDir.resolve("recordings").toString();

        recorder = createRecorder(settings);

        Model model = mock(Model.class);
        when(model.getName()).thenReturn("sladkoezka");
        when(model.getDisplayName()).thenReturn("sladkoezka");
        when(model.getSanitizedNamed()).thenReturn("sladkoezka");

        BlockingRecordingProcess process = new BlockingRecordingProcess();
        Recording recording = new Recording(settings.recordingsDir);
        recording.setId(UUID.randomUUID().toString());
        recording.setModel(model);
        recording.setStartDate(Instant.now());
        recording.setRecordingProcess(process);
        recorder.getRecordingProcesses().add(process);

        Thread stopper = Thread.ofVirtual().start(() -> recorder.stopRecordingProcess(process));

        assertTrue(process.stopEntered.await(2, TimeUnit.SECONDS));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertDoesNotThrow(() -> executor.submit(recorder::getModels).get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertTrue(recorder.getRecordingProcesses().isEmpty());

        process.allowStopToFinish.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(2));
    }

    private SimplifiedLocalRecorder createRecorder(Settings settings) throws IOException {
        Config config = mock(Config.class);
        when(config.getSettings()).thenReturn(settings);
        when(config.getConfigDir()).thenReturn(tempDir.resolve("config").toFile());
        return new SimplifiedLocalRecorder(config, List.of());
    }

    private static final class BlockingRecordingProcess implements RecordingProcess {
        private final CountDownLatch stopEntered = new CountDownLatch(1);
        private final CountDownLatch allowStopToFinish = new CountDownLatch(1);
        private final AtomicLong downloadedBytes = new AtomicLong();

        @Override
        public void init(Config config, Model model, Instant startTime, ExecutorService executorService) {
        }

        @Override
        public void stop() {
            stopEntered.countDown();
            try {
                allowStopToFinish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void finalizeDownload() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public Model getModel() {
            return mock(Model.class);
        }

        @Override
        public Instant getStartTime() {
            return Instant.now();
        }

        @Override
        public Instant getRescheduleTime() {
            return Instant.now();
        }

        @Override
        public void postProcess(Recording recording) {
        }

        @Override
        public File getTarget() {
            return tempFile();
        }

        @Override
        public String getPath(Model model) {
            return "";
        }

        @Override
        public boolean isSingleFile() {
            return true;
        }

        @Override
        public long getSizeInByte() {
            return 0;
        }

        @Override
        public void awaitEnd() {
        }

        @Override
        public AtomicLong getDownloadedBytes() {
            return downloadedBytes;
        }

        @Override
        public RecordingProcess call() {
            return this;
        }

        private static File tempFile() {
            try {
                return File.createTempFile("ctbrec-stop-workflow", ".ts");
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't create temp file", e);
            }
        }
    }
}
