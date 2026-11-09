/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestQuPathViewerUtils {
    @Test
    void hoverClassificationSkipsAnnotationContainmentAndRetainsClassFormatting() {
        var containmentChecks = new AtomicInteger();
        var delegate = ROIs.createRectangleROI(0, 0, 100, 100);
        var roi = (ROI) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ROI.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("contains")) containmentChecks.incrementAndGet();
                    return method.invoke(delegate, args);
                });
        var detection = PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 10, 10));
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(List.of(PathObjects.createAnnotationObject(roi), detection));
        containmentChecks.set(0);

        assertEquals(PathClass.NULL_CLASS.toString(), QuPathViewerUtils.getImageObjectClassificationString(
                hierarchy, 10, 10, ImagePlane.getDefaultPlane()));
        detection.setPathClass(PathClass.fromString("Tissue"));
        assertEquals("Tissue", QuPathViewerUtils.getImageObjectClassificationString(
                hierarchy, 10, 10, ImagePlane.getDefaultPlane()));
        assertEquals(0, containmentChecks.get());
    }
}
