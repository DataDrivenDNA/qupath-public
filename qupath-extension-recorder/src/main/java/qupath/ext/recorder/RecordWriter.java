/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns disk writes and ordering. Producers never wait for storage on the UI thread. */
final class RecordWriter implements AutoCloseable {
    interface Asset { void write(Path destination) throws Exception; }
    private record Pending(String json, Asset asset, Path destination) {}
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ArrayBlockingQueue<Pending> queue = new ArrayBlockingQueue<>(8192);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong assetErrors = new AtomicLong();
    private final AtomicLong observerErrors = new AtomicLong();
    private final Path directory;
    private final BufferedWriter output;
    private final Consumer<Throwable> failed;
    private final Thread thread;
    private long sequence;
    private volatile boolean closing;
    private volatile Throwable failure;

    RecordWriter(Path directory, Consumer<Throwable> failed) throws IOException {
        this(directory, open(directory), failed);
    }

    RecordWriter(Path directory, BufferedWriter output, Consumer<Throwable> failed) {
        this.directory = directory;
        this.failed = failed;
        this.output = output;
        thread = new Thread(this::drain, "qupath-recorder-writer");
        thread.setDaemon(true);
        thread.start();
    }

    private static BufferedWriter open(Path directory) throws IOException {
        Files.createDirectories(directory);
        return Files.newBufferedWriter(directory.resolve("events.jsonl"));
    }

    Path directory() { return directory; }
    boolean isHealthy() { return !closing && failure == null; }
    int backlog() { return queue.size(); }
    long droppedEvents() { return dropped.get(); }
    long assetErrors() { return assetErrors.get(); }
    long observerErrors() { return observerErrors.get(); }

    /** Recorder defects must not interrupt the application operation being observed. */
    void observe(String source, Runnable operation) {
        if (!isHealthy()) return;
        try { operation.run(); }
        catch (RuntimeException e) {
            observerErrors.incrementAndGet();
            event("recorder.observer_error", Map.of("source", source, "error", e.toString()));
        }
    }

    synchronized void event(String type, Map<String, ?> payload) {
        enqueue(type, payload, null, null);
    }

    synchronized void asset(String type, String relativePath, Map<String, ?> payload, Asset asset) {
        var destination = directory.resolve(relativePath).normalize();
        if (!destination.startsWith(directory.normalize())) throw new IllegalArgumentException("Asset escapes session");
        var fields = new LinkedHashMap<String, Object>(payload);
        fields.put("file", relativePath);
        enqueue(type, fields, asset, destination);
    }

    private void enqueue(String type, Map<String, ?> payload, Asset asset, Path path) {
        if (closing || failure != null) return;
        var record = new LinkedHashMap<String, Object>();
        record.put("schema", 1);
        record.put("sequence", ++sequence);
        record.put("time", Instant.now().toString());
        record.put("thread", Thread.currentThread().getName());
        record.put("type", type);
        record.put("data", safe(payload));
        if (!queue.offer(new Pending(gson.toJson(record), asset, path))) dropped.incrementAndGet();
    }

    private void line(String text) throws IOException { output.write(text); output.newLine(); }

    private static Object safe(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) return Double.isFinite(n.doubleValue()) ? n : null;
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key), safe(item)));
            return result;
        }
        if (value instanceof Iterable<?> items) {
            var result = new java.util.ArrayList<>();
            items.forEach(item -> result.add(safe(item)));
            return result;
        }
        return value.toString();
    }

    private void drain() {
        long reportedDrops = 0;
        try {
            while (!closing || !queue.isEmpty()) {
                var item = queue.poll(500, TimeUnit.MILLISECONDS);
                long lost = dropped.get();
                if (lost != reportedDrops) {
                    line(gson.toJson(Map.of("schema", 1, "type", "recorder.gap", "time", Instant.now().toString(),
                            "data", Map.of("droppedTotal", lost, "reason", "storage queue full"))));
                    reportedDrops = lost;
                }
                if (item != null) {
                    if (item.asset != null) {
                        try {
                            Files.createDirectories(item.destination.getParent());
                            item.asset.write(item.destination);
                        } catch (Exception e) {
                            assetErrors.incrementAndGet();
                            line(gson.toJson(Map.of("schema", 1, "type", "recorder.asset_error",
                                    "time", Instant.now().toString(), "data", Map.of("file", item.destination.toString(), "error", e.toString()))));
                            // The intended asset event remains, paired with an explicit failure record.
                        }
                    }
                    line(item.json);
                }
                if (item == null || queue.isEmpty()) output.flush();
            }
            line(gson.toJson(Map.of("schema", 1, "type", "recorder.closed", "time", Instant.now().toString(),
                    "data", Map.of("droppedTotal", dropped.get(), "assetErrors", assetErrors.get(), "observerErrors", observerErrors.get()))));
        } catch (Throwable e) {
            failure = e;
            failed.accept(e);
        } finally {
            try { output.close(); } catch (IOException e) { if (failure == null) { failure = e; failed.accept(e); } }
        }
    }

    @Override public synchronized void close() { closing = true; }

    boolean awaitClosed(long milliseconds) throws InterruptedException {
        thread.join(milliseconds);
        return !thread.isAlive();
    }
}
