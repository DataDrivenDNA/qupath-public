/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.panes;

import javafx.beans.property.ReadOnlyLongProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.commands.ProjectCommands;
import qupath.lib.gui.tools.BackgroundCache;
import qupath.lib.projects.ProjectImageEntry;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** All project-row disk access belongs to this worker boundary, never cell layout. */
final class ProjectEntryDisplayCache {
    private final BackgroundCache<Request, Display> cache;
    private record Request(ProjectImageEntry<BufferedImage> entry, boolean thumbnail, boolean checkUris) {}
    record Display(boolean hasData, boolean missing, String summary, SoftReference<Image> image,
                   boolean thumbnailFailed) {}

    ProjectEntryDisplayCache() {
        this(new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), work -> {
            var thread = new Thread(work, "project-thumbnail-loader");
            thread.setDaemon(true);
            return thread;
        }));
    }
    ProjectEntryDisplayCache(Executor executor) { cache = new BackgroundCache<>(executor); }
    ReadOnlyLongProperty revisionProperty() { return cache.revisionProperty(); }
    void clear() { cache.clear(); }
    void invalidate(ProjectImageEntry<BufferedImage> entry) {
        for (boolean thumbnail : new boolean[]{true, false})
            for (boolean uris : new boolean[]{true, false}) cache.invalidate(new Request(entry, thumbnail, uris));
    }
    Display get(ProjectImageEntry<BufferedImage> entry, boolean thumbnail, boolean checkUris) {
        var request = new Request(entry, thumbnail, checkUris);
        var display = cache.get(request, () -> read(request));
        if (display != null && display.image != null && display.image.get() == null) {
            cache.invalidate(request);
            return cache.get(request, () -> read(request));
        }
        return display;
    }
    private static Display read(Request request) {
        var entry = request.entry;
        boolean hasData = entry.hasImageData();
        String summary = entry.getSummary();
        boolean missing = false;
        if (request.checkUris) {
            try {
                for (var uri : entry.getURIs()) {
                    var path = GeneralTools.toPath(uri);
                    if (path != null && Files.notExists(path)) { missing = true; break; }
                }
            } catch (Exception error) {
                LoggerFactory.getLogger(ProjectEntryDisplayCache.class).warn("Unable to check image paths", error);
            }
        }
        Image image = null;
        boolean failed = false;
        if (request.thumbnail) {
            try {
                var thumbnail = entry.getThumbnail();
                if (thumbnail == null && !Thread.currentThread().isInterrupted()) {
                    try (var server = entry.getServerBuilder().build()) {
                        thumbnail = ProjectCommands.getThumbnailRGB(server);
                        if (!Thread.currentThread().isInterrupted()) entry.setThumbnail(thumbnail);
                    }
                }
                if (thumbnail != null) image = SwingFXUtils.toFXImage(thumbnail, null);
                else failed = true;
            } catch (Exception error) {
                failed = true;
                if (!Thread.currentThread().isInterrupted())
                    LoggerFactory.getLogger(ProjectEntryDisplayCache.class).warn("Unable to read thumbnail for {}", entry.getImageName(), error);
            }
        }
        return new Display(hasData, missing, summary, image == null ? null : new SoftReference<>(image), failed);
    }
}
