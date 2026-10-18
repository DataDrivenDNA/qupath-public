/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.viewer;

import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

/** Worker preparation of the native display cache; never changes analysis geometry. */
public final class ImageRenderingPreparation {
    private ImageRenderingPreparation() {}

    /** Call on an independently owned hierarchy before publishing it to a viewer. */
    public static void prepare(PathObjectHierarchy hierarchy) {
        for (var object : hierarchy.getFlattenedObjectList(null)) {
            DownsampledShapeCache.prepareForRendering(object.getROI());
            if (object instanceof PathCellObject cell) DownsampledShapeCache.prepareForRendering(cell.getNucleusROI());
        }
    }
}
