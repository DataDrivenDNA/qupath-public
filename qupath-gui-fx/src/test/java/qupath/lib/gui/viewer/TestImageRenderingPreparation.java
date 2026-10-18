/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer;

import org.junit.jupiter.api.Test;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.awt.Shape;
import java.lang.reflect.Proxy;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TestImageRenderingPreparation {
    @Test void workerPreparationDoesNotBlockOtherViewersAndPreservesNativeShapes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var roi = polygon();
        var expected = DownsampledShapeCache.getShapeForDownsample(roi, 32);
        var slow = (ROI)Proxy.newProxyInstance(ROI.class.getClassLoader(), new Class<?>[]{ROI.class}, (proxy, method, args) -> {
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            if (method.getName().equals("getShape")) {
                assertFalse(javafx.application.Platform.isFxApplicationThread());
                entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return method.invoke(roi, args);
        });
        var hierarchy = new PathObjectHierarchy(); hierarchy.addObject(PathObjects.createAnnotationObject(slow));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var worker = executor.submit(() -> ImageRenderingPreparation.prepare(hierarchy));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try { assertSame(expected, FxTestTools.call(() -> DownsampledShapeCache.getShapeForDownsample(roi,32))); }
            finally { release.countDown(); }
            worker.get(10, TimeUnit.SECONDS);
            for (double scale : new double[]{1,4,8,16,32,128,1024})
                assertShapeEquals(DownsampledShapeCache.getShapeForDownsample(roi,scale),
                        DownsampledShapeCache.getShapeForDownsample(slow,scale));
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentRequestsShareCompleteNativeCacheLevels() throws Exception {
        var roi = polygon();
        var executor = Executors.newFixedThreadPool(3);
        try {
            var tasks = new java.util.ArrayList<Callable<Shape>>();
            for (int i=0;i<18;i++) tasks.add(() -> DownsampledShapeCache.getShapeForDownsample(roi,64));
            var results = executor.invokeAll(tasks);
            Shape first = results.getFirst().get();
            for (var result : results) assertSame(first, result.get());
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static ROI polygon() {
        double[] x = new double[4000], y = new double[4000];
        for(int i=0;i<x.length;i++) { double angle=2*Math.PI*i/x.length; x[i]=500+450*Math.cos(angle); y[i]=500+400*Math.sin(angle); }
        return ROIs.createPolygonROI(x,y,qupath.lib.regions.ImagePlane.getDefaultPlane());
    }
    private static void assertShapeEquals(Shape one, Shape two) {
        var a=one.getPathIterator(null); var b=two.getPathIterator(null);
        double[] ca=new double[6], cb=new double[6];
        while(!a.isDone()) {
            assertFalse(b.isDone()); assertEquals(a.currentSegment(ca),b.currentSegment(cb));
            assertArrayEquals(ca,cb); a.next(); b.next();
        }
        assertTrue(b.isDone());
    }
}
