/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.transform.Scale;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.QuPathViewerListener;
import qupath.lib.images.ImageData;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Coordinates adapters and lifecycle; each session owns exactly one disk writer. */
final class RecordingSession implements AutoCloseable {
    private final QuPathGUI gui;
    private final RecordWriter writer;
    private final UiRecording ui;
    private final Map<ImageData<BufferedImage>, ImageRecording> images = new IdentityHashMap<>();
    private final Map<ImageData<BufferedImage>, String> imageIds = new WeakHashMap<>();
    private final Set<QuPathViewer> viewers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<QuPathViewer, Long> lastView = new IdentityHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private final Timeline timer;
    private final ListChangeListener<QuPathViewer> viewerChanges;
    private final ChangeListener<Object> projectChanges;
    private final QuPathViewerListener viewerListener;
    private final AppenderBase<ILoggingEvent> appender;
    private final ch.qos.logback.classic.Logger rootLogger;
    private final Thread shutdown;
    private FxResponsivenessMonitor responsiveness;
    private int imageNumber;
    private int frameNumber;
    private boolean closed;

    RecordingSession(QuPathGUI gui, Path path, Consumer<Throwable> failed) throws IOException {
        this.gui = gui;
        writer = new RecordWriter(path, failed);
        try {
        writer.event("session.start", Map.of("pid", ProcessHandle.current().pid(), "qupathVersion", QuPathGUI.getVersion().toString(),
                "extensionVersion", "1.1.0", "formatLocale", Locale.getDefault(Locale.Category.FORMAT).toLanguageTag(),
                "screenshots", "QuPath JavaFX scenes after activity, at most once per 2 seconds",
                "scope", "UI actions, committed fields, workflow, annotations, selections, view, INFO/WARN/ERROR logs"));
        ui = new UiRecording(writer, () -> dirty.set(true));
        ui.observeMenus(gui.getMenuBar().getMenus());
        viewerListener = new QuPathViewerListener() {
            @Override public void imageDataChanged(QuPathViewer viewer, ImageData<BufferedImage> old, ImageData<BufferedImage> next) { writer.observe("image.change", () -> refreshImages()); }
            @Override public void visibleRegionChanged(QuPathViewer viewer, Shape shape) { writer.observe("viewer", () -> viewChanged(viewer)); }
            @Override public void selectedObjectChanged(QuPathViewer viewer, qupath.lib.objects.PathObject object) {}
            @Override public void viewerClosed(QuPathViewer viewer) { writer.observe("viewer.close", () -> refreshViewers()); }
        };
        viewerChanges = change -> writer.observe("viewers", () -> refreshViewers());
        projectChanges = (observable, old, next) -> writer.observe("project", () -> project());
        gui.getAllViewers().addListener(viewerChanges);
        gui.projectProperty().addListener(projectChanges);
        refreshViewers(); project();
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            appender = new AppenderBase<>() {
                @Override protected void append(ILoggingEvent event) {
                    var fields = new LinkedHashMap<String, Object>();
                    fields.put("level", event.getLevel().toString()); fields.put("logger", event.getLoggerName());
                    fields.put("thread", event.getThreadName()); fields.put("message", event.getFormattedMessage());
                    if (event.getThrowableProxy() != null) fields.put("exception", ThrowableProxyUtil.asString(event.getThrowableProxy()));
                    writer.event("application.log", fields);
                }
            };
            appender.setContext(context); appender.setName("QuPath session recorder"); appender.start();
            rootLogger.addAppender(appender);
        } else { rootLogger = null; appender = null; }
        timer = new Timeline(new KeyFrame(Duration.seconds(2), event -> writer.observe("poll", () -> {
            if (!writer.isHealthy()) return;
            images.values().forEach(ImageRecording::pollSavedState);
            if (dirty.getAndSet(false)) captureFrames();
        })));
        timer.setCycleCount(Timeline.INDEFINITE); timer.play();
        responsiveness = FxResponsivenessMonitor.start(writer);
        shutdown = new Thread(() -> {
            writer.event("session.shutdown", Map.of("time", Instant.now().toString()));
            writer.close();
            try { writer.awaitClosed(10000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "qupath-recorder-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    Path directory() { return writer.directory(); }
    boolean healthy() { return writer.isHealthy(); }
    long droppedEvents() { return writer.droppedEvents(); }
    long assetErrors() { return writer.assetErrors(); }
    long observerErrors() { return writer.observerErrors(); }
    int backlog() { return writer.backlog(); }

    private void project() {
        var project = gui.getProject();
        writer.event("project", Map.of("path", project == null ? "" : project.getPath().toString()));
        dirty.set(true);
    }

    private void refreshViewers() {
        var current = new HashSet<>(gui.getAllViewers());
        for (var viewer : new HashSet<>(viewers)) {
            if (!current.contains(viewer)) { viewer.removeViewerListener(viewerListener); viewers.remove(viewer); lastView.remove(viewer); }
        }
        for (var viewer : current) if (viewers.add(viewer)) viewer.addViewerListener(viewerListener);
        refreshImages();
    }

    private void refreshImages() {
        var active = Collections.newSetFromMap(new IdentityHashMap<ImageData<BufferedImage>, Boolean>());
        for (var viewer : gui.getAllViewers()) if (viewer.getImageData() != null) active.add(viewer.getImageData());
        for (var image : new ArrayList<>(images.keySet())) if (!active.contains(image)) images.remove(image).close();
        for (var image : active) {
            if (images.containsKey(image)) continue;
            // A new attachment gets a new asset namespace even when the same ImageData returns.
            String id = "image-" + (++imageNumber);
            imageIds.put(image, id);
            var entry = gui.getProject() == null ? null : gui.getProject().getEntry(image);
            var fields = new LinkedHashMap<String, Object>();
            fields.put("image", id); fields.put("entryId", entry == null ? null : entry.getID());
            fields.put("name", entry == null ? gui.getDisplayedImageName(image) : entry.getImageName());
            fields.put("savedPath", image.getLastSavedPath());
            fields.put("serverType", image.getServer().getClass().getName());
            writer.event("image.attach", fields);
            images.put(image, new ImageRecording(image, writer, id, () -> dirty.set(true)));
        }
        dirty.set(true);
    }

    private void viewChanged(QuPathViewer viewer) {
        long now = System.nanoTime();
        long previous = lastView.getOrDefault(viewer, 0L);
        if (now - previous < 250_000_000L) return;
        lastView.put(viewer, now);
        writer.event("viewer", Map.of("viewer", System.identityHashCode(viewer),
                "image", imageIds.getOrDefault(viewer.getImageData(), ""),
                "centerX", viewer.getCenterPixelX(), "centerY", viewer.getCenterPixelY(),
                "downsample", viewer.getDownsampleFactor(), "z", viewer.getZPosition(), "t", viewer.getTPosition()));
        dirty.set(true);
    }

    void checkpoint() {
        writer.event("checkpoint", Map.of("reason", "user request"));
        images.values().forEach(image -> writer.observe("checkpoint", image::snapshotAnnotations));
        dirty.set(true);
    }

    private void captureFrames() {
        if (writer.backlog() > 256) {
            writer.event("screenshot.skipped", Map.of("reason", "storage backlog", "queued", writer.backlog()));
            return;
        }
        for (var window : new ArrayList<>(Window.getWindows())) {
            if (!window.isShowing() || window.getScene() == null) continue;
            var scene = window.getScene();
            if (scene.getWidth() <= 0 || scene.getHeight() <= 0) continue;
            try {
                double scale = Math.min(1, 1280.0 / Math.max(scene.getWidth(), scene.getHeight()));
                var parameters = new SnapshotParameters();
                parameters.setTransform(new Scale(scale, scale)); parameters.setFill(scene.getFill());
                var pixels = SwingFXUtils.fromFXImage(scene.getRoot().snapshot(parameters, null), null);
                String file = String.format(Locale.ROOT, "frames/%06d.png", ++frameNumber);
                writer.asset("screenshot", file, Map.of("window", window.getClass().getSimpleName(),
                        "width", pixels.getWidth(), "height", pixels.getHeight()), path -> {
                    if (!ImageIO.write(pixels, "png", path.toFile())) throw new IOException("No PNG encoder");
                });
            } catch (RuntimeException e) { writer.event("screenshot.error", Map.of("error", e.toString())); }
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (responsiveness != null) responsiveness.close();
        if (timer != null) timer.stop();
        if (rootLogger != null) { rootLogger.detachAppender(appender); appender.stop(); }
        if (viewerChanges != null) gui.getAllViewers().removeListener(viewerChanges);
        if (projectChanges != null) gui.projectProperty().removeListener(projectChanges);
        viewers.forEach(viewer -> viewer.removeViewerListener(viewerListener));
        images.values().forEach(ImageRecording::close);
        images.clear(); viewers.clear();
        if (ui != null) ui.close();
        writer.event("session.stop", Map.of("droppedEvents", writer.droppedEvents()));
        writer.close();
        // Keep the shutdown hook until pending records/assets are safely flushed.
        Thread.ofVirtual().name("qupath-recorder-flush").start(() -> {
            try {
                writer.awaitClosed(0);
                if (shutdown != null) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {}
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
}
