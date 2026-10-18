/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.ImageRenderingPreparation;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import qupath.lib.io.FileVersion;
import java.util.Objects;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/** Latest request wins; edits made during loading always keep their current viewer. */
public final class BackgroundImageLoad {
    private final java.util.function.Consumer<String> notification;
    public BackgroundImageLoad() { this(message -> Dialogs.showInfoNotification("Load image", message)); }
    BackgroundImageLoad(java.util.function.Consumer<String> notification) { this.notification = notification; }
    private final Map<QuPathViewer, Object> requests = new WeakHashMap<>();

    public Request begin(QuPathViewer viewer) {
        var token = new Object();
        requests.put(viewer, token);
        var source = viewer.getImageData();
        var unchanged = source == null ? (BooleanSupplier)() -> true : ImageDataSnapshot.watch(source);
        return new Request(viewer, source, token, unchanged);
    }

    public final class Request {
        private final QuPathViewer viewer;
        private final ImageData<BufferedImage> source;
        private final Object token;
        private final BooleanSupplier unchanged;
        private Request(QuPathViewer viewer, ImageData<BufferedImage> source, Object token, BooleanSupplier unchanged) {
            this.viewer = viewer; this.source = source; this.token = token; this.unchanged = unchanged;
        }
        public boolean current() {
            return requests.get(viewer) == token && viewer.getImageData() == source
                    && !ViewerEditGuard.isEditing(viewer) && unchanged.getAsBoolean();
        }
        /** Loader owns the returned server; abandoned results are closed on the worker. */
        public boolean load(Callable<ImageData<BufferedImage>> loader, BooleanSupplier contextCurrent, Path file) throws IOException {
            var loaded = BackgroundTasks.io(() -> {
                var version = FileVersion.read(file);
                var data = loader.call();
                try {
                    ImageRenderingPreparation.prepare(data.getHierarchy());
                    return new Loaded(data, version);
                } catch (Exception | Error error) {
                    try { data.close(); } catch (Exception close) { error.addSuppressed(close); }
                    throw error;
                }
            });
            var result = loaded.data;
            boolean applied = false;
            try {
                if (!current() || !contextCurrent.getAsBoolean() || !Objects.equals(loaded.version, FileVersion.read(file))) {
                    notification.accept("Loading finished. Your newer image choice or annotation edits were kept. Open the image again when ready.");
                    return false;
                }
                try { viewer.setImageData(result); }
                catch (IOException | RuntimeException error) {
                    // Viewer initialization can fail after changing its image property.
                    try { viewer.setImageData(source); }
                    catch (Exception restore) { error.addSuppressed(restore); }
                    throw error;
                }
                applied = true;
                return true;
            } finally {
                if (!applied) BackgroundTasks.io(() -> { result.close(); return null; });
            }
        }
    }
    private record Loaded(ImageData<BufferedImage> data, FileVersion version) {}
}
