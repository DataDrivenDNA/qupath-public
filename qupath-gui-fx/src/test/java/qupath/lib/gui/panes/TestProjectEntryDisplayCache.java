/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.panes;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.projects.ProjectImageEntry;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TestProjectEntryDisplayCache {
    @Test void slowDiskReadNeverBlocksFxOrAssignsOneImagesThumbnailToAnother() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var cache = new ProjectEntryDisplayCache(worker);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var ready = new CountDownLatch(1);
            var slow = entry("slow", () -> {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
                return image(0xff3300);
            });
            var next = entry("next", () -> image(0x00ff00));
            FxTestTools.call(() -> {
                cache.revisionProperty().addListener(obs -> ready.countDown());
                assertNull(cache.get(slow, true, true));
                return null;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("still annotating", FxTestTools.call(() -> {
                    assertNull(cache.get(next, true, true));
                    return "still annotating";
                }));
            } finally { release.countDown(); }
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            FxTestTools.call(() -> {
                var first = cache.get(slow, true, true);
                var second = cache.get(next, true, true);
                assertEquals("summary slow", first.summary());
                assertEquals("summary next", second.summary());
                assertEquals(0xffff3300, first.image().get().getPixelReader().getArgb(0, 0));
                assertEquals(0xff00ff00, second.image().get().getPixelReader().getArgb(0, 0));
                assertTrue(first.hasData()); assertTrue(first.missing());
                cache.clear(); return null;
            });
        }
    }

    @Test void failedThumbnailsAreNotRetriedOnEveryLayoutAndRefreshCanRecover() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var cache = new ProjectEntryDisplayCache(worker);
            var reads = new AtomicInteger();
            var entry = entry("retry", () -> {
                if (reads.incrementAndGet() == 1) throw new IOException("unavailable disk");
                return image(0x123456);
            });
            FxTestTools.call(() -> { cache.get(entry, true, false); return null; });
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            FxTestTools.call(() -> {
                for (int i = 0; i < 10; i++) assertTrue(cache.get(entry, true, false).thumbnailFailed());
                assertEquals(1, reads.get());
                cache.invalidate(entry);
                assertNull(cache.get(entry, true, false)); return null;
            });
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            FxTestTools.call(() -> {
                assertFalse(cache.get(entry, true, false).thumbnailFailed());
                cache.clear(); return null;
            });
            assertEquals(2, reads.get());
        }
    }

    @Test void hiddenThumbnailsNeverReadPixelsOrOpenAServer() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            var cache = new ProjectEntryDisplayCache(worker);
            var entry = entry("hidden", () -> { fail("Hidden thumbnail was read"); return null; });
            FxTestTools.call(() -> { cache.get(entry, false, false); return null; });
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            FxTestTools.call(() -> {
                assertNull(cache.get(entry, false, false).image());
                cache.clear(); return null;
            });
        }
    }

    private static BufferedImage image(int rgb) {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb); return image;
    }
    @SuppressWarnings("unchecked")
    private static ProjectImageEntry<BufferedImage> entry(String name, Callable<BufferedImage> thumbnail) {
        return (ProjectImageEntry<BufferedImage>)Proxy.newProxyInstance(ProjectImageEntry.class.getClassLoader(),
                new Class<?>[]{ProjectImageEntry.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getImageName" -> name;
                    case "getSummary" -> { assertFalse(Platform.isFxApplicationThread()); yield "summary " + name; }
                    case "hasImageData" -> { assertFalse(Platform.isFxApplicationThread()); yield true; }
                    case "getURIs" -> { assertFalse(Platform.isFxApplicationThread()); yield List.of(URI.create("file:///Z:/qupath-nonexistent-test-image")); }
                    case "getThumbnail" -> { assertFalse(Platform.isFxApplicationThread()); yield thumbnail.call(); }
                    default -> throw new AssertionError("Unexpected native call: " + method.getName());
                });
    }
}
