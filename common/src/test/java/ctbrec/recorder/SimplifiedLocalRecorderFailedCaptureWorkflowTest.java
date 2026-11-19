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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimplifiedLocalRecorderFailedCaptureWorkflowTest {

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
    void singleRecordingLoop_marksFailedCaptureInsteadOfDeletingEmptyRecording() throws Exception {
        Settings settings = new Settings();
        settings.recordingsDir = tempDir.resolve("recordings").toString();
        recorder = createRecorder(settings);

        Model model = mockModel("failed-model");
        FailedCaptureProcess process = new FailedCaptureProcess(
                model,
                tempDir.resolve("recordings/failed-model/failed.ts").toFile(),
                "/failed-model/failed.ts",
                Set.of(),
                0L
        );

        Recording recording = new Recording(settings.recordingsDir);
        recording.setId(UUID.randomUUID().toString());
        recording.setModel(model);
        recording.setStartDate(Instant.now());
        recording.setRecordingProcess(process);
        recording.setAbsoluteFile(process.getTarget());

        recordingManager().add(recording);
        recorder.getRecordingProcesses().add(process);
        recorder.getRecordings().add(recording);

        invokePrivateVoid(recorder, "singleRecordingLoop", recording, 0L);

        assertEquals(Recording.State.FAILED, recording.getStatus());
        assertTrue(Files.exists(Path.of(recording.getMetaDataFile())), "Failed capture metadata should be preserved");
    }

    @Test
    void createRecording_registersManagedArtifacts() throws Exception {
        Settings settings = new Settings();
        settings.recordingsDir = tempDir.resolve("recordings").toString();
        recorder = createRecorder(settings);

        Model model = mockModel("artifact-model");
        File target = tempDir.resolve("recordings/artifact-model/output.ts").toFile();
        File workspace = tempDir.resolve("recordings/artifact-model/output.ts.llhls-work").toFile();
        FailedCaptureProcess process = new FailedCaptureProcess(
                model,
                target,
                "/artifact-model/output.ts",
                Set.of(workspace),
                0L
        );

        Recording recording = invokeCreateRecording(process);

        assertTrue(recording.getAssociatedFiles().contains(workspace.getCanonicalPath()));
    }

    @Test
    void recordingManagerTreatsFailedRecordingWithManagedArtifactsAsExisting() throws Exception {
        Settings settings = new Settings();
        settings.recordingsDir = tempDir.resolve("recordings").toString();
        recorder = createRecorder(settings);

        Path workspace = tempDir.resolve("recordings/persisted.ts.llhls-work");
        Files.createDirectories(workspace);

        Recording recording = new Recording(settings.recordingsDir);
        recording.setModel(mockModel("persisted-model"));
        recording.setStartDate(Instant.now());
        recording.setAbsoluteFile(tempDir.resolve("recordings/persisted.ts").toFile());
        recording.setStatus(Recording.State.FAILED);
        recording.getAssociatedFiles().add(workspace.toFile().getCanonicalPath());

        Method recordingExists = RecordingManager.class.getDeclaredMethod("recordingExists", Recording.class);
        recordingExists.setAccessible(true);

        boolean exists = (boolean) recordingExists.invoke(recordingManager(), recording);

        assertTrue(exists);
    }

    private SimplifiedLocalRecorder createRecorder(Settings settings) throws IOException {
        Config config = mock(Config.class);
        when(config.getSettings()).thenReturn(settings);
        when(config.getConfigDir()).thenReturn(tempDir.resolve("config").toFile());
        return new SimplifiedLocalRecorder(config, List.of());
    }

    private RecordingManager recordingManager() throws ReflectiveOperationException {
        Field recordingManagerField = SimplifiedLocalRecorder.class.getDeclaredField("recordingManager");
        recordingManagerField.setAccessible(true);
        return (RecordingManager) recordingManagerField.get(recorder);
    }

    private Recording invokeCreateRecording(RecordingProcess process) throws ReflectiveOperationException {
        Method createRecording = SimplifiedLocalRecorder.class.getDeclaredMethod("createRecording", RecordingProcess.class);
        createRecording.setAccessible(true);
        return (Recording) createRecording.invoke(recorder, process);
    }

    private static void invokePrivateVoid(Object target, String methodName, Object... args)
            throws ReflectiveOperationException {
        // this finds first method by name, irrespectable of parameter types
        Method method = Arrays.stream(target.getClass().getDeclaredMethods()).filter(x -> x.getName().equals(methodName)).findFirst().get();
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static Model mockModel(String name) {
        Model model = mock(Model.class);
        when(model.getName()).thenReturn(name);
        when(model.getDisplayName()).thenReturn(name);
        when(model.getSanitizedNamed()).thenReturn(name);
        when(model.getUrl()).thenReturn("https://example.com/" + name + '/');
        return model;
    }

    private static final class FailedCaptureProcess implements RecordingProcess {
        private final AtomicLong downloadedBytes = new AtomicLong();
        private final Model model;
        private final File target;
        private final String relativePath;
        private final Set<File> managedArtifacts;
        private final long sizeInBytes;

        private FailedCaptureProcess(Model model,
                                     File target,
                                     String relativePath,
                                     Set<File> managedArtifacts,
                                     long sizeInBytes) {
            this.model = model;
            this.target = target;
            this.relativePath = relativePath;
            this.managedArtifacts = managedArtifacts;
            this.sizeInBytes = sizeInBytes;
        }

        @Override
        public void init(Config config, Model model, Instant startTime, ExecutorService executorService) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void finalizeDownload() {
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public Model getModel() {
            return model;
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
            return target;
        }

        @Override
        public String getPath(Model model) {
            return relativePath;
        }

        @Override
        public boolean isSingleFile() {
            return true;
        }

        @Override
        public long getSizeInByte() {
            return sizeInBytes;
        }

        @Override
        public void awaitEnd() {
        }

        @Override
        public AtomicLong getDownloadedBytes() {
            return downloadedBytes;
        }

        @Override
        public boolean isFailedCapture() {
            return true;
        }

        @Override
        public Set<File> getManagedArtifacts() {
            return managedArtifacts;
        }

        @Override
        public RecordingProcess call() {
            return this;
        }
    }
}
