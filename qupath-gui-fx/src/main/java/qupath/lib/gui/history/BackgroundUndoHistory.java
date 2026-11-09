/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.history;

import javafx.application.Platform;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.background.BackgroundTasks;
import qupath.lib.gui.viewer.ImageRenderingPreparation;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/** Owner-thread snapshots, worker serialization, and guarded owner-thread undo publication. */
public final class BackgroundUndoHistory {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(BackgroundUndoHistory.class);
    private final SerializableUndoRedoStack<PathObjectHierarchy> stack = new SerializableUndoRedoStack<>(null);
    private final Runnable changed;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile long generation;
    private volatile long sequence;
    private boolean disposed, hasCurrent, moving;
    private int undoCount, redoCount;

    public BackgroundUndoHistory(Runnable changed) { this.changed = changed; }
    public boolean canUndo() { return !disposed && !moving && undoCount > 0; }
    public boolean canRedo() { return !disposed && !moving && redoCount > 0; }
    public long totalBytes() { return stack.totalBytes(); }

    public void record(ImageData<BufferedImage> data, int limit) {
        if (disposed) return;
        ImageDataSnapshot snapshot;
        try { snapshot = ImageDataSnapshot.capture(data); }
        catch (RuntimeException error) {
            logger.error("Unable to snapshot undo history; current annotations are retained", error);
            clear();
            return;
        }
        long epoch = generation, order = ++sequence;
        if (hasCurrent) undoCount = limit > 0 ? Math.min(limit, undoCount + 1) : undoCount + 1;
        hasCurrent = true;
        redoCount = 0;
        var done = new CompletableFuture<Void>();
        tail = done;
        BackgroundTasks.submitIO(() -> {
            byte[] bytes = null;
            Throwable failure = null;
            try (snapshot) {
                // Entries already beyond the configured undo depth need not be serialized.
                if (generation == epoch && (limit <= 0 || order >= sequence - limit))
                    bytes = SerializableUndoRedoStack.serializeObject(snapshot.data().getHierarchy(), stack.initialSize());
            } catch (Throwable error) { failure = error; }
            var saved = bytes;
            var error = failure;
            Platform.runLater(() -> {
                try {
                    if (!disposed && generation == epoch && (limit <= 0 || order >= sequence - limit)) {
                        if (saved == null) {
                            logger.error("Unable to record undo history; current annotations are retained", error);
                            clear();
                        } else {
                            stack.addSerialized(saved, limit);
                            if (order == sequence) { undoCount = stack.undoCount(); redoCount = stack.redoCount(); }
                        }
                    }
                } finally {
                    done.complete(null);
                    changed.run();
                }
            });
        });
        changed.run();
    }

    /** Waits responsively for pending snapshots and rejects edits made during the wait. */
    public PathObjectHierarchy move(boolean redo, BooleanSupplier stillCurrent) throws IOException {
        if (disposed || moving) return null;
        moving = true;
        changed.run();
        try { return moveCurrent(redo, stillCurrent); }
        finally { moving = false; changed.run(); }
    }
    private PathObjectHierarchy moveCurrent(boolean redo, BooleanSupplier stillCurrent) throws IOException {
        var pending = tail;
        BackgroundTasks.io(() -> { pending.get(); return null; });
        if (disposed || !stillCurrent.getAsBoolean()) return null;
        var move = stack.prepareMove(redo);
        if (move == null) return null;
        PathObjectHierarchy hierarchy = BackgroundTasks.io(() -> {
            var restored = move.readObject();
            if (restored != null) ImageRenderingPreparation.prepare(restored);
            return restored;
        });
        if (hierarchy == null || disposed || !stillCurrent.getAsBoolean() || !stack.commitMove(move)) return null;
        undoCount = stack.undoCount();
        redoCount = stack.redoCount();
        changed.run();
        return hierarchy;
    }

    public void clear() {
        generation++;
        stack.clear(); hasCurrent = false; undoCount = 0; redoCount = 0;
        changed.run();
    }
    public void dispose() { disposed = true; clear(); }
}
