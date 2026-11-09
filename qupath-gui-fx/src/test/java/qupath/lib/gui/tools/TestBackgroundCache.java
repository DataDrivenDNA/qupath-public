/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundCache {

    @Test
    void queuedWorkIsBoundedAndDeferredRequestsCanResume() throws Exception {
        var tasks = new ArrayList<Runnable>();
        var cache = new BackgroundCache<Integer, Integer>(tasks::add);
        FxTestTools.call(() -> {
            for (int i = 0; i < 1000; i++) {
                int value = i;
                cache.get(i, () -> value);
            }
            assertEquals(128, tasks.size());
            return null;
        });
        tasks.getFirst().run();
        FxTestTools.call(() -> {
            assertEquals(0, cache.get(0, () -> -1));
            assertNull(cache.get(999, () -> 999));
            assertEquals(129, tasks.size());
            cache.clear();
            cache.get(1000, () -> 1000);
            assertEquals(130, tasks.size(), "Invalidation must release queue capacity");
            cache.clear();
            return null;
        });
    }

    @Test
    void repeatedRequestsAreDeduplicatedAndQueuedInvalidationDropsWork() throws Exception {
        var tasks = new ArrayList<Runnable>();
        var cache = new BackgroundCache<String, Integer>(tasks::add);
        var count = new AtomicInteger();
        FxTestTools.call(() -> {
            for (int i=0; i<20; i++) assertNull(cache.get("same", count::incrementAndGet));
            assertEquals(1, tasks.size());
            cache.clear();
            return null;
        });
        tasks.getFirst().run();
        assertEquals(0, count.get());
    }

    @Test
    void obsoleteRunningResultCannotReplaceNewResult() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var cache = new BackgroundCache<String, Integer>(workers);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var oldFinished = new CountDownLatch(1);
            var fresh = new CountDownLatch(1);
            try {
                FxTestTools.call(() -> {
                    cache.revisionProperty().addListener(obs -> fresh.countDown());
                    cache.get("roi", () -> {
                        entered.countDown();
                        boolean done = false;
                        while (!done) {
                            try { done = release.await(5, TimeUnit.SECONDS); }
                            catch (InterruptedException ignored) { /* emulate non-interruptible geometry work */ }
                        }
                        oldFinished.countDown();
                        return 1;
                    });
                    return null;
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                FxTestTools.call(() -> { cache.clear(); cache.get("roi", () -> 2); return null; });
                assertTrue(fresh.await(3, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(oldFinished.await(2, TimeUnit.SECONDS));
                assertEquals(2, FxTestTools.call(() -> cache.get("roi", () -> 3)));
            } finally { release.countDown(); cache.clear(); }
        }
    }

    @Test
    void aSaturatedExecutorNeverRunsWorkOnCaller() throws Exception {
        var cache = new BackgroundCache<String, Integer>(task -> { throw new RejectedExecutionException(); });
        var count = new AtomicInteger();
        FxTestTools.call(() -> { assertNull(cache.get("roi", count::incrementAndGet)); return null; });
        assertEquals(0, count.get());
    }
}
