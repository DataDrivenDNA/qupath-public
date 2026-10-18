/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.PreparedImageDataSave;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import qupath.lib.io.FileVersion;

/** Snapshot on FX, serialize and publish on the I/O worker, acknowledge on FX. */
public final class BackgroundImageSave {
    private static long sequence; // FX only
    private static final Map<Path, Published> PUBLISHED = new HashMap<>(); // I/O lane only
    private record Published(long sequence, WeakReference<ImageData<?>> source, FileVersion version) {}
    private BackgroundImageSave() {}

    /** @return true if the saved snapshot still contains all current edits. */
    public static boolean save(ImageData<BufferedImage> source,
                               ProjectImageEntry<BufferedImage> entry, Path file) throws IOException {
        var name = entry == null ? source.getServerMetadata().getName() : entry.getImageName();
        try (var progress = BackgroundProgress.start("Save — " + name, "Waiting for the I/O worker")) {
            return save(source, entry, file, progress);
        }
    }

    private static boolean save(ImageData<BufferedImage> source, ProjectImageEntry<BufferedImage> entry,
                                Path file, BackgroundProgress.Operation progress) throws IOException {
        var snapshot = ImageDataSnapshot.capture(source);
        var target = entry == null ? file.toAbsolutePath().normalize() : entry.getImageDataPath();
        var version = FileVersion.read(target);
        long request = ++sequence;
        var current = new AtomicBoolean();
        BackgroundTasks.io(() -> {
            try (snapshot) {
                progress.phase("Writing annotations and measurements");
                var actual = FileVersion.read(target);
                var previous = PUBLISHED.get(target);
                boolean followsOwnSave = previous != null && previous.sequence < request
                        && previous.source.get() == source && Objects.equals(previous.version, actual);
                if (!Objects.equals(version, actual) && !followsOwnSave)
                    throw new IOException("The saved image changed after Save was requested. Your current edits are kept; review the newer results before saving again.");
                PreparedImageDataSave staged;
                try { staged = entry == null ? PreparedImageDataSave.prepare(file, snapshot.data())
                        : entry.prepareImageDataSave(snapshot.data()); }
                catch (UnsupportedOperationException unsupported) {
                    // Other storage backends retain their native persistence implementation.
                    if (entry == null) throw unsupported;
                    entry.saveImageData(snapshot.data());
                    var saved = snapshot.data().getLastSavedPath();
                    return saved == null ? null : Path.of(saved);
                }
                try (var prepared = staged) {
                    progress.phase("Finalizing saved file");
                    prepared.commit();
                    if (target != null) PUBLISHED.put(target, new Published(request, new WeakReference<>(source), prepared.committedVersion()));
                    return prepared.targetPath();
                }
            }
        }, saved -> {
            progress.close();
            current.set(snapshot.unchanged() && !ImageDataSnapshot.hasUncommittedObjects(source));
            if (saved != null) source.setLastSavedPath(saved.toString(), current.get());
            else if (current.get()) source.setChanged(false);
            if (!current.get()) source.setChanged(true);
        });
        return current.get();
    }
}
