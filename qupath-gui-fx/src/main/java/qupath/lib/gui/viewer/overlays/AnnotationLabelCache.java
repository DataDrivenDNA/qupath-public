/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer.overlays;

import qupath.lib.geom.Point2;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.ROI;
import java.util.*;

/** Native label positions shared by worker preparation and overlay painting. */
public final class AnnotationLabelCache {
    private static final Map<ROI, Point2> points = Collections.synchronizedMap(new WeakHashMap<>());
    private AnnotationLabelCache() {}

    public static Point2 get(ROI roi) {
        var cached = points.get(roi);
        if (cached != null) return cached;
        // Finding a position may traverse millions of vertices. Keep the shared
        // lock free so another viewer can paint its already prepared labels.
        var created = find(roi);
        return created == null ? null : points.computeIfAbsent(roi, ignored -> created);
    }
    private static Point2 find(ROI roi) {
        double x = roi.getCentroidX();
        if (!Double.isFinite(x)) x = roi.getBoundsX() + roi.getBoundsWidth() / 2.0;
        double y = roi.getBoundsY();
        if (Double.isNaN(x) || Double.isNaN(y) || roi.isEmpty()) return null;
        if (roi instanceof RectangleROI || roi instanceof EllipseROI) return new Point2(x, y);
        if (roi instanceof LineROI) return new Point2(roi.getCentroidX(), roi.getCentroidY());
        Point2 target = new Point2(x, y);
        return roi.getAllPoints().stream()
                .filter(p -> Math.abs(p.getY() - target.getY()) < 1e-3)
                .min(Comparator.comparingDouble(p -> p.distanceSq(target)))
                .orElse(null);
    }
}
