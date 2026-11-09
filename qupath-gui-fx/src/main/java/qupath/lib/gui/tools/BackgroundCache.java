/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.tools;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyLongProperty;
import javafx.beans.property.ReadOnlyLongWrapper;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Non-blocking cache for expensive display values. Work runs on the supplied executor;
 * completion notifications run on JavaFX. Invalidated tasks cannot publish stale results.
 * @param <K> immutable request key
 * @param <V> result type
 */
public final class BackgroundCache<K, V> {

    // Limit each view's queued work. Its next completion prompts consumers to retry deferred requests.
    private static final int MAX_PENDING = 128;

    private final Executor executor;
    private final Map<K, Entry> entries = new HashMap<>();
    private final ReadOnlyLongWrapper revision = new ReadOnlyLongWrapper();
    private final AtomicBoolean notificationPending = new AtomicBoolean();
    private int pending;

    private final class Entry {
        private FutureTask<Void> task;
        private V value;
        private boolean pending = true;
    }

    public BackgroundCache(Executor executor) {
        this.executor = executor;
    }

    public ReadOnlyLongProperty revisionProperty() {
        return revision.getReadOnlyProperty();
    }

    /** Return a completed value, or queue one calculation and return null immediately. */
    public synchronized V get(K key, Supplier<V> calculation) {
        var existing = entries.get(key);
        if (existing != null)
            return existing.value;
        if (pending >= MAX_PENDING)
            return null;
        var entry = new Entry();
        entries.put(key, entry);
        pending++;
        entry.task = new FutureTask<>(() -> {
            V value = null;
            try {
                if (!Thread.currentThread().isInterrupted())
                    value = calculation.get();
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted())
                    LoggerFactory.getLogger(BackgroundCache.class).warn("Background display calculation failed", e);
            }
            synchronized (BackgroundCache.this) {
                if (entries.get(key) != entry || Thread.currentThread().isInterrupted())
                    return null;
                entry.value = value;
                entry.pending = false;
                pending--;
            }
            requestRefresh();
            return null;
        });
        try {
            executor.execute(entry.task);
        } catch (RejectedExecutionException e) {
            // A full queue never pushes work back onto the calling/UI thread.
            entries.remove(key);
            pending--;
        }
        return null;
    }

    public synchronized void invalidate(K key) {
        var entry = entries.remove(key);
        if (entry != null)
            cancel(entry);
    }

    public synchronized void clear() {
        entries.values().forEach(this::cancel);
        entries.clear();
    }

    private void cancel(Entry entry) {
        if (entry.pending) {
            entry.pending = false;
            pending--;
        }
        entry.task.cancel(true);
        if (executor instanceof ThreadPoolExecutor pool)
            pool.remove(entry.task);
    }

    /** Ask consumers to read again after a dependency changes, coalescing JavaFX notifications. */
    public void requestRefresh() {
        if (notificationPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                notificationPending.set(false);
                revision.set(revision.get() + 1);
            });
        }
    }
}
