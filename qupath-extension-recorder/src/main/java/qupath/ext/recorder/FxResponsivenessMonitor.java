/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import javafx.application.Platform;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import java.util.stream.Collectors;

/** At most one pending FX pulse; captures the blocked stack from a background thread. */
final class FxResponsivenessMonitor implements AutoCloseable {
    private final Consumer<Runnable> dispatch;
    private final LongSupplier clock;
    private final Supplier<String> stack;
    private final BiConsumer<String, Map<String, Object>> events;
    private ScheduledExecutorService timer;
    private boolean pending;
    private boolean closed;
    private long started;
    private int samples;

    FxResponsivenessMonitor(Consumer<Runnable> dispatch, LongSupplier clock, Supplier<String> stack,
                            BiConsumer<String, Map<String, Object>> events) {
        this.dispatch = dispatch; this.clock = clock; this.stack = stack; this.events = events;
    }
    static FxResponsivenessMonitor start(RecordWriter writer) {
        var fxThread = Thread.currentThread();
        var monitor = new FxResponsivenessMonitor(Platform::runLater, System::nanoTime,
                () -> Arrays.stream(fxThread.getStackTrace()).map(Object::toString).collect(Collectors.joining("\n")), writer::event);
        monitor.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "qupath-ui-responsiveness"); thread.setDaemon(true); return thread;
        });
        monitor.timer.scheduleWithFixedDelay(monitor::tick, 250, 250, TimeUnit.MILLISECONDS);
        return monitor;
    }
    synchronized void tick() {
        if (closed) return;
        if (!pending) {
            pending = true; started = clock.getAsLong(); samples = 0;
            dispatch.accept(this::resumed);
        } else {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started);
            // Three bounded samples distinguish sustained work from a brief spike.
            if (samples < 3 && elapsed >= 1000 + samples * 2000L) {
                samples++;
                events.accept("ui.stall", Map.of("durationMs", elapsed, "sample", samples, "fxStack", stack.get()));
            }
        }
    }
    private synchronized void resumed() {
        if (closed) return;
        if (samples > 0) events.accept("ui.resumed", Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started)));
        pending = false;
    }
    @Override public synchronized void close() {
        closed = true;
        if (timer != null) timer.shutdownNow();
    }
}
