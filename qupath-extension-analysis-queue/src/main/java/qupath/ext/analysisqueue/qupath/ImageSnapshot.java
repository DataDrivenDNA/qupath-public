/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import java.awt.image.BufferedImage;

/** Compatibility adapter; snapshot ownership belongs to the image model. */
public final class ImageSnapshot implements AutoCloseable {
    private final ImageDataSnapshot snapshot;
    private ImageSnapshot(ImageDataSnapshot snapshot) { this.snapshot = snapshot; }
    public static ImageSnapshot capture(ImageData<BufferedImage> source) {
        return new ImageSnapshot(ImageDataSnapshot.capture(source));
    }
    public ImageData<BufferedImage> data() { return snapshot.data(); }
    public ImageData<BufferedImage> source() { return snapshot.source(); }
    public boolean unchanged() { return snapshot.unchanged(); }
    @Override public void close() throws Exception { snapshot.close(); }
}
