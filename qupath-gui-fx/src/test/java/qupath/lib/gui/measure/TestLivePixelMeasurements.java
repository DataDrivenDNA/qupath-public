/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.measure;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TestLivePixelMeasurements {

    @Test
    void changingClassifierDiscardsPendingResultsFromThePreviousModel() throws Exception {
        var oldImage = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        var newImage = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                newImage.getRaster().setSample(x, y, 0, 1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var oldServer = new WrappedBufferedImageServer("old-classifier", oldImage) {
            @Override public BufferedImage getCachedTile(TileRequest tile) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return oldImage;
            }
        };
        var newServer = new WrappedBufferedImageServer("new-classifier", newImage) {
            @Override public BufferedImage getCachedTile(TileRequest tile) { return newImage; }
        };
        for (var server : List.of(oldServer, newServer))
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .channelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
                    .classificationLabels(Map.of(0, PathClass.fromString("tissue"), 1, PathClass.fromString("empty")))
                    .build());
        var data = new ImageData<>(oldServer);
        var object = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 64, 64));
        data.getHierarchy().addObject(object);
        var model = new ObservableMeasurementTableData();
        try {
            FxTestTools.call(() -> {
                ObservableMeasurementTableData.setPixelLayer(data, oldServer);
                model.setImageData(data, List.of(object));
                assertTrue(Double.isNaN(model.getNumericValueForDisplay(object, "(Live)tissue %")));
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var exportSnapshot = FxTestTools.call(() -> model.snapshotForExport(List.of(object)));
            FxTestTools.call(() -> {
                ObservableMeasurementTableData.setPixelLayer(data, newServer);
                model.updateMeasurementList();
                return null;
            });
            awaitValue(0, () -> model.getNumericValueForDisplay(object, "(Live)tissue %"));
            release.countDown();
            assertEquals(100, exportSnapshot.getNumericValue(object, "(Live)tissue %"),
                    "An export keeps the classifier captured before the view changed");
            assertEquals(0, FxTestTools.call(() -> model.getNumericValueForDisplay(object, "(Live)tissue %")));
            assertEquals(1, data.getHierarchy().nObjects());
            assertTrue(object.getMeasurementList().isEmpty());
        } finally {
            release.countDown();
            FxTestTools.call(() -> { model.setImageData(null, List.of()); return null; });
            oldServer.close();
            newServer.close();
        }
    }

    @Test
    void missingTilesAndEditedRoisRefreshRepeatedlyWithoutLosingExportValues() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 64; y++)
            for (int x = 32; x < 64; x++)
                image.getRaster().setSample(x, y, 0, 1);
        var available = new AtomicBoolean();
        var reads = new AtomicInteger();
        var server = new WrappedBufferedImageServer("two-classes", image) {
            @Override public BufferedImage getCachedTile(TileRequest tile) {
                reads.incrementAndGet();
                return available.get() ? image : null;
            }
        };
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                .channelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
                .classificationLabels(Map.of(0, PathClass.fromString("tissue"), 1, PathClass.fromString("empty")))
                .build());
        var data = new ImageData<>(server);
        var object = (qupath.lib.objects.PathAnnotationObject)PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 32, 64));
        data.getHierarchy().addObject(object);
        ObservableMeasurementTableData.setPixelLayer(data, server);
        var model = new ObservableMeasurementTableData();
        var first = new CountDownLatch(1);
        var revisions = new AtomicInteger();
        try {
            var binding = FxTestTools.call(() -> {
                model.setImageData(data, List.of(object));
                model.measurementRevisionProperty().addListener((obs, oldValue, newValue) -> {
                    revisions.incrementAndGet();
                    first.countDown();
                });
                var b = Bindings.createDoubleBinding(() -> model.getNumericValueForDisplay(object, "(Live)tissue %"),
                        model.measurementRevisionProperty());
                assertTrue(Double.isNaN(b.get()));
                return b;
            });
            assertTrue(first.await(5, TimeUnit.SECONDS));
            assertTrue(Double.isNaN(FxTestTools.call(binding::get)));
            int missingReads = reads.get();
            for (int i = 0; i < 5; i++)
                assertTrue(Double.isNaN(FxTestTools.call(() -> model.getNumericValueForDisplay(object, "(Live)tissue %"))));
            assertEquals(missingReads, reads.get(), "Missing tiles must not cause a busy retry loop");
            available.set(true);
            FxTestTools.call(() -> {
                data.getHierarchy().fireObjectMeasurementsChangedEvent(this, List.of(object), true);
                return null;
            });
            awaitValue(100, binding::get);
            FxTestTools.call(() -> {
                object.setROI(ROIs.createRectangleROI(32, 0, 32, 64));
                data.getHierarchy().fireObjectsChangedEvent(this, List.of(object));
                return null;
            });
            awaitValue(0, binding::get);
            assertTrue(revisions.get() >= 3, "Every completion must notify the view");
            // A synchronous export must calculate a new ROI even if no display request preceded it.
            FxTestTools.call(() -> { object.setROI(ROIs.createRectangleROI(0, 0, 64, 64)); return null; });
            assertEquals(50, model.getNumericValue(object, "(Live)tissue %"));
            assertEquals("50", model.getStringValue(object, "(Live)tissue %", 0));
        } finally {
            FxTestTools.call(() -> { model.setImageData(null, List.of()); return null; });
            server.close();
        }
    }

    private static void awaitValue(double expected, DoubleSupplier supplier) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        double actual;
        do {
            actual = FxTestTools.call(supplier::getAsDouble);
            if (actual == expected)
                return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, actual);
    }

    @Test
    void geometryWorkNeverBlocksJavaFxAndBindingsUpdateWithoutChangingAnnotations() throws Exception {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        var server = new WrappedBufferedImageServer("labels", image) {
            @Override public BufferedImage getCachedTile(TileRequest tile) { return image; }
        };
        server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                .channelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
                .classificationLabels(Map.of(0, PathClass.fromString("tissue"), 1, PathClass.fromString("empty")))
                .build());
        var data = new ImageData<>(server);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var onFx = new AtomicBoolean();
        var shapeCalls = new AtomicInteger();
        var delegate = ROIs.createRectangleROI(0, 0, 64, 64);
        var roi = (ROI) Proxy.newProxyInstance(ROI.class.getClassLoader(), new Class<?>[]{ROI.class}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("getShape")) {
                onFx.set(Platform.isFxApplicationThread());
                shapeCalls.incrementAndGet();
                entered.countDown();
                assertTrue(release.await(8, TimeUnit.SECONDS));
            }
            return method.invoke(delegate, args);
        });
        var object = PathObjects.createAnnotationObject(roi);
        object.getMeasurementList().put("historical", 123.0);
        data.getHierarchy().addObject(object);
        ObservableMeasurementTableData.setPixelLayer(data, server);
        data.setChanged(false);
        var model = new ObservableMeasurementTableData();
        var updated = new CountDownLatch(1);
        try {
            var binding = FxTestTools.call(() -> {
                model.setImageData(data, List.of(object));
                model.measurementRevisionProperty().addListener(obs -> updated.countDown());
                var b = Bindings.createDoubleBinding(() -> model.getNumericValueForDisplay(object, "(Live)tissue %"),
                        model.measurementRevisionProperty());
                assertTrue(Double.isNaN(b.get()));
                assertTrue(Double.isNaN(model.getNumericValueForDisplay(object, "(Live)tissue area px^2")));
                return b;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("responsive", FxTestTools.call(() -> "responsive"));
            assertFalse(onFx.get());
            release.countDown();
            assertTrue(updated.await(5, TimeUnit.SECONDS));
            assertEquals(100.0, FxTestTools.call(binding::get));
            assertEquals(1, shapeCalls.get(), "All columns must share one ROI calculation");
            assertSame(roi, object.getROI());
            assertEquals(1, data.getHierarchy().nObjects());
            assertEquals(123.0, object.getMeasurementList().get("historical"));
            assertEquals(1, object.getMeasurementList().size());
            assertFalse(data.isChanged(), "Display completion must not dirty the project");
            // Non-UI callers still obtain their value synchronously for export.
            assertEquals(100.0, model.getNumericValue(object, "(Live)tissue %"));
        } finally {
            release.countDown();
            FxTestTools.call(() -> { model.setImageData(null, List.of()); return null; });
            server.close();
        }
    }
}
