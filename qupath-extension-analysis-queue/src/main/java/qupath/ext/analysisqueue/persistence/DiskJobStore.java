/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.persistence;

import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.queue.JobStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.*;

public final class DiskJobStore implements JobStore {
    private final Path root;
    private final FileChannel channel;
    private final FileLock lock;
    public DiskJobStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        channel = FileChannel.open(this.root.resolve("queue.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = channel.tryLock(); }
        catch (OverlappingFileLockException e) { acquired = null; }
        if (acquired == null) { channel.close(); throw new IOException("Another QuPath instance owns this analysis queue."); }
        lock = acquired;
    }
    @Override public Path directory(String id) {
        UUID.fromString(id); // Job IDs are directory components, never arbitrary paths.
        return root.resolve("jobs").resolve(id);
    }
    @Override public List<Job> load() throws IOException {
        var jobs = new ArrayList<Job>();
        var directory = root.resolve("jobs");
        if (!Files.exists(directory)) return jobs;
        try (var paths = Files.list(directory)) {
            for (var path : paths.toList()) {
                var file = path.resolve("job.json");
                if (!Files.exists(file)) continue; // Input preparation did not enqueue a job.
                var job = JsonFiles.read(file, Job.class);
                if (job == null || job.state() == null || !directory(job.id()).equals(path))
                    throw new IOException("Invalid queue record: " + file);
                jobs.add(job);
            }
        }
        jobs.sort(Comparator.comparing(Job::createdAt).thenComparing(Job::id));
        return jobs;
    }
    @Override public void save(Job job) throws IOException { JsonFiles.write(directory(job.id()).resolve("job.json"), job); }
    @Override public void close() throws IOException { if (lock.isValid()) lock.release(); channel.close(); }
}
