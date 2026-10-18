/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.process.gui.commands.ml;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.classifiers.pixel.*;
import qupath.lib.images.*;
import qupath.lib.images.servers.*;
import qupath.lib.io.PathIO;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.*;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.opencv.ml.pixel.*;
import qupath.opencv.ml.pixel.PixelClassifierTools.CreateObjectOptions;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundObjectCreation {
    @TempDir Path temp;
    private static boolean fxStarted;

    @Test void nativeRegionsMatchWhileUnrelatedAnnotationEditsSurvive() throws Exception {
        try (var fixture = new Fixture(temp); var reference = ImageDataSnapshot.capture(fixture.data)) {
            var classifier = fixture.classifier();
            try (var server = PixelClassifierTools.createPixelClassificationServer(reference.data(), classifier)) {
                PixelClassifierTools.createAnnotationsFromPixelClassifier(reference.data().getHierarchy(), server, 0, 0);
            }
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var job = submit(() -> BackgroundObjectCreation.run(fixture.data, delayed(classifier, entered, release),
                    false, 0, 0, null, message -> {}, temp.resolve("recovery")));
            var other = PathObjects.createAnnotationObject(ROIs.createRectangleROI(201,1,10,10));
            var unfinished = PathObjects.createAnnotationObject(ROIs.createRectangleROI(215,1,10,10));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                submit(() -> {
                    fixture.data.getHierarchy().addObject(other); other.setName("manual work");
                    fixture.data.getHierarchy().getSelectionModel().setSelectedObject(unfinished);
                    return null;
                }).get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            assertTrue(job.get(20, TimeUnit.SECONDS));
            assertTrue(fixture.data.getHierarchy().getAnnotationObjects().contains(other));
            assertEquals("manual work", other.getName());
            assertSame(unfinished, fixture.data.getHierarchy().getSelectionModel().getSelectedObject());
            assertNull(unfinished.getParent());
            var expected = ImageDataFingerprint.roots(reference.data(), fixture.ids()).getFirst();
            assertEquals(2, fixture.region.nChildObjects());
            assertEquals(expected.nChildObjects(), fixture.region.nChildObjects());
            for (var child : expected.getChildObjects()) {
                var actual = fixture.region.getChildObjects().stream().filter(p -> p.getPathClass().equals(child.getPathClass())).findFirst().orElseThrow();
                assertTrue(child.getROI().getGeometry().equalsExact(actual.getROI().getGeometry()));
                assertTrue(actual.isAnnotation());
            }
            assertFalse(Files.exists(temp.resolve("recovery")));
        }
    }

    @Test void unfinishedSelectionCannotAccidentallyRunFullImageClassification() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var unfinished = PathObjects.createAnnotationObject(ROIs.createRectangleROI(210,1,10,10));
            var messages = new ArrayList<String>();
            assertFalse(submit(() -> {
                fixture.data.getHierarchy().getSelectionModel().setSelectedObject(unfinished);
                return BackgroundObjectCreation.run(fixture.data, fixture.classifier(), false, 0, 0, null,
                        messages::add, temp.resolve("recovery"));
            }).get(10, TimeUnit.SECONDS));
            assertSame(unfinished, fixture.data.getHierarchy().getSelectionModel().getSelectedObject());
            assertEquals(1, fixture.data.getHierarchy().getAnnotationObjects().size());
            assertFalse(messages.isEmpty());
        }
    }

    @Test void changedOutlineIsKeptAndCompletedObjectsAreRecoverable() throws Exception {
        try (var fixture = new Fixture(temp)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var job = submit(() -> BackgroundObjectCreation.run(fixture.data, delayed(fixture.classifier(), entered, release),
                    false, 0, 0, null, message -> {}, temp.resolve("recovery")));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                submit(() -> { fixture.region.setROI(ROIs.createRectangleROI(10,0,100,100)); return null; }).get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> job.get(20, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("saved separately"));
            assertEquals(10, fixture.region.getROI().getBoundsX());
            assertFalse(fixture.region.hasChildObjects());
            Path recovery;
            try (var files = Files.list(temp.resolve("recovery"))) { recovery = files.findFirst().orElseThrow(); }
            try (var read = PathIO.<BufferedImage>readImageData(recovery)) {
                var parent = ImageDataFingerprint.roots(read, fixture.ids()).getFirst();
                assertEquals(0, parent.getROI().getBoundsX()); assertEquals(2, parent.nChildObjects());
            }
        }
    }

    @Test void fullImageDetectionsAndSelectionUseNativeSemantics() throws Exception {
        try (var fixture = new Fixture(temp)) {
            fixture.data.getHierarchy().getSelectionModel().clearSelection();
            assertTrue(submit(() -> BackgroundObjectCreation.run(fixture.data, fixture.classifier(), true,
                    0, 0, null, message -> {}, temp.resolve("recovery"), CreateObjectOptions.SELECT_NEW)).get(20, TimeUnit.SECONDS));
            assertEquals(2, fixture.data.getHierarchy().getDetectionObjects().size());
            assertEquals(2, fixture.data.getHierarchy().getSelectionModel().getSelectedObjects().size());
            assertEquals(1, fixture.data.getHierarchy().getAnnotationObjects().size());
        }
    }

    @Test void closedImageResultsSaveNativelyAndReopenedImageIsProtected() throws Exception {
        try (var fixture = new Fixture(temp); var result = ImageDataSnapshot.capture(fixture.data)) {
            var baseline = ImageDataFingerprint.compute(result.data(), fixture.ids());
            try (var server = PixelClassifierTools.createPixelClassificationServer(result.data(), fixture.classifier())) {
                PixelClassifierTools.createAnnotationsFromPixelClassifier(result.data().getHierarchy(), server, 0, 0);
            }
            byte[] before = Files.readAllBytes(fixture.entry.getImageDataPath());
            var rejected = submit(() -> {
                BackgroundObjectCreation.publishClosed(() -> true, fixture.entry, null, result.data(), fixture.ids(), baseline, null);
                return true;
            });
            assertThrows(ExecutionException.class, () -> rejected.get(20, TimeUnit.SECONDS));
            assertArrayEquals(before, Files.readAllBytes(fixture.entry.getImageDataPath()));
            assertTrue(submit(() -> {
                BackgroundObjectCreation.publishClosed(() -> false, fixture.entry, null, result.data(), fixture.ids(), baseline, null);
                return true;
            }).get(20, TimeUnit.SECONDS));
            try (var read = fixture.entry.readImageData()) {
                var saved = ImageDataFingerprint.roots(read, fixture.ids()).getFirst();
                assertEquals(2, saved.nChildObjects());
            }
            assertEquals(2, ImageDataFingerprint.roots(result.data(), fixture.ids()).getFirst().nChildObjects());
        }
    }

    private static PixelClassifier delayed(PixelClassifier delegate, CountDownLatch entered, CountDownLatch release) {
        return new PixelClassifier() {
            public boolean supportsImage(ImageData<BufferedImage> data) { return delegate.supportsImage(data); }
            public PixelClassifierMetadata getMetadata() { return delegate.getMetadata(); }
            public BufferedImage applyClassification(ImageData<BufferedImage> data, RegionRequest request) throws IOException {
                assertFalse(Platform.isFxApplicationThread()); entered.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                return delegate.applyClassification(data, request);
            }
        };
    }
    private static synchronized <T> FutureTask<T> submit(Callable<T> work) throws Exception {
        if (!fxStarted) {
            var ready = new CountDownLatch(1);
            Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); fxStarted = true;
        }
        var task = new FutureTask<>(work); Platform.runLater(task); return task;
    }
    private static final class Fixture implements AutoCloseable {
        final ImageData<BufferedImage> data;
        final qupath.lib.objects.PathAnnotationObject region;
        final ProjectImageEntry<BufferedImage> entry;
        Fixture(Path folder) throws Exception {
            Class.forName("qupath.lib.scripting.QP");
            var image = new BufferedImage(240,100,BufferedImage.TYPE_INT_RGB);
            for (int y=0; y<100; y++) for (int x=0; x<240; x++) image.setRGB(x,y,x<100 ? 0x966E46 : 0xFAFAFA);
            var path = folder.resolve("slide.png"); ImageIO.write(image,"png",path.toFile());
            var project = Projects.createProject(folder.toFile(), BufferedImage.class);
            try (var server = ImageServers.buildServer(path.toUri())) {
                server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata()).pixelSizeMicrons(0.5,0.5).build());
                entry = project.addImage(server.getBuilder());
            }
            data = entry.readImageData(); data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_DAB);
            region = (qupath.lib.objects.PathAnnotationObject)PathObjects.createAnnotationObject(ROIs.createRectangleROI(0,0,200,100));
            data.getHierarchy().addObject(region); data.getHierarchy().getSelectionModel().setSelectedObject(region);
            entry.saveImageData(data);
        }
        List<String> ids() { return List.of(region.getID().toString()); }
        PixelClassifier classifier() { return PixelClassifiers.createThresholdClassifier(data.getServer().getPixelCalibration(), 0,220,PathClass.fromString("tissue"),PathClass.fromString("empty")); }
        public void close() throws Exception { data.close(); }
    }
}
