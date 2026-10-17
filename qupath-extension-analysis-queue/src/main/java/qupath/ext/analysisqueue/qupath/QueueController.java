/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import javafx.application.Platform;
import qupath.ext.analysisqueue.persistence.DiskJobStore;
import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.queue.AnalysisQueue;
import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.workflow.AnalysisInput;
import qupath.ext.analysisqueue.workflow.Synj1Processor;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Composition boundary connecting the reusable queue to the native SYNJ1 workflow. */
public final class QueueController implements AutoCloseable {
    private final QuPathGUI gui;
    private final DiskJobStore store;
    private final AnalysisQueue queue;
    private final java.util.concurrent.ExecutorService preparation = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "analysis-queue-input"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;
    private final javafx.beans.property.BooleanProperty pausePreference = PathPrefs.createPersistentPreference("analysisQueuePaused", false);

    public QueueController(QuPathGUI gui, Path root) throws Exception {
        this.gui = gui; store = new DiskJobStore(root);
        try { queue = new AnalysisQueue(store, new Synj1Processor(new NativeResultPublisher(gui, FxExecutor.application()))); }
        catch (Exception e) { store.close(); throw e; }
        queue.setPaused(pausePreference.get()); queue.start();
    }
    public Job enqueueCurrent() throws Exception {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Capture must run on the application thread.");
        var project = gui.getProject(); var data = gui.getImageData();
        if (project == null || data == null || project.getEntry(data) == null) throw new IllegalStateException("Open a project image and draw its outer analysis region first.");
        var entry = project.getEntry(data);
        String key = project.getPath().toAbsolutePath().normalize() + "#" + entry.getID();
        if (queue.containsActive(key)) throw new IllegalStateException("This image is already waiting or processing.");
        var job = Job.preparing(key, entry.getImageName());
        var selection = new ArrayList<>(data.getHierarchy().getSelectionModel().getSelectedObjects());
        var directory = store.directory(job.id());
        var capture = new InputCapture();
        var frozen = capture.freeze(project, data, selection, job, directory);
        try {
            queue.enqueue(job);
            preparation.submit(() -> prepare(capture, frozen));
        } catch (Exception error) {
            frozen.close();
            throw error;
        }
        return job;
    }
    private void prepare(InputCapture capture, InputCapture.Frozen frozen) {
        try {
            try (frozen) { capture.complete(frozen, FxExecutor.application(), () -> !closed); }
            queue.inputReady(frozen.job().id());
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not prepare queue input {}", frozen.job().id(), error);
            try {
                Files.deleteIfExists(frozen.directory().resolve("input.qpdata"));
                queue.preparationFailed(frozen.job().id(), java.util.Objects.toString(error.getMessage(), error.toString()));
            } catch (Exception storageError) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Could not record input failure", storageError);
            }
        }
    }
    public List<Job> jobs() { return queue.snapshot(); }
    public boolean paused() { return queue.paused(); }
    public String error() { return queue.storageError(); }
    public void pause(boolean value) { pausePreference.set(value); queue.setPaused(value); }
    public void retry(Job job) throws Exception { queue.retry(job.id()); }
    public void removeWaiting(Job job) throws Exception {
        queue.cancelWaiting(job.id());
        Files.deleteIfExists(store.directory(job.id()).resolve("input.qpdata"));
    }
    public Path results(Job job) throws Exception {
        if (job.outputPath() != null) return Path.of(job.outputPath());
        if (!Files.exists(store.directory(job.id()).resolve("input.json"))) return store.directory(job.id());
        var input = JsonFiles.read(store.directory(job.id()).resolve("input.json"), AnalysisInput.class);
        var folder = Path.of(input.outputDirectory());
        return Files.exists(folder) ? folder : store.directory(job.id());
    }
    @Override public void close() { closed = true; preparation.shutdown(); queue.close(); }
}
