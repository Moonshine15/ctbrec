package ctbrec.sites.chaturbate;

import ctbrec.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChaturbateLlhlsDownloadLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void applyFetchedSegmentIfRunning_writesSegmentWhileRunning() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        ChaturbateLlhlsSegment segment = new ChaturbateLlhlsSegment(
                133, "https://example.com/seg_000133.m4s", 1.5f, null, false, null);

        assertTrue(download.applyFetchedSegmentIfRunning(workspace, segment, null, new byte[] {1, 2, 3}));
        byte[] stored = workspace.serveSegment("seg_000001.m4s");
        assertEquals(3, stored.length);
    }

    @Test
    void applyFetchedSegmentIfRunning_skipsWorkspaceMutationAfterStop() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        setPrivateField(download, "running", false);

        ChaturbateLlhlsInMemoryWorkspace workspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        ChaturbateLlhlsSegment segment = new ChaturbateLlhlsSegment(
                133, "https://example.com/seg_000133.m4s", 1.5f, null, false, null);

        assertFalse(download.applyFetchedSegmentIfRunning(workspace, segment, null, new byte[] {1, 2, 3}));
        assertTrue(workspace.isEmpty());
    }

    @Test
    void isStillRunning_returnsFalseAfterStop() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        setPrivateField(download, "running", false);

        assertFalse(download.isStillRunning());
    }

    @Test
    void isStillRunning_returnsTrueWhileRunning() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);

        assertTrue(download.isStillRunning());
    }

    @Test
    void getLivePlaybackStreamSource_returnsLocalMasterPlaylist() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        ChaturbateLlhlsInMemoryWorkspace videoWorkspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        ChaturbateLlhlsInMemoryWorkspace audioWorkspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        ChaturbateLlhlsMediaServer mediaServer = new ChaturbateLlhlsMediaServer(videoWorkspace, audioWorkspace);
        mediaServer.start();

        try {
            ChaturbateStreamSource selectedSource = new ChaturbateStreamSource();
            selectedSource.setBandwidth(2_000_000);
            selectedSource.setWidth(1280);
            selectedSource.setHeight(720);
            setPrivateField(download, "selectedStreamSource", selectedSource);
            setPrivateField(download, "mediaServer", mediaServer);

            Optional<ctbrec.recorder.download.StreamSource> liveSource = download.getLivePlaybackStreamSource();

            assertTrue(liveSource.isPresent());
            assertEquals(mediaServer.getMasterPlaylistUrl(), liveSource.get().getMediaPlaylistUrl());
            assertEquals(mediaServer.getMasterPlaylistUrl(), liveSource.get().getMasterPlaylistUrl());
            assertEquals(720, liveSource.get().getHeight());
            assertEquals(1280, liveSource.get().getWidth());
            assertEquals(2_000_000, liveSource.get().getBandwidth());
        } finally {
            mediaServer.stop();
        }
    }

    @Test
    void finalizeDownload_marksWorkspaceAsFinalized() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        setPrivateField(download, "running", false);
        setPrivateField(download, "preserveWorkspace", true);
        setPrivateField(download, "workspaceDir", tempDir);

        ChaturbateLlhlsInMemoryWorkspace videoWorkspace = new ChaturbateLlhlsInMemoryWorkspace("video", 2);
        ChaturbateLlhlsInMemoryWorkspace audioWorkspace = new ChaturbateLlhlsInMemoryWorkspace("audio", 2);
        setPrivateField(download, "videoWorkspace", videoWorkspace);
        setPrivateField(download, "audioWorkspace", audioWorkspace);

        download.finalizeDownload();

        // Forensic dump should have created the track directories
        assertTrue(Files.exists(tempDir.resolve("video/playlist.m3u8")));
        assertTrue(Files.exists(tempDir.resolve("audio/playlist.m3u8")));
    }

    @Test
    void waitForFfmpegToExit_skipsQuitCommandWhenProcessAlreadyExited() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        TrackingOutputStream controlStream = new TrackingOutputStream(false, true);
        setPrivateField(download, "ffmpegStdIn", controlStream);
        setPrivateField(download, "ffmpegProcess", new FakeProcess(false, 0));

        assertDoesNotThrow(() -> invokePrivateVoid(download, "waitForFfmpegToExit"));

        assertEquals(0, controlStream.writeCalls);
        assertEquals(1, controlStream.closeCalls);
        assertNull(getPrivateField(download, "ffmpegStdIn"));
        assertNull(getPrivateField(download, "ffmpegProcess"));
    }

    @Test
    void waitForFfmpegToExit_requestsQuitCommandWhileProcessIsAlive() throws Exception {
        ChaturbateLlhlsDownload download = new ChaturbateLlhlsDownload(null, false, null);
        TrackingOutputStream controlStream = new TrackingOutputStream(false, false);
        setPrivateField(download, "ffmpegStdIn", controlStream);
        setPrivateField(download, "ffmpegProcess", new FakeProcess(true, 0));

        assertDoesNotThrow(() -> invokePrivateVoid(download, "waitForFfmpegToExit"));

        assertTrue(controlStream.writeCalls > 0);
        assertEquals(1, controlStream.closeCalls);
        assertNull(getPrivateField(download, "ffmpegStdIn"));
        assertNull(getPrivateField(download, "ffmpegProcess"));
    }

    @Test
    void handleSessionRefreshRequired_stopsCleanlyWhenModelIsNoLongerOnline() throws Exception {
        TestableChaturbateLlhlsDownload download = new TestableChaturbateLlhlsDownload();
        download.throwOnRefresh = true;

        Model model = mock(Model.class);
        when(model.updateOnlineState(false)).thenReturn(Model.State.OFFLINE);
        setPrivateField(download, "model", model);

        download.handleSessionRefreshRequired(new IOException("403 while fetching playlist"));

        assertFalse(download.isRunning());
        assertFalse(download.isFailedCapture());
        verify(model, never()).delay();
    }

    // @Test
    void handleSessionRefreshRequired_marksFailedCaptureWhenModelStillLooksOnline() throws Exception {
        TestableChaturbateLlhlsDownload download = new TestableChaturbateLlhlsDownload();
        download.throwOnRefresh = true;

        Model model = mock(Model.class);
        when(model.updateOnlineState(false)).thenReturn(Model.State.ONLINE);
        setPrivateField(download, "model", model);
        setPrivateField(download, "workspaceDir", tempDir);

        download.handleSessionRefreshRequired(new IOException("403 while fetching playlist"));

        assertFalse(download.isRunning());
        assertTrue(download.isFailedCapture());
        verify(model).delay();
    }

    @Test
    void handleSessionRefreshRequired_reschedulesImmediatelyAfterSuccessfulRefresh() throws Exception {
        TestableChaturbateLlhlsDownload download = new TestableChaturbateLlhlsDownload();
        setPrivateField(download, "rescheduleTime", java.time.Instant.EPOCH);

        download.handleSessionRefreshRequired(new IOException("403 while fetching playlist"));

        assertTrue(download.refreshed);
        assertTrue(download.getRescheduleTime().isAfter(java.time.Instant.EPOCH));
        assertTrue(download.isRunning());
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getPrivateField(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void invokePrivateVoid(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final boolean failOnWrite;
        private final boolean failOnClose;
        private int writeCalls;
        private int closeCalls;

        private TrackingOutputStream(boolean failOnWrite, boolean failOnClose) {
            this.failOnWrite = failOnWrite;
            this.failOnClose = failOnClose;
        }

        @Override
        public void write(int b) throws IOException {
            writeCalls++;
            if (failOnWrite) {
                throw new IOException("pipe already closed");
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writeCalls++;
            if (failOnWrite) {
                throw new IOException("pipe already closed");
            }
        }

        @Override
        public void close() throws IOException {
            closeCalls++;
            if (failOnClose) {
                throw new IOException("pipe already closed");
            }
        }
    }

    private static final class FakeProcess extends Process {
        private final int exitCode;
        private boolean alive;

        private FakeProcess(boolean alive, int exitCode) {
            this.alive = alive;
            this.exitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class TestableChaturbateLlhlsDownload extends ChaturbateLlhlsDownload {
        private boolean throwOnRefresh;
        private boolean refreshed;

        private TestableChaturbateLlhlsDownload() {
            super(null, false, null);
        }

        @Override
        void refreshSelectedStreamSource() throws IOException {
            if (throwOnRefresh) {
                throw new IOException("refresh failed");
            }
            refreshed = true;
        }
    }
}
