/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.history;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.gui.UndoRedoManager;
import qupath.lib.gui.background.BackgroundTasks;
import qupath.lib.gui.images.stores.ImageRegionStoreFactory;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.gui.viewer.*;
import qupath.lib.images.*;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundUndoHistory {
    @TempDir Path temp;

    @Test void pendingSnapshotsKeepFxResponsiveAndUndoRetainsEveryRecentEdit() throws Exception {
        try (var data = data(); var blocker = new Blocker()) {
            var history = new BackgroundUndoHistory(() -> {});
            FxTestTools.call(() -> {
                history.record(data, 10);
                add(data, 10); history.record(data, 10);
                add(data, 20); history.record(data, 10);
                assertTrue(history.canUndo()); return null;
            });
            assertEquals("annotating", FxTestTools.call(() -> "annotating"));
            var undo = submit(() -> history.move(false, () -> true));
            FxTestTools.call(() -> "undo waits without blocking input");
            blocker.release.countDown();
            assertEquals(2, undo.get(15, TimeUnit.SECONDS).getAnnotationObjects().size());
            assertEquals(1, submit(() -> history.move(false, () -> true)).get(10, TimeUnit.SECONDS).getAnnotationObjects().size());
            assertEquals(2, submit(() -> history.move(true, () -> true)).get(10, TimeUnit.SECONDS).getAnnotationObjects().size());
            FxTestTools.call(() -> { history.dispose(); return null; });
        }
    }

    @Test void editingDuringPendingUndoCancelsThatUndoWithoutLosingHistory() throws Exception {
        try (var data = data(); var blocker = new Blocker()) {
            var history = new BackgroundUndoHistory(() -> {});
            FxTestTools.call(() -> { history.record(data, 10); add(data, 10); history.record(data, 10); return null; });
            var undo = submit(() -> {
                var unchanged = ImageDataSnapshot.watch(data);
                return history.move(false, unchanged);
            });
            FxTestTools.call(() -> { add(data, 20); history.record(data, 10); return null; });
            blocker.release.countDown();
            assertNull(undo.get(15, TimeUnit.SECONDS));
            assertEquals(3, data.getHierarchy().getAnnotationObjects().size());
            assertEquals(2, submit(() -> history.move(false, () -> true)).get(10, TimeUnit.SECONDS).getAnnotationObjects().size());
            FxTestTools.call(() -> { history.dispose(); return null; });
        }
    }

    @Test void clearingOrDisposingHistoryInvalidatesPendingSnapshots() throws Exception {
        try (var data = data(); var blocker = new Blocker()) {
            var history = new BackgroundUndoHistory(() -> {});
            FxTestTools.call(() -> {
                history.record(data, 10); add(data, 10); history.record(data, 10);
                history.clear(); history.dispose(); return null;
            });
            blocker.release.countDown();
            submit(() -> BackgroundTasks.io(() -> null)).get(10, TimeUnit.SECONDS);
            assertFalse(FxTestTools.call(history::canUndo));
            assertEquals(0, history.totalBytes());
            assertEquals(2, data.getHierarchy().getAnnotationObjects().size());
        }
    }

    @Test void nativeViewerUndoAndRedoApplyTheCorrectHierarchy() throws Exception {
        try (var data = data()) {
            var store = ImageRegionStoreFactory.createImageRegionStore(1024*1024);
            var viewer = FxTestTools.call(() -> {
                var v = new QuPathViewer(store, new OverlayOptions()); v.getView(); v.setImageData(data); return v;
            });
            try {
                var manager = FxTestTools.call(() -> UndoRedoManager.createForObservableViewer(new SimpleObjectProperty<>(viewer)));
                FxTestTools.call(() -> { add(data, 10); return null; });
                assertTrue(submit(manager::undoOnce).get(10, TimeUnit.SECONDS));
                assertEquals(1, data.getHierarchy().getAnnotationObjects().size());
                assertTrue(submit(manager::redoOnce).get(10, TimeUnit.SECONDS));
                assertEquals(2, data.getHierarchy().getAnnotationObjects().size());
            } finally { FxTestTools.call(() -> { viewer.closeViewer(); return null; }); store.close(); }
        }
    }

    @Test void pendingHistoryHonorsConfiguredDepthWithoutDroppingRecentEdits() throws Exception {
        try (var data = data(); var blocker = new Blocker()) {
            var history = new BackgroundUndoHistory(() -> {});
            FxTestTools.call(() -> {
                history.record(data, 2);
                for (int i=1; i<=5; i++) { add(data, i*10); history.record(data, 2); }
                return null;
            });
            blocker.release.countDown();
            assertEquals(5, submit(() -> history.move(false, () -> true)).get(10, TimeUnit.SECONDS).getAnnotationObjects().size());
            assertEquals(4, submit(() -> history.move(false, () -> true)).get(10, TimeUnit.SECONDS).getAnnotationObjects().size());
            assertNull(submit(() -> history.move(false, () -> true)).get(10, TimeUnit.SECONDS));
            FxTestTools.call(() -> { history.dispose(); return null; });
        }
    }

    @Test void beginningAStrokeWhileUndoWaitsPreservesTheStrokeAndHistory() throws Exception {
        try (var data = data(); var blocker = new Blocker()) {
            var store = ImageRegionStoreFactory.createImageRegionStore(1024*1024);
            var viewer = FxTestTools.call(() -> {
                var v = new QuPathViewer(store, new OverlayOptions()); v.getView(); v.setImageData(data); return v;
            });
            try {
                var manager = FxTestTools.call(() -> UndoRedoManager.createForObservableViewer(new SimpleObjectProperty<>(viewer)));
                FxTestTools.call(() -> { add(data, 10); return null; });
                var undo = submit(manager::undoOnce);
                var outline = PathObjects.createAnnotationObject(ROIs.createRectangleROI(30,1,5,5));
                FxTestTools.call(() -> { viewer.setSelectedObject(outline); return null; });
                blocker.release.countDown();
                assertFalse(undo.get(10, TimeUnit.SECONDS));
                assertSame(outline, data.getHierarchy().getSelectionModel().getSelectedObject());
                assertEquals(2, data.getHierarchy().getAnnotationObjects().size());
                FxTestTools.call(() -> { viewer.setSelectedObject(null); return null; });
                assertTrue(submit(manager::undoOnce).get(10, TimeUnit.SECONDS));
                assertEquals(1, data.getHierarchy().getAnnotationObjects().size());
            } finally { FxTestTools.call(() -> { viewer.closeViewer(); return null; }); store.close(); }
        }
    }

    @Test void closingOneViewerKeepsHistoryListeningForAnotherViewOfTheSameImage() throws Exception {
        try (var data = data()) {
            var store = ImageRegionStoreFactory.createImageRegionStore(1024*1024);
            var viewers = FxTestTools.call(() -> {
                var one = new QuPathViewer(store, new OverlayOptions()); one.getView(); one.setImageData(data);
                var two = new QuPathViewer(store, new OverlayOptions()); two.getView(); two.setImageData(data);
                return java.util.List.of(one, two);
            });
            try {
                var manager = FxTestTools.call(() -> {
                    var active = new SimpleObjectProperty<>(viewers.getFirst());
                    var result = UndoRedoManager.createForObservableViewer(active);
                    active.set(viewers.getLast()); add(data, 10); viewers.getFirst().closeViewer(); add(data, 20);
                    return result;
                });
                assertTrue(submit(manager::undoOnce).get(10, TimeUnit.SECONDS));
                assertEquals(2, data.getHierarchy().getAnnotationObjects().size());
            } finally { FxTestTools.call(() -> { viewers.getLast().closeViewer(); return null; }); store.close(); }
        }
    }

    private ImageData<BufferedImage> data() throws Exception {
        Class.forName("qupath.lib.scripting.QP");
        var file = temp.resolve("slide.png"); ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        var data = new ImageData<>(ImageServers.buildServer(file.toUri()), ImageData.ImageType.BRIGHTFIELD_H_DAB);
        add(data, 1); return data;
    }
    private static void add(ImageData<BufferedImage> data, int x) {
        data.getHierarchy().addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(x,1,5,5)));
    }
    private static <T> FutureTask<T> submit(Callable<T> work) throws Exception {
        FxTestTools.start(); var task = new FutureTask<>(work); Platform.runLater(task); return task;
    }
    private static final class Blocker implements AutoCloseable {
        final CountDownLatch release = new CountDownLatch(1);
        Blocker() throws Exception {
            var entered = new CountDownLatch(1);
            BackgroundTasks.submitIO(() -> {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }
        public void close() { release.countDown(); }
    }
}
