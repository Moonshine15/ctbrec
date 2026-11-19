package ctbrec.recorder;

import ctbrec.Config;
import ctbrec.Recording;
import ctbrec.Settings;
import ctbrec.sites.chaturbate.ChaturbateModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimplifiedLocalRecorderRerunPostProcessingTest {

    @TempDir
    Path tempDir;

    private SimplifiedLocalRecorder recorder;

    @AfterEach
    void afterEach() {
        if (recorder != null) {
            recorder.shutdown(true);
        }
    }

    // disable temporarily as the heavy part is moved out of init function. 
    // TODO: allow independent postprocessing or move it to finalization instead
    // @Test
    void rerunPostProcessing_forChaturbateRecordingDoesNotRequireLiveDownloadInitialization() throws Exception {
        Settings settings = new Settings();
        settings.recordingsDir = tempDir.resolve("recordings").toString();
        settings.postProcessingThreads = 1;
        recorder = createRecorder(settings);

        Path recordingFile = tempDir.resolve("recordings/chaturbate/example.ts");
        Files.createDirectories(recordingFile.getParent());
        Files.write(recordingFile, new byte[]{1, 2, 3});

        ChaturbateModel model = new ChaturbateModel();
        model.setName("testmodel");
        model.setUrl("https://chaturbate.com/testmodel/");

        Recording recording = new Recording(settings.recordingsDir);
        recording.setId(UUID.randomUUID().toString());
        recording.setModel(model);
        recording.setStartDate(Instant.now());
        recording.setAbsoluteFile(recordingFile.toFile());
        recording.setSingleFile(true);
        recording.setStatus(Recording.State.FAILED);

        addRecordingToManager(recording);

        assertDoesNotThrow(() -> recorder.rerunPostProcessing(recording));

        waitForStatus(recording, Recording.State.FINISHED);
        assertEquals(recordingFile.toFile(), recording.getRecordingProcess().getTarget());
    }

    private SimplifiedLocalRecorder createRecorder(Settings settings) throws IOException {
        Config config = mock(Config.class);
        when(config.getSettings()).thenReturn(settings);
        when(config.getConfigDir()).thenReturn(tempDir.resolve("config").toFile());
        return new SimplifiedLocalRecorder(config, List.of());
    }

    @SuppressWarnings("unchecked")
    private void addRecordingToManager(Recording recording) throws ReflectiveOperationException {
        Field recordingManagerField = SimplifiedLocalRecorder.class.getDeclaredField("recordingManager");
        recordingManagerField.setAccessible(true);
        Object recordingManager = recordingManagerField.get(recorder);

        Field recordingsField = RecordingManager.class.getDeclaredField("recordings");
        recordingsField.setAccessible(true);
        ((List<Recording>) recordingsField.get(recordingManager)).add(recording);
    }

    private static void waitForStatus(Recording recording, Recording.State expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (recording.getStatus() == expectedStatus) {
                return;
            }
            Thread.sleep(25);
        }
        assertEquals(expectedStatus, recording.getStatus());
    }
}
