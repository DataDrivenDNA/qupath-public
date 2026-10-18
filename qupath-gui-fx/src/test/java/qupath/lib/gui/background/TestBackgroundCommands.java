/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServers;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.projects.*;
import qupath.lib.roi.ROIs;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundCommands {
    @TempDir Path temp;

    @Test void saveDoesNotMarkAnUnfinishedOutlineAsSaved() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var outline = PathObjects.createAnnotationObject(ROIs.createRectangleROI(20,20,5,5));
            FxTestTools.call(() -> { fixture.data.getHierarchy().getSelectionModel().setSelectedObject(outline); return null; });
            assertFalse(submit(() -> BackgroundImageSave.save(fixture.data, fixture.entry, null)).get(10, TimeUnit.SECONDS));
            assertTrue(fixture.data.isChanged());
            assertSame(outline, fixture.data.getHierarchy().getSelectionModel().getSelectedObject());
            assertNull(outline.getParent());
            try (var saved = fixture.entry.readImageData()) { assertEquals(1, saved.getHierarchy().getAnnotationObjects().size()); }
        }
    }

    @Test void analysisAndIoKeepDispatchingFxEventsAndPropagateFailure() throws Exception {
        FxTestTools.start();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var task = submit(() -> BackgroundTasks.analysis(() -> {
            assertFalse(Platform.isFxApplicationThread());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            throw new IOException("native failure");
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(42, FxTestTools.call(() -> BackgroundTasks.io(() -> 42)));
            assertEquals("still annotating", FxTestTools.call(() -> "still annotating"));
        } finally { release.countDown(); }
        assertEquals("native failure", assertThrows(ExecutionException.class,
                () -> task.get(10, TimeUnit.SECONDS)).getCause().getMessage());
        assertFalse(FxTestTools.call(Platform::isNestedLoopRunning));
    }

    @Test void saveKeepsEditsMadeAfterSnapshotDirtyAndNativeFileHasOriginalValues() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var entry = delayed(fixture.entry, entered, release, false);
            FxTestTools.call(() -> { fixture.data.getHierarchy().getAnnotationObjects().iterator().next().setName("at save"); fixture.data.setChanged(true); return null; });
            var task = submit(() -> BackgroundImageSave.save(fixture.data, entry, null));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxTestTools.call(() -> {
                    fixture.data.getHierarchy().getAnnotationObjects().iterator().next().setName("newer edit");
                    fixture.data.getHierarchy().addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(20,20,5,5)));
                    return null;
                });
            } finally { release.countDown(); }
            assertFalse(task.get(15, TimeUnit.SECONDS));
            assertTrue(fixture.data.isChanged());
            assertEquals(2, fixture.data.getHierarchy().getAnnotationObjects().size());
            try (var read = fixture.entry.readImageData()) {
                assertEquals(1, read.getHierarchy().getAnnotationObjects().size());
                assertEquals("at save", read.getHierarchy().getAnnotationObjects().iterator().next().getName());
            }
        }
    }

    @Test void overlappingSavesAreOrderedAndNewestSnapshotWins() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var entry = delayed(fixture.entry, entered, release, false);
            var first = submit(() -> BackgroundImageSave.save(fixture.data, entry, null));
            FutureTask<Boolean> second;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                FxTestTools.call(() -> { fixture.data.getHierarchy().getAnnotationObjects().iterator().next().setName("latest"); fixture.data.setChanged(true); return null; });
                second = submit(() -> BackgroundImageSave.save(fixture.data, fixture.entry, null));
                FxTestTools.call(() -> "second save started");
            } finally { release.countDown(); }
            assertTrue(second.get(15, TimeUnit.SECONDS));
            assertFalse(first.get(15, TimeUnit.SECONDS));
            assertFalse(fixture.data.isChanged());
            try (var read = fixture.entry.readImageData()) {
                assertEquals("latest", read.getHierarchy().getAnnotationObjects().iterator().next().getName());
            }
        }
    }

    @Test void failedSavePreservesAuthoritativeFileAndDirtyFlag() throws Exception {
        try (var fixture = new Fixture(temp)) {
            byte[] before = Files.readAllBytes(fixture.entry.getImageDataPath());
            fixture.data.setChanged(true);
            var task = submit(() -> BackgroundImageSave.save(fixture.data,
                    delayed(fixture.entry, new CountDownLatch(0), new CountDownLatch(0), true), null));
            assertThrows(ExecutionException.class, () -> task.get(10, TimeUnit.SECONDS));
            assertArrayEquals(before, Files.readAllBytes(fixture.entry.getImageDataPath()));
            assertTrue(fixture.data.isChanged());
        }
    }

    @Test void standaloneSaveRoundTripsNativeGeometryAndMeasurements() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var region = fixture.data.getHierarchy().getAnnotationObjects().iterator().next();
            region.getMeasurementList().put("ROI: 0.50 µm per pixel: DAB: Mean", 0.42330889381351156);
            var file = temp.resolve("standalone.qpdata");
            assertTrue(submit(() -> BackgroundImageSave.save(fixture.data, null, file)).get(15, TimeUnit.SECONDS));
            try (var read = PathIO.<BufferedImage>readImageData(file)) {
                var saved = read.getHierarchy().getAnnotationObjects().iterator().next();
                assertTrue(region.getROI().getGeometry().equalsExact(saved.getROI().getGeometry()));
                assertEquals(region.getMeasurementList().asMap(), saved.getMeasurementList().asMap());
            }
            try (var files = Files.list(temp)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".prepared-save-"))); }
        }
    }

    @Test void externalSaveBeforeWorkerStartsCannotBeOverwrittenByAnOlderSnapshot() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocker = submit(() -> BackgroundTasks.io(() -> {
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return null;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var save = submit(() -> BackgroundImageSave.save(fixture.data, fixture.entry, null));
            byte[] newer;
            try {
                FxTestTools.call(() -> "snapshot captured");
                try (var external = fixture.entry.readImageData()) {
                    external.getHierarchy().getAnnotationObjects().iterator().next().setName("new queue result");
                    fixture.entry.saveImageData(external);
                }
                newer = Files.readAllBytes(fixture.entry.getImageDataPath());
            } finally { release.countDown(); }
            assertThrows(ExecutionException.class, () -> save.get(10, TimeUnit.SECONDS));
            blocker.get(10, TimeUnit.SECONDS);
            assertArrayEquals(newer, Files.readAllBytes(fixture.entry.getImageDataPath()));
        }
    }

    @Test void otherProjectBackendsRetainNativeSavingOnWorker() throws Exception {
        try (var fixture = new Fixture(temp)) {
            @SuppressWarnings("unchecked")
            var backend = (ProjectImageEntry<BufferedImage>)Proxy.newProxyInstance(ProjectImageEntry.class.getClassLoader(),
                    new Class<?>[]{ProjectImageEntry.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareImageDataSave")) throw new UnsupportedOperationException();
                        if (method.getName().equals("saveImageData")) assertFalse(Platform.isFxApplicationThread());
                        try { return method.invoke(fixture.entry, args); }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            assertTrue(submit(() -> BackgroundImageSave.save(fixture.data, backend, null)).get(10, TimeUnit.SECONDS));
            try (var read = fixture.entry.readImageData()) { assertEquals(1, read.getHierarchy().getAnnotationObjects().size()); }
        }
    }

    static <T> FutureTask<T> submit(Callable<T> work) throws Exception {
        FxTestTools.start();
        var task = new FutureTask<>(work); Platform.runLater(task); return task;
    }
    @SuppressWarnings("unchecked")
    static ProjectImageEntry<BufferedImage> delayed(ProjectImageEntry<BufferedImage> delegate, CountDownLatch entered,
            CountDownLatch release, boolean fail) {
        return (ProjectImageEntry<BufferedImage>)Proxy.newProxyInstance(ProjectImageEntry.class.getClassLoader(),
                new Class<?>[]{ProjectImageEntry.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareImageDataSave")) {
                        assertFalse(Platform.isFxApplicationThread()); entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        if (fail) throw new IOException("simulated disk failure");
                    }
                    try { return method.invoke(delegate, args); }
                    catch (InvocationTargetException error) { throw error.getCause(); }
                });
    }

    static final class Fixture implements AutoCloseable {
        final ProjectImageEntry<BufferedImage> entry;
        final ImageData<BufferedImage> data;
        Fixture(Path path) throws Exception {
            Class.forName("qupath.lib.scripting.QP");
            var file = path.resolve("image.png");
            ImageIO.write(new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB), "png", file.toFile());
            var project = Projects.createProject(path.toFile(), BufferedImage.class);
            try (var server = ImageServers.buildServer(file.toUri())) { entry = project.addImage(server.getBuilder()); }
            data = entry.readImageData();
            data.getHierarchy().addObject(PathObjects.createAnnotationObject(ROIs.createRectangleROI(1.125,2.25,10.5,11.75)));
            entry.saveImageData(data);
        }
        @Override public void close() throws Exception { data.close(); }
    }
}
