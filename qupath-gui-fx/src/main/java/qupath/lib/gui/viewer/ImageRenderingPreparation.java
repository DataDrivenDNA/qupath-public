/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer;

import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.gui.viewer.overlays.AnnotationLabelCache;

/** Worker preparation of the native display cache; never changes analysis geometry. */
public final class ImageRenderingPreparation {
    private ImageRenderingPreparation() {}

    /** Call on an independently owned hierarchy before publishing it to a viewer. */
    public static void prepare(PathObjectHierarchy hierarchy) {
        for (var object : hierarchy.getFlattenedObjectList(null)) {
            DownsampledShapeCache.prepareForRendering(object.getROI());
            if (object instanceof PathCellObject cell) DownsampledShapeCache.prepareForRendering(cell.getNucleusROI());
            if (object.getName() != null && !object.getName().isBlank()) {
                if (object.getROI() != null) AnnotationLabelCache.get(object.getROI());
                if (object instanceof PathCellObject cell && cell.getNucleusROI() != null)
                    AnnotationLabelCache.get(cell.getNucleusROI());
            }
        }
    }
}
