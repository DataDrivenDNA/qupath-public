/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Operation lifetime and stage reporting; reporting never waits for the UI thread. */
public final class BackgroundProgress {
    private static final ObservableList<Operation> ACTIVE = FXCollections.observableArrayList();
    private static final ObservableList<Operation> VIEW = FXCollections.unmodifiableObservableList(ACTIVE);
    private BackgroundProgress() {}

    /** Read on JavaFX only. Completed or failed operations are removed, not retained as history. */
    public static ObservableList<Operation> activeOperations() { return VIEW; }

    /** May be called from FX or a worker. The caller closes the handle in a finally block. */
    public static Operation start(String title, String phase) {
        var operation = new Operation(Objects.requireNonNull(title), Objects.requireNonNull(phase));
        onFx(() -> { if (!operation.closed.get()) ACTIVE.add(operation); });
        return operation;
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    public static final class Operation implements AutoCloseable {
        private final String title;
        private final ReadOnlyStringWrapper phase;
        private final long started = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private Operation(String title, String phase) {
            this.title = title;
            this.phase = new ReadOnlyStringWrapper(phase);
        }
        public String title() { return title; }
        public ReadOnlyStringProperty phaseProperty() { return phase.getReadOnlyProperty(); }
        public long elapsedSeconds() { return (System.nanoTime() - started) / 1_000_000_000L; }
        public void phase(String value) {
            Objects.requireNonNull(value);
            onFx(() -> { if (!closed.get()) phase.set(value); });
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) onFx(() -> ACTIVE.remove(this));
        }
    }
}
