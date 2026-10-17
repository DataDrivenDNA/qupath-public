/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import org.locationtech.jts.io.WKBWriter;
import qupath.lib.color.ColorDeconvolutionStains;
import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.lib.images.ImageData;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Compares the input settings and selected branches, independent of unrelated annotation edits. */
public final class SourceSignature {
    private SourceSignature() {}
    public static boolean matches(ImageData<BufferedImage> data, AnalysisInput input) {
        try { return compute(data, input.regionIds()).equals(input.sourceSignature()); }
        catch (IllegalStateException removedRegion) { return false; }
    }
    public static List<PathObject> roots(ImageData<BufferedImage> data, List<String> ids) {
        var byId = new HashMap<String, PathObject>();
        data.getHierarchy().getAnnotationObjects().forEach(p -> byId.put(p.getID().toString(), p));
        return ids.stream().map(id -> {
            var object = byId.get(id);
            if (object == null) throw new IllegalStateException("An outlined region was removed: " + id);
            return object;
        }).toList();
    }
    public static List<PathObject> branch(PathObject root) {
        var objects = new ArrayList<PathObject>();
        var pending = new ArrayDeque<PathObject>(); pending.add(root);
        while (!pending.isEmpty()) { var object = pending.removeFirst(); objects.add(object); pending.addAll(object.getChildObjects()); }
        return objects;
    }
    public static String compute(ImageData<BufferedImage> data, List<String> ids) {
        var digest = JsonFiles.digest();
        var server = data.getServer();
        text(digest, data.getImageType().name());
        // Native deserialization renormalizes stain vectors, changing last-bit doubles.
        // Canonicalize only these vectors; geometry and measured values remain exact.
        text(digest, ColorDeconvolutionStains.getColorDeconvolutionStainsAsString(data.getColorDeconvolutionStains(), 12));
        text(digest, GsonTools.getInstance().toJson(server.getPixelCalibration()));
        text(digest, server.getWidth() + ":" + server.getHeight() + ":" + server.nChannels());
        server.getURIs().stream().map(Object::toString).sorted().forEach(uri -> text(digest, uri));
        var writer = new WKBWriter();
        roots(data, ids).stream().flatMap(root -> branch(root).stream())
                .sorted(Comparator.comparing(p -> p.getID().toString())).forEach(object -> {
                    text(digest, object.getID().toString());
                    text(digest, object.getParent() == null ? "" : object.getParent().getID().toString());
                    text(digest, object.getClass().getName());
                    text(digest, object.getName()); text(digest, Objects.toString(object.getPathClass(), ""));
                    text(digest, Boolean.toString(object.isLocked())); text(digest, Objects.toString(object.getColor(), ""));
                    if (object.getROI() != null) {
                        text(digest, object.getROI().getImagePlane().toString());
                        digest.update(writer.write(object.getROI().getGeometry()));
                    }
                    new TreeMap<>(object.getMeasurementList().asMap()).forEach((key, value) -> {
                        text(digest, key); text(digest, Double.toHexString(value.doubleValue()));
                    });
                    new TreeMap<>(object.getMetadata()).forEach((key, value) -> { text(digest, key); text(digest, value); });
                });
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void text(MessageDigest digest, String value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
}
