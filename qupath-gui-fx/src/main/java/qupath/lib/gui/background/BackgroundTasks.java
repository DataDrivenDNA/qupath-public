/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import javafx.application.Platform;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Runs native synchronous commands while FX continues dispatching input and painting. */
public final class BackgroundTasks {
    private static final Executor IO = executor("qupath-image-io");
    private static final Executor ANALYSIS = executor("qupath-object-creation");
    private BackgroundTasks() {}

    private static Executor executor(String name) {
        // Idle workers expire. Separate lanes let saving/loading proceed during segmentation.
        return new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    var thread = new Thread(r, name);
                    thread.setDaemon(true);
                    return thread;
                });
    }
    public static <T> T io(Callable<T> work) throws IOException {
        return run(IO, work, result -> {});
    }
    public static <T> T io(Callable<T> work, Consumer<T> completed) throws IOException {
        return run(IO, work, completed);
    }
    public static <T> T analysis(Callable<T> work) throws IOException {
        return run(ANALYSIS, work, result -> {});
    }
    /** Enqueue independently owned I/O work; callers own completion and failure handling. */
    public static void submitIO(Runnable work) { IO.execute(work); }

    /** Completion runs on FX before a later queued command can update its UI state. */
    static <T> T run(Executor executor, Callable<T> work, Consumer<T> completed) throws IOException {
        if (!Platform.isFxApplicationThread())
            throw new IllegalStateException("Background GUI commands must be started on JavaFX");
        if (!Platform.canStartNestedEventLoop())
            throw new IOException("This command cannot run during layout or animation; retry from its menu");
        var key = new Object();
        executor.execute(() -> {
            T value = null;
            Throwable failure = null;
            try { value = work.call(); }
            catch (Throwable error) { failure = error; }
            var outcome = new Outcome<>(value, failure);
            Platform.runLater(() -> {
                var finished = outcome;
                try {
                    if (finished.failure == null) completed.accept(finished.value);
                } catch (Throwable error) { finished = new Outcome<>(null, error); }
                finally { Platform.exitNestedEventLoop(key, finished); }
            });
        });
        @SuppressWarnings("unchecked")
        var outcome = (Outcome<T>)Platform.enterNestedEventLoop(key);
        if (outcome.failure instanceof Error error) throw error;
        if (outcome.failure instanceof IOException error) throw error;
        if (outcome.failure != null) throw new IOException(outcome.failure.getMessage(), outcome.failure);
        return outcome.value;
    }
    private record Outcome<T>(T value, Throwable failure) {}
}
