/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TestRecordWriter {
    @TempDir Path temporary;

    @Test void concurrentEventsAreOrderedAndFlushedWithStrictJson() throws Exception {
        var writer = new RecordWriter(temporary, error -> fail(error));
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (int i=0; i<4; i++) workers.submit(() -> {
                for (int j=0; j<100; j++) writer.event("measurement", Map.of("value", Double.NaN, "index", j));
            });
        }
        writer.close(); assertTrue(writer.awaitClosed(5000));
        var lines = Files.readAllLines(temporary.resolve("events.jsonl"));
        assertEquals(401, lines.size());
        for (int i=0; i<400; i++) {
            var record = JsonParser.parseString(lines.get(i)).getAsJsonObject();
            assertEquals(i+1, record.get("sequence").getAsLong());
            assertTrue(record.getAsJsonObject("data").get("value").isJsonNull());
        }
        assertEquals("recorder.closed", JsonParser.parseString(lines.getLast()).getAsJsonObject().get("type").getAsString());
    }

    @Test void slowStorageNeverBlocksProducersAndLossIsExplicit() throws Exception {
        var writer = new RecordWriter(temporary, error -> fail(error));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        writer.asset("slow", "asset.txt", Map.of(), path -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            for (int i=0; i<9000; i++) writer.event("action", Map.of("index", i));
            assertTrue((System.nanoTime()-start)/1e9 < 3);
            assertTrue(writer.droppedEvents() > 0);
        } finally { release.countDown(); writer.close(); }
        assertTrue(writer.awaitClosed(5000));
        assertTrue(Files.readString(temporary.resolve("events.jsonl")).contains("recorder.gap"));
    }

    @Test void failedAssetsAreReportedWithoutLosingLaterActions() throws Exception {
        var writer = new RecordWriter(temporary, error -> fail(error));
        writer.asset("geometry", "geometry/test.wkb", Map.of(), path -> { throw new java.io.IOException("simulated storage failure"); });
        writer.event("later.action", Map.of("completed", true));
        assertThrows(IllegalArgumentException.class, () -> writer.asset("escape", "../escape", Map.of(), path -> {}));
        writer.close(); assertTrue(writer.awaitClosed(5000));
        var text = Files.readString(temporary.resolve("events.jsonl"));
        assertTrue(text.contains("recorder.asset_error")); assertTrue(text.contains("later.action"));
        assertFalse(Files.exists(temporary.getParent().resolve("escape")));
        assertEquals(1, writer.assetErrors());
    }

    @Test void fatalDiskFailureStopsWriterAndCallsFailureHandler() throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var output = new java.io.BufferedWriter(new java.io.Writer() {
            @Override public void write(char[] chars, int offset, int length) throws java.io.IOException { throw new java.io.IOException("disk unavailable"); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        var writer = new RecordWriter(temporary, output, failure::set);
        writer.event("action", Map.of());
        assertTrue(writer.awaitClosed(5000));
        assertFalse(writer.isHealthy());
        assertNotNull(failure.get());
        assertEquals("disk unavailable", failure.get().getMessage());
    }

    @Test void observerFailureDoesNotEscapeIntoAnalysisAndLaterEventsContinue() throws Exception {
        var writer = new RecordWriter(temporary, error -> fail(error));
        assertDoesNotThrow(() -> writer.observe("test", () -> { throw new IllegalStateException("bad observer"); }));
        writer.observe("later", () -> writer.event("analysis.completed", Map.of()));
        writer.close(); assertTrue(writer.awaitClosed(5000));
        assertEquals(1, writer.observerErrors());
        var text = Files.readString(temporary.resolve("events.jsonl"));
        assertTrue(text.contains("recorder.observer_error")); assertTrue(text.contains("analysis.completed"));
    }
}
