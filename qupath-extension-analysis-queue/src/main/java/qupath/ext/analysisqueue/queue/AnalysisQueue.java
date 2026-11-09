/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.queue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One worker owns dispatch. No GUI or analysis-specific dependencies. */
public final class AnalysisQueue implements AutoCloseable {
    private final JobStore store;
    private final JobProcessor processor;
    private final List<Job> jobs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "analysis-queue-worker"); thread.setDaemon(true); return thread;
    });
    private boolean paused;
    private boolean scheduled;
    private boolean closed;
    private String storageError = "";

    public AnalysisQueue(JobStore store, JobProcessor processor) throws IOException {
        this.store = store; this.processor = processor;
        for (var job : store.load()) {
            if (job.state() == Job.State.PREPARING) {
                job = job.finish(Job.State.PREPARATION_FAILED, "Application closed before input was ready. Add the image again.", job.outputPath());
                store.save(job);
            }
            if (job.state() == Job.State.PROCESSING) {
                job = job.finish(Job.State.INTERRUPTED, "Application closed during processing. Retry to use the saved input.", job.outputPath());
                store.save(job);
            }
            jobs.add(job);
        }
    }
    public synchronized List<Job> snapshot() { return List.copyOf(jobs); }
    public synchronized boolean paused() { return paused; }
    public synchronized String storageError() { return storageError; }
    public synchronized boolean containsActive(String key) {
        return jobs.stream().anyMatch(j -> j.active() && j.imageKey().equals(key));
    }
    public synchronized void enqueue(Job job) throws IOException {
        if (closed) throw new IllegalStateException("Queue is closed");
        if (containsActive(job.imageKey())) throw new IllegalArgumentException("This image is already waiting or processing.");
        if (jobs.stream().anyMatch(j -> j.id().equals(job.id()))) throw new IllegalArgumentException("Duplicate job");
        store.save(job); jobs.add(job); start();
    }
    public synchronized void start() {
        if (!closed && !paused && !scheduled && nextWaiting() != null) {
            scheduled = true; worker.submit(this::drain);
        }
    }
    private Job nextWaiting() {
        var first = jobs.stream().filter(Job::active).findFirst().orElse(null);
        return first != null && first.state() == Job.State.WAITING ? first : null;
    }
    public synchronized void inputReady(String id) throws IOException {
        if (closed) return; // A durable Preparing row is recovered on the next launch.
        if (find(id).state() != Job.State.PREPARING) throw new IllegalStateException("Input is not preparing");
        try { replace(find(id).retry()); start(); }
        catch (IOException error) { pauseForStorageFailure(error); throw error; }
    }
    public synchronized void preparationFailed(String id, String message) throws IOException {
        if (closed) return;
        try { replace(find(id).finish(Job.State.PREPARATION_FAILED, message + " Add the image again.", null)); start(); }
        catch (IOException error) { pauseForStorageFailure(error); throw error; }
    }
    public synchronized void setPaused(boolean value) { paused = value; if (!value) start(); }
    public synchronized void cancelWaiting(String id) throws IOException {
        var job = find(id);
        if (job.state() != Job.State.WAITING) throw new IllegalStateException("Only waiting jobs can be removed.");
        replace(job.finish(Job.State.CANCELLED, "Removed from queue", job.outputPath()));
    }
    public synchronized void retry(String id) throws IOException {
        var job = find(id);
        if (job.state() != Job.State.FAILED && job.state() != Job.State.INTERRUPTED)
            throw new IllegalStateException("Requeue the current outline for completed or changed inputs.");
        if (containsActive(job.imageKey())) throw new IllegalStateException("This image already has an active job.");
        replace(job.retry()); start();
    }
    private Job find(String id) { return jobs.stream().filter(j -> j.id().equals(id)).findFirst().orElseThrow(); }
    private void replace(Job job) throws IOException {
        store.save(job);
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).id().equals(job.id())) { jobs.set(i, job); return; }
        throw new IllegalArgumentException("Unknown job " + job.id());
    }
    private void drain() {
        try {
            while (true) {
                Job job;
                synchronized (this) {
                    if (closed || paused) return;
                    job = nextWaiting();
                    if (job == null) return;
                    job = job.processing("Opening saved input"); replace(job);
                }
                var current = job;
                try {
                    var outcome = processor.process(job, store.directory(job.id()), phase -> updatePhase(current.id(), phase));
                    synchronized (this) { replace(find(job.id()).finish(outcome.state(), outcome.message(), outcome.outputPath())); }
                    if (outcome.state() == Job.State.COMPLETED) {
                        try { processor.releaseCompletedInput(current, store.directory(current.id())); }
                        catch (Exception cleanupError) {
                            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Completed result saved; temporary input retained for {}", current.id(), cleanupError);
                        }
                    }
                } catch (Exception error) {
                    synchronized (this) {
                        replace(find(job.id()).finish(Job.State.FAILED,
                                error.getClass().getSimpleName() + ": " + error.getMessage(), find(job.id()).outputPath()));
                    }
                }
            }
        } catch (IOException error) {
            synchronized (this) { pauseForStorageFailure(error); }
        } finally {
            synchronized (this) {
                scheduled = false;
                if (closed) closeStore();
                else start();
            }
        }
    }
    private synchronized void updatePhase(String id, String phase) {
        try { replace(find(id).processing(phase)); }
        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    private void pauseForStorageFailure(IOException error) {
        paused = true; storageError = "Queue storage failed: " + error.getMessage();
    }
    @Override public synchronized void close() {
        closed = true; worker.shutdown(); // Finish the current native call; never interrupt another slide's editor.
        if (!scheduled) closeStore();
    }
    private void closeStore() {
        try { store.close(); } catch (IOException error) { storageError = error.toString(); }
    }
}
