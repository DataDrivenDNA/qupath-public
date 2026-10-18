/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer.overlays;

import org.junit.jupiter.api.Test;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import static org.junit.jupiter.api.Assertions.*;

class TestAnnotationLabelCache {
    @Test void nativeLabelPlacementIsPreservedAcrossRoiKinds() {
        var plane = ImagePlane.getDefaultPlane();
        check(ROIs.createRectangleROI(10,20,100,80,plane),60,20);
        check(ROIs.createEllipseROI(10,20,100,80,plane),60,20);
        check(ROIs.createLineROI(10,20,110,100,plane),60,60);
        check(ROIs.createPolygonROI(new double[]{0,100,100,0},new double[]{0,0,200,200},plane),0,0);
        assertNull(AnnotationLabelCache.get(ROIs.createEmptyROI()));
    }
    private static void check(ROI roi, double x, double y) {
        Point2 point = AnnotationLabelCache.get(roi);
        assertNotNull(point); assertEquals(x,point.getX(),1e-8); assertEquals(y,point.getY(),1e-8);
        assertSame(point, AnnotationLabelCache.get(roi));
    }
}
