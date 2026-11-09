/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.images;

import qupath.lib.objects.*;
import qupath.lib.objects.hierarchy.DefaultTMAGrid;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import java.awt.image.BufferedImage;
import java.util.*;

/**
 * Copies mutable object state on FX, sharing only native immutable ROIs.
 * Geometry serialization, server opening and exact fingerprints belong to workers.
 */
public final class ImageDataSnapshot implements AutoCloseable {
    private final ImageData<BufferedImage> source;
    private final ImageData<BufferedImage> data;
    private final List<Object> stamp;

    private ImageDataSnapshot(ImageData<BufferedImage> source, ImageData<BufferedImage> data, List<Object> stamp) {
        this.source = source; this.data = data; this.stamp = stamp;
    }
    public static ImageDataSnapshot capture(ImageData<BufferedImage> source) {
        var hierarchy = new PathObjectHierarchy();
        var copies = new IdentityHashMap<PathObject, PathObject>();
        var root = source.getHierarchy().getRootObject();
        copies.put(root, hierarchy.getRootObject());
        hierarchy.getRootObject().setID(root.getID());
        hierarchy.getRootObject().getMeasurementList().putAll(root.getMeasurementList());
        hierarchy.getRootObject().getMetadata().putAll(root.getMetadata());
        hierarchy.getRootObject().setName(root.getName());
        hierarchy.getRootObject().setColor(root.getColor());
        hierarchy.getRootObject().setLocked(root.isLocked());
        hierarchy.getRootObject().setPathClass(root.getPathClass(), root.getClassProbability());
        for (var object : ImageDataFingerprint.branch(root)) {
            if (object == root) continue;
            var copy = PathObjectTools.transformObject(object, null, true, false);
            copy.setPathClass(object.getPathClass(), object.getClassProbability());
            copies.put(object, copy);
        }
        var grid = source.getHierarchy().getTMAGrid();
        if (grid != null) hierarchy.setTMAGrid(DefaultTMAGrid.create(
                grid.getTMACoreList().stream().map(c -> (TMACoreObject)copies.get(c)).toList(), grid.getGridWidth()));
        for (var object : ImageDataFingerprint.branch(root)) {
            if (object != root) copies.get(object.getParent()).addChildObject(copies.get(object));
        }
        var selection = source.getHierarchy().getSelectionModel();
        hierarchy.getSelectionModel().setSelectedObjects(
                selection.getSelectedObjects().stream().map(copies::get).filter(Objects::nonNull).toList(),
                copies.get(selection.getSelectedObject()));
        var data = new ImageData<>(source.getServerBuilder(), hierarchy, source.getImageType());
        if (source.isBrightfield()) data.setColorDeconvolutionStains(source.getColorDeconvolutionStains());
        source.getProperties().forEach(data::setProperty);
        data.getHistoryWorkflow().clear();
        data.getHistoryWorkflow().addSteps(source.getHistoryWorkflow().getSteps());
        return new ImageDataSnapshot(source, data, stamp(source));
    }
    public ImageData<BufferedImage> data() { return data; }
    public ImageData<BufferedImage> source() { return source; }
    /** Call on FX, serialized with editor mutations. No geometry traversal or disk I/O. */
    public boolean unchanged() { return stamp.equals(stamp(source)); }

    /** New outlines are selected before the drawing tool adds them to the hierarchy. */
    public static boolean hasUncommittedObjects(ImageData<?> source) {
        return source.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                .anyMatch(p -> !p.isRootObject() && p.getParent() == null);
    }

    /** Cheap edit guard; capture and evaluate on the hierarchy's owner thread. */
    public static java.util.function.BooleanSupplier watch(ImageData<BufferedImage> source) {
        var before = stamp(source);
        return () -> before.equals(stamp(source));
    }

    /** Guard selected branches while allowing edits elsewhere in the image. */
    public static java.util.function.BooleanSupplier watchBranches(ImageData<BufferedImage> source, List<String> ids) {
        var before = branchStamp(source, ids);
        return () -> {
            try { return before.equals(branchStamp(source, ids)); }
            catch (IllegalStateException removed) { return false; }
        };
    }
    private static List<Object> branchStamp(ImageData<BufferedImage> data, List<String> ids) {
        var result = new ArrayList<Object>();
        result.add(data.getImageType()); result.add(data.getColorDeconvolutionStains());
        result.add(data.getServerMetadata());
        for (var root : ImageDataFingerprint.roots(data, ids))
            appendObjects(result, ImageDataFingerprint.branch(root));
        return result;
    }
    private record Identity(Object value) {
        @Override public boolean equals(Object other) { return other instanceof Identity i && value == i.value; }
        @Override public int hashCode() { return System.identityHashCode(value); }
    }
    private static List<Object> stamp(ImageData<BufferedImage> data) {
        var result = new ArrayList<Object>();
        result.add(data.getImageType()); result.add(data.getColorDeconvolutionStains());
        result.add(data.getServerMetadata()); result.add(new LinkedHashMap<>(data.getProperties()));
        result.add(new ArrayList<>(data.getHistoryWorkflow().getSteps()));
        result.add(new Identity(data.getHierarchy().getTMAGrid()));
        appendObjects(result, ImageDataFingerprint.branch(data.getHierarchy().getRootObject()));
        return result;
    }
    private static void appendObjects(List<Object> result, List<PathObject> objects) {
        for (var p : objects) {
            result.add(Arrays.asList(p.getID(), p.getParent() == null ? null : p.getParent().getID(), p.getClass(),
                    new Identity(p.getROI()), new Identity(p instanceof PathCellObject cell ? cell.getNucleusROI() : null),
                    p.getName(), p.getPathClass(), p.getClassProbability(), p.getColor(), p.isLocked(),
                    p instanceof TMACoreObject core ? core.isMissing() : null,
                    new LinkedHashMap<>(p.getMeasurementList().asMap()), new LinkedHashMap<>(p.getMetadata())));
        }
    }
    @Override public void close() throws Exception { data.close(); }
}
