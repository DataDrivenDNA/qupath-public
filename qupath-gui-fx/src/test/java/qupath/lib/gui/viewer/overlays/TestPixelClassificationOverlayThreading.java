/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.viewer.overlays;

import javafx.application.Platform;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.RegionFilter;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.regions.ImageRegion;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.ml.pixel.PixelClassificationMeasurementManager;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestPixelClassificationOverlayThreading {

    @Test
    void requestedTilesReachTheSharedCacheAndNotifyLiveMeasurements() throws Exception {
        try (var server = new WrappedBufferedImageServer("uncached-classifier",
                new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY))) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .channelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
                    .classificationLabels(Map.of(0, PathClass.fromString("tissue"), 1, PathClass.fromString("empty")))
                    .build());
            var data = new ImageData<>(server);
            var notified = new CountDownLatch(1);
            var manager = new PixelClassificationMeasurementManager(server);
            var tile = server.getTileRequestManager().getAllTileRequests().iterator().next();
            var overlay = FxTestTools.call(() -> PixelClassificationOverlay.create(new OverlayOptions(),
                    new java.util.HashMap<>(Map.<ImageData<BufferedImage>, ImageServer<BufferedImage>>of(data, server)), null));
            data.getHierarchy().addListener(event -> {
                if (event.getSource() == overlay && event.isObjectMeasurementEvent()) notified.countDown();
            });
            try {
                assertNull(server.getCachedTile(tile));
                overlay.requestTile(tile, data, server);
                assertTrue(notified.await(10, TimeUnit.SECONDS));
                assertNotNull(server.getCachedTile(tile));
                assertEquals(100.0, manager.getCachedMeasurementValue(data.getHierarchy().getRootObject(), "tissue %").doubleValue());
            } finally {
                FxTestTools.call(() -> { overlay.stop(); return null; });
            }
        }
    }

    @Test
    void viewsOfTheSameImageDoNotShareAnnotationFilterResults() throws Exception {
        var tile = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        var server = new WrappedBufferedImageServer("shared-image", tile) {
            @Override public BufferedImage getCachedTile(TileRequest request) { return tile; }
        };
        var first = new ImageData<>(server);
        var second = new ImageData<>(server);
        var options = new OverlayOptions();
        var checkedSecond = new CountDownLatch(1);
        var overlay = FxTestTools.call(() -> {
            options.setShowPixelClassification(true);
            options.setPixelClassificationRegionFilter((image, region) -> {
                if (image == second) checkedSecond.countDown();
                return image == first;
            });
            return PixelClassificationOverlay.create(options,
                    new java.util.HashMap<>(Map.<ImageData<BufferedImage>, ImageServer<BufferedImage>>of(first, server, second, server)), null);
        });
        try {
            int alpha = 0;
            for (int i = 0; i < 100 && alpha == 0; i++) { alpha = paint(overlay, first); Thread.sleep(20); }
            assertEquals(255, alpha);
            assertEquals(0, paint(overlay, second));
            assertTrue(checkedSecond.await(2, TimeUnit.SECONDS));
            assertEquals(0, paint(overlay, second));
            assertEquals(255, paint(overlay, first));
        } finally {
            FxTestTools.call(() -> { overlay.stop(); return null; });
            server.close();
        }
    }

    @Test
    void slowRegionFiltersRunOutsidePaintAndMeasurementEventsKeepTheirResults() throws Exception {
        var tile = new BufferedImage(64, 64, BufferedImage.TYPE_BYTE_GRAY);
        var server = new WrappedBufferedImageServer("overlay", tile) {
            @Override public BufferedImage getCachedTile(TileRequest request) { return tile; }
        };
        var data = new ImageData<>(server);
        var options = new OverlayOptions();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var onFx = new AtomicBoolean();
        var calls = new AtomicInteger();
        RegionFilter filter = (image, region) -> {
            calls.incrementAndGet();
            onFx.set(Platform.isFxApplicationThread());
            entered.countDown();
            try { assertTrue(release.await(8, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            return true;
        };
        var overlay = FxTestTools.call(() -> {
            options.setShowPixelClassification(true);
            options.setPixelClassificationRegionFilter(filter);
            return PixelClassificationOverlay.create(options, new java.util.HashMap<>(Map.<ImageData<BufferedImage>,ImageServer<BufferedImage>>of(data,server)), null);
        });
        try {
            assertEquals(0, paint(overlay, data));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(onFx.get());
            assertEquals("responsive", FxTestTools.call(() -> "responsive"));
            release.countDown();
            int alpha = 0;
            for (int i=0; i<100 && alpha==0; i++) { Thread.sleep(20); alpha=paint(overlay,data); }
            assertEquals(255,alpha);
            FxTestTools.call(() -> { data.getHierarchy().fireObjectMeasurementsChangedEvent(overlay,java.util.List.of(data.getHierarchy().getRootObject()),true); return null; });
            assertEquals(255,paint(overlay,data));
            assertEquals(1,calls.get());
            FxTestTools.call(() -> { options.setPixelClassificationRegionFilter((image,region) -> false); return null; });
            assertEquals(0,paint(overlay,data));
            Thread.sleep(50);
            assertEquals(0,paint(overlay,data));
        } finally {
            release.countDown();
            FxTestTools.call(() -> { overlay.stop(); return null; });
            server.close();
        }
    }

    private int paint(PixelClassificationOverlay overlay, ImageData<BufferedImage> data) throws Exception {
        return FxTestTools.call(() -> {
            var image = new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();
            try { overlay.paintOverlay(g,ImageRegion.createInstance(0,0,64,64,0,0),1,data,false); }
            finally { g.dispose(); }
            return (image.getRGB(32,32)>>>24)&255;
        });
    }
}
