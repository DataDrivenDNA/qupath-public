package qupath.ext.analysisqueue.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.analysisqueue.persistence.DiskJobStore;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisQueueTest {
    @TempDir Path root;
    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for queue state");
    }
    @Test void serialFailureContinuationPauseAndDuplicateProtection() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var order = new CopyOnWriteArrayList<String>();
        try (var queue = new AnalysisQueue(new DiskJobStore(root), (job, directory, phase) -> {
            order.add(job.imageKey());
            if (job.imageKey().equals("first")) {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
                throw new IOException("Missing slide");
            }
            return new JobProcessor.Outcome(Job.State.COMPLETED, "saved", directory.toString());
        })) {
            queue.enqueue(Job.waiting("first", "First")); assertTrue(entered.await(10, TimeUnit.SECONDS));
            queue.enqueue(Job.waiting("second", "Second"));
            assertThrows(IllegalArgumentException.class, () -> queue.enqueue(Job.waiting("second", "Duplicate")));
            assertEquals(List.of("first"), order);
            queue.setPaused(true); release.countDown();
            await(() -> queue.snapshot().getFirst().state() == Job.State.FAILED);
            assertEquals(Job.State.WAITING, queue.snapshot().get(1).state());
            queue.setPaused(false);
            await(() -> queue.snapshot().get(1).state() == Job.State.COMPLETED);
            assertEquals(List.of("first", "second"), order);
        } finally { release.countDown(); }
    }
    @Test void durableRecoveryCancellationAndExclusiveOwnership() throws Exception {
        var processing = Job.waiting("interrupted", "Interrupted").processing("Measuring");
        var waiting = Job.waiting("waiting", "Waiting");
        try (var store = new DiskJobStore(root)) {
            store.save(processing); store.save(waiting);
            assertThrows(IOException.class, () -> new DiskJobStore(root));
        }
        try (var queue = new AnalysisQueue(new DiskJobStore(root), (j, p, c) -> new JobProcessor.Outcome(Job.State.COMPLETED, "saved", p.toString()))) {
            assertEquals(Job.State.INTERRUPTED, queue.snapshot().stream().filter(j -> j.id().equals(processing.id())).findFirst().orElseThrow().state());
            queue.setPaused(true); queue.cancelWaiting(waiting.id()); queue.retry(processing.id());
            assertTrue(queue.containsActive("interrupted"));
            queue.setPaused(false);
            await(() -> queue.snapshot().stream().anyMatch(j -> j.state() == Job.State.COMPLETED));
        }
        // Closing while the worker is returning releases ownership once it exits.
        await(() -> { try (var store = new DiskJobStore(root)) { return store.load().stream().anyMatch(j -> j.state() == Job.State.CANCELLED); } catch (IOException e) { return false; } });
    }
    @Test void cleanupRunsAfterDurableCompletionAndCannotEraseSuccess() throws Exception {
        var store = new DiskJobStore(root); var cleaned = new CountDownLatch(1);
        var processor = new JobProcessor() {
            public Outcome process(Job j, Path p, java.util.function.Consumer<String> phase) { return new Outcome(Job.State.COMPLETED, "saved", p.toString()); }
            public void releaseCompletedInput(Job j, Path p) throws Exception {
                assertEquals(Job.State.COMPLETED, store.load().getFirst().state());
                cleaned.countDown(); throw new IOException("Locked temporary file");
            }
        };
        try (var queue = new AnalysisQueue(store, processor)) {
            queue.enqueue(Job.waiting("one", "One")); assertTrue(cleaned.await(10, TimeUnit.SECONDS));
            assertEquals(Job.State.COMPLETED, queue.snapshot().getFirst().state());
        }
    }
    @Test void preparingJobsReserveTheirPositionAndFailedPreparationReleasesIt() throws Exception {
        var order = new CopyOnWriteArrayList<String>();
        try (var queue = new AnalysisQueue(new DiskJobStore(root), (j, p, c) -> {
            order.add(j.imageKey()); return new JobProcessor.Outcome(Job.State.COMPLETED, "saved", p.toString());
        })) {
            var first = Job.preparing("first", "First"); var second = Job.preparing("second", "Second");
            queue.enqueue(first); queue.enqueue(second); queue.inputReady(second.id());
            assertTrue(queue.containsActive("first")); assertTrue(order.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> queue.enqueue(Job.preparing("first", "Duplicate")));
            queue.preparationFailed(first.id(), "Input changed");
            await(() -> queue.snapshot().get(1).state() == Job.State.COMPLETED);
            assertEquals(List.of("second"), order);
            assertEquals(Job.State.PREPARATION_FAILED, queue.snapshot().getFirst().state());
            assertThrows(IllegalStateException.class, () -> queue.retry(first.id()));
        }
    }
    @Test void restartNeverDispatchesAnIncompleteSnapshot() throws Exception {
        try (var store = new DiskJobStore(root)) { store.save(Job.preparing("partial", "Partial")); }
        try (var queue = new AnalysisQueue(new DiskJobStore(root), (j, p, c) -> { throw new AssertionError("Incomplete input dispatched"); })) {
            queue.start();
            assertEquals(Job.State.PREPARATION_FAILED, queue.snapshot().getFirst().state());
            assertFalse(queue.containsActive("partial"));
        }
    }
}
