/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.projects;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A native project save prepared from an independently owned ImageData.
 * Serialization and verification can run on a worker; commit only checks file
 * versions and moves the prepared files. The caller must also guard live edits.
 * Close on the worker after commit (or abandonment) to remove staging files.
 */
public final class PreparedImageDataSave implements AutoCloseable {
    private record Version(long size, Object modified, Object created, Object key) {
        static Version read(Path path) throws IOException {
            try {
                var a = Files.readAttributes(path, BasicFileAttributes.class);
                return new Version(a.size(), a.lastModifiedTime(), a.creationTime(), a.fileKey());
            } catch (java.nio.file.NoSuchFileException e) { return null; }
        }
    }
    private final Object owner;
    private final List<Path> targets;
    private final List<Version> versions;
    private final Path directory;
    private final Runnable committed;
    private boolean finished;
    private boolean retainRecovery;

    PreparedImageDataSave(Object owner, List<Path> targets, Runnable committed) throws IOException {
        this.owner = owner;
        this.targets = List.copyOf(targets);
        this.committed = committed;
        versions = new ArrayList<>();
        for (var path : targets) versions.add(Version.read(path));
        directory = Files.createTempDirectory(targets.getFirst().getParent(), ".prepared-save-");
        try {
            // Copy rollback files on the worker. Commit can atomically replace the
            // authoritative data file without ever making it disappear, even if
            // the process exits between publishing data and its JSON summaries.
            for (int i = 0; i < targets.size(); i++)
                if (versions.get(i) != null) Files.copy(targets.get(i), backup(i));
        } catch (IOException error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    /** Native data file to verify before committing. */
    public Path stagedPath() { return staged(0); }
    /** Final native data path. */
    public Path targetPath() { return targets.getFirst(); }
    Path staged(int index) { return directory.resolve("new-" + index); }
    private Path backup(int index) { return directory.resolve("previous-" + index); }

    /** Fast optimistic concurrency check; performs no content hashing. */
    public boolean sourceUnchanged() throws IOException {
        for (int i = 0; i < targets.size(); i++)
            if (!Objects.equals(versions.get(i), Version.read(targets.get(i)))) return false;
        return true;
    }

    /**
     * Publish the already verified files, rejecting a concurrent native save.
     * Synchronizes only this short operation with the entry's other saves.
     */
    public void commit() throws IOException {
        synchronized (owner) {
            if (finished) throw new IllegalStateException("Prepared save is already finished");
            if (!sourceUnchanged()) throw new IOException("Project image changed while its save was being prepared");
            var installed = new ArrayList<Integer>();
            try {
                for (int i = 0; i < targets.size(); i++) {
                    Files.move(staged(i), targets.get(i), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    installed.add(i);
                }
                committed.run();
                finished = true;
            } catch (IOException | RuntimeException error) {
                for (int i = targets.size() - 1; i >= 0; i--) {
                    try {
                        if (installed.contains(i)) {
                            if (versions.get(i) == null) Files.deleteIfExists(targets.get(i));
                            else Files.move(backup(i), targets.get(i), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException recovery) {
                        retainRecovery = true;
                        error.addSuppressed(recovery);
                    }
                }
                throw error;
            }
        }
    }

    @Override public void close() throws IOException {
        finished = true;
        if (retainRecovery) throw new IOException("Recovery files retained at " + directory);
        for (int i = 0; i < targets.size(); i++) {
            Files.deleteIfExists(staged(i));
            Files.deleteIfExists(backup(i));
        }
        Files.deleteIfExists(directory);
    }
}
