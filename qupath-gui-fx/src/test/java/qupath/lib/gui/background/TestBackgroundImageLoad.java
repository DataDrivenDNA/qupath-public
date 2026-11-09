/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.images.stores.DefaultImageRegionStore;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.gui.viewer.*;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundImageLoad {
    @TempDir Path temp;

    @Test void startingAnOutlineDuringLoadPreservesTheUncommittedStroke() throws Exception {
        try (var fixture = new ViewerFixture()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var closed = new AtomicBoolean();
            var outline = PathObjects.createAnnotationObject(ROIs.createRectangleROI(2,2,8,8));
            var task = TestBackgroundCommands.submit(() -> fixture.loads.begin(fixture.viewer).load(() -> {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
                return data("abandoned", closed);
            }, () -> true, null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxTestTools.call(() -> { fixture.viewer.setSelectedObject(outline); return null; });
            } finally { release.countDown(); }
            assertFalse(task.get(10, TimeUnit.SECONDS));
            assertSame(fixture.source, fixture.viewer.getImageData());
            assertSame(outline, fixture.source.getHierarchy().getSelectionModel().getSelectedObject());
            assertNull(outline.getParent());
            assertTrue(closed.get());
        }
    }

    @Test void editingDuringLoadKeepsCurrentImageAndClosesAbandonedServer() throws Exception {
        try (var fixture = new ViewerFixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var closed = new AtomicBoolean();
            var result = data("abandoned", closed);
            var task = TestBackgroundCommands.submit(() -> fixture.loads.begin(fixture.viewer).load(() -> {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return result;
            }, () -> true, null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxTestTools.call(() -> { fixture.source.getHierarchy().addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(1,1,5,5))); return null; });
            } finally { release.countDown(); }
            assertFalse(task.get(10, TimeUnit.SECONDS));
            assertSame(fixture.source, fixture.viewer.getImageData());
            assertEquals(1, fixture.source.getHierarchy().getAnnotationObjects().size());
            assertTrue(closed.get());
        }
    }

    @Test void laterImageChoiceWinsEvenWhenEarlierLoadCompletesFirst() throws Exception {
        try (var fixture = new ViewerFixture()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var closed = new AtomicBoolean();
            var older = data("older", closed);
            var newer = data("newer", new AtomicBoolean());
            var first = TestBackgroundCommands.submit(() -> fixture.loads.begin(fixture.viewer).load(() -> {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return older;
            }, () -> true, null));
            FutureTask<Boolean> second;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                second = TestBackgroundCommands.submit(() -> fixture.loads.begin(fixture.viewer).load(() -> newer, () -> true, null));
                FxTestTools.call(() -> "later request captured");
            } finally { release.countDown(); }
            assertTrue(second.get(10, TimeUnit.SECONDS));
            assertFalse(first.get(10, TimeUnit.SECONDS));
            assertSame(newer, fixture.viewer.getImageData());
            assertTrue(closed.get());
        }
    }

    @Test void fileReplacementDuringLoadRejectsStaleData() throws Exception {
        try (var fixture = new ViewerFixture()) {
            var file = temp.resolve("data.qpdata"); Files.writeString(file, "before");
            var closed = new AtomicBoolean();
            var task = TestBackgroundCommands.submit(() -> fixture.loads.begin(fixture.viewer).load(() -> {
                Files.writeString(file, "new saved analysis");
                return data("stale", closed);
            }, () -> true, file));
            assertFalse(task.get(10, TimeUnit.SECONDS));
            assertSame(fixture.source, fixture.viewer.getImageData());
            assertTrue(closed.get());
        }
    }

    private static ImageData<BufferedImage> data(String name, AtomicBoolean closed) {
        return new ImageData<>(new WrappedBufferedImageServer(name, new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB)) {
            @Override public void close() { closed.set(true); }
        }, ImageData.ImageType.BRIGHTFIELD_H_DAB);
    }
    private static final class ViewerFixture implements AutoCloseable {
        final DefaultImageRegionStore store = DefaultImageRegionStore.getSharedInstance();
        final BackgroundImageLoad loads = new BackgroundImageLoad(message -> {});
        final ImageData<BufferedImage> source = data("current", new AtomicBoolean());
        final QuPathViewer viewer;
        ViewerFixture() throws Exception {
            viewer = FxTestTools.call(() -> { var v = new QuPathViewer(store, new OverlayOptions()); v.getView(); v.setImageData(source); return v; });
        }
        @Override public void close() throws Exception {
            var current = viewer.getImageData();
            FxTestTools.call(() -> { viewer.closeViewer(); return null; });
            if (current != null && current != source) current.close();
            source.close();
        }
    }
}
