package ctbrec;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import ctbrec.io.HttpClient;
import ctbrec.recorder.Recorder;

// Stub recorder for PlayerServer
// Used to hold model info regardless of it being recorded
public class PlayerModelCache implements Recorder {
    private final List<Model> models = new ArrayList<>();
    private final ReentrantLock modelsLock = new ReentrantLock();

    @Override
    public void addModel(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        try {
            modelsLock.lock();
            models.add(model);
        } finally {
            modelsLock.unlock();
        }
        // TODO: add cleanup after a period of model inactivity. Otherwise this is equivalent to a memory leak
    }

    @Override
    public void stopRecording(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        try {
            modelsLock.lock();
            models.remove(model);
        } finally {
            modelsLock.unlock();
        }
    }

    @Override
    public void stopRecordingAt(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        throw new UnsupportedOperationException("Unimplemented method 'stopRecordingAt'");
    }

    @Override
    public void switchStreamSource(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        throw new UnsupportedOperationException("Unimplemented method 'switchStreamSource'");
    }

    @Override
    public boolean isTracked(Model model) {
        try {
            modelsLock.lock();
            return models.contains(model);
        } finally {
            modelsLock.unlock();
        }
    }

    @Override
    public List<Model> getModels() {
        return models;
    }

    @Override
    public List<Recording> getRecordings() throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getRecordings'");
    }

    @Override
    public void delete(Recording recording) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'delete'");
    }

    @Override
    public void pin(Recording recording) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'pin'");
    }

    @Override
    public void unpin(Recording recording) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'unpin'");
    }

    @Override
    public void shutdown(boolean immediately) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'shutdown'");
    }

    @Override
    public void suspendRecording(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'suspendRecording'");
    }

    @Override
    public void resumeRecording(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'resumeRecording'");
    }

    @Override
    public boolean isSuspended(Model model) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isSuspended'");
    }

    @Override
    public boolean isForcePriority(Model model) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isForcePriority'");
    }

    @Override
    public void forcePriorityRecording(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'forcePriorityRecording'");
    }

    @Override
    public void resumePriorityRecording(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'resumePriorityRecording'");
    }

    @Override
    public boolean isMarkedForLaterRecording(Model model) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isMarkedForLaterRecording'");
    }

    @Override
    public void markForLaterRecording(Model model, boolean mark)
            throws InvalidKeyException, NoSuchAlgorithmException, IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'markForLaterRecording'");
    }

    @Override
    public List<Model> getOnlineModels() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getOnlineModels'");
    }

    @Override
    public HttpClient getHttpClient() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getHttpClient'");
    }

    @Override
    public long getTotalSpaceBytes() throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getTotalSpaceBytes'");
    }

    @Override
    public long getFreeSpaceBytes() throws IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getFreeSpaceBytes'");
    }

    @Override
    public void rerunPostProcessing(Recording recording)
            throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'rerunPostProcessing'");
    }

    @Override
    public void priorityChanged(Model model) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'priorityChanged'");
    }

    @Override
    public void setNote(Recording rec, String note) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'setNote'");
    }

    @Override
    public void pause() throws InvalidKeyException, NoSuchAlgorithmException, IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'pause'");
    }

    @Override
    public void resume() throws InvalidKeyException, NoSuchAlgorithmException, IOException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'resume'");
    }

    @Override
    public int getModelCount() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getModelCount'");
    }

    @Override
    public int getGroupedModelCount() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getGroupedModelCount'");
    }

    @Override
    public Set<ModelGroup> getModelGroups() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getModelGroups'");
    }

    @Override
    public void saveModelGroup(ModelGroup group) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'saveModelGroup'");
    }

    @Override
    public void deleteModelGroup(ModelGroup group) throws IOException, InvalidKeyException, NoSuchAlgorithmException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'deleteModelGroup'");
    }
    
}
