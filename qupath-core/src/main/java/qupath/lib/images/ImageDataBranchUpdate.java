/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.images;

import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectTools;
import java.awt.image.BufferedImage;
import java.util.*;

/** Publishes checked child branches without transferring ownership of result objects. */
public final class ImageDataBranchUpdate {
    private ImageDataBranchUpdate() {}

    /** Use a branch stamp or fingerprint to validate the target before calling. */
    public static void apply(ImageData<BufferedImage> target, ImageData<BufferedImage> result, List<String> ids) {
        var destinations = ImageDataFingerprint.roots(target, ids);
        var sources = ImageDataFingerprint.roots(result, ids);
        var selection = target.getHierarchy().getSelectionModel();
        var unfinished = new HashMap<UUID, PathObject>();
        selection.getSelectedObjects().stream().filter(p -> !p.isRootObject() && p.getParent() == null)
                .forEach(p -> unfinished.put(p.getID(), p));
        var selected = selection.getSelectedObjects().stream().map(PathObject::getID).toList();
        var primary = selection.getSelectedObject() == null ? null : selection.getSelectedObject().getID();
        // Prepare every branch before changing the live hierarchy.
        var children = sources.stream().map(p -> p.getChildObjects().stream()
                .map(ImageDataBranchUpdate::copyBranch).toList()).toList();
        for (int i = 0; i < destinations.size(); i++) {
            var parent = destinations.get(i);
            parent.clearChildObjects();
            parent.addChildObjects(children.get(i));
            parent.setLocked(sources.get(i).isLocked());
        }
        target.getHierarchy().fireHierarchyChangedEvent(ImageDataBranchUpdate.class);
        var objects = new HashMap<UUID, PathObject>();
        ImageDataFingerprint.branch(target.getHierarchy().getRootObject()).forEach(p -> objects.put(p.getID(), p));
        unfinished.forEach(objects::putIfAbsent);
        selection.setSelectedObjects(selected.stream().map(objects::get).filter(Objects::nonNull).toList(), objects.get(primary));
    }

    private static PathObject copyBranch(PathObject source) {
        var copies = new IdentityHashMap<PathObject, PathObject>();
        for (var object : ImageDataFingerprint.branch(source)) {
            var copy = PathObjectTools.transformObject(object, null, true, false);
            copy.setPathClass(object.getPathClass(), object.getClassProbability());
            copies.put(object, copy);
            if (object != source) copies.get(object.getParent()).addChildObject(copy);
        }
        return copies.get(source);
    }
}
