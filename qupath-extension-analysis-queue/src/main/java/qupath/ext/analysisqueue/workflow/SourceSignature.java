/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataFingerprint;
import qupath.lib.objects.PathObject;
import java.awt.image.BufferedImage;
import java.util.List;

/** Queue input contract, using the shared native image fingerprint. */
public final class SourceSignature {
    private SourceSignature() {}
    public static boolean matches(ImageData<BufferedImage> data, AnalysisInput input) {
        try { return compute(data, input.regionIds()).equals(input.sourceSignature()); }
        catch (IllegalStateException removedRegion) { return false; }
    }
    public static List<PathObject> roots(ImageData<BufferedImage> data, List<String> ids) {
        return ImageDataFingerprint.roots(data, ids);
    }
    public static List<PathObject> branch(PathObject root) { return ImageDataFingerprint.branch(root); }
    public static String compute(ImageData<BufferedImage> data, List<String> ids) {
        return ImageDataFingerprint.compute(data, ids);
    }
}
