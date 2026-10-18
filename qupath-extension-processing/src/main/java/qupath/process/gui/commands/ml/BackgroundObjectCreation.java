/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.process.gui.commands.ml;

import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.background.BackgroundTasks;
import qupath.lib.gui.background.BackgroundProgress;
import qupath.lib.gui.viewer.ImageRenderingPreparation;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.*;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.WorkflowStep;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.projects.PreparedImageDataSave;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.io.FileVersion;
import qupath.opencv.ml.pixel.PixelClassifierTools;
import qupath.opencv.ml.pixel.PixelClassifierTools.CreateObjectOptions;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Owns the native classifier job and optimistic publication of its completed branches. */
final class BackgroundObjectCreation {
    private static final Set<Object> ACTIVE = new HashSet<>(); // FX only
    private BackgroundObjectCreation() {}

    static boolean run(ImageData<BufferedImage> source, PixelClassifier classifier,
                       boolean detections, double minArea, double minHoleArea,
                       WorkflowStep step, CreateObjectOptions... options) throws IOException {
        return run(source, classifier, detections, minArea, minHoleArea, step,
                message -> Dialogs.showInfoNotification("Create objects", message),
                PathPrefs.getDefaultQuPathUserDirectory().resolve("background-results"), options);
    }

    static boolean run(ImageData<BufferedImage> source, PixelClassifier classifier,
                       boolean detections, double minArea, double minHoleArea, WorkflowStep step,
                       java.util.function.Consumer<String> notification, Path recoveryDirectory,
                       CreateObjectOptions... options) throws IOException {
        if (ImageDataSnapshot.hasUncommittedObjects(source)) {
            notification.accept("Finish drawing the outline before creating objects.");
            return false;
        }
        var gui = QuPathGUI.getInstance();
        var project = gui == null ? null : gui.getProject();
        var entry = project == null ? null : project.getEntry(source);
        var savedPath = source.getLastSavedPath() == null ? null : Path.of(source.getLastSavedPath());
        Object key = entry != null ? entry : savedPath != null ? savedPath.toAbsolutePath().normalize() : source;
        if (ACTIVE.contains(key)) {
            notification.accept("This image already has an object creation job running.");
            return false;
        }
        var snapshot = ImageDataSnapshot.capture(source);
        var selected = new HashSet<>(snapshot.data().getHierarchy().getSelectionModel().getSelectedObjects());
        if (selected.isEmpty()) selected.add(snapshot.data().getHierarchy().getRootObject());
        // Publish only outermost selected branches; nested selections are already included.
        var ids = selected.stream().filter(p -> {
            for (var parent = p.getParent(); parent != null; parent = parent.getParent())
                if (selected.contains(parent)) return false;
            return true;
        }).map(p -> p.getID().toString()).toList();
        var unchanged = ImageDataSnapshot.watchBranches(source, ids);
        var originalSelection = source.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                .map(PathObject::getID).collect(java.util.stream.Collectors.toSet());
        ACTIVE.add(key);
        boolean completed = false;
        var progress = BackgroundProgress.start("Create regions — " + source.getServerMetadata().getName(),
                "Waiting for the analysis worker");
        try {
            notification.accept("Creating regions in the background. You can continue annotating other regions or images.");
            var baseline = BackgroundTasks.analysis(() -> {
                progress.phase("Checking source annotations");
                var signature = ImageDataFingerprint.compute(snapshot.data(), ids);
                progress.phase(detections ? "Creating detections" : "Creating classified annotations");
                try (var server = PixelClassifierTools.createPixelClassificationServer(snapshot.data(), classifier)) {
                    boolean success = detections
                            ? PixelClassifierTools.createDetectionsFromPixelClassifier(snapshot.data().getHierarchy(), server, minArea, minHoleArea, options)
                            : PixelClassifierTools.createAnnotationsFromPixelClassifier(snapshot.data().getHierarchy(), server, minArea, minHoleArea, options);
                    if (success) {
                        progress.phase("Preparing regions for display");
                        ImageRenderingPreparation.prepare(snapshot.data().getHierarchy());
                    }
                    return success ? signature : null;
                }
            });
            if (baseline == null) return false;
            completed = true;
            progress.phase("Applying completed regions");
            var live = findOpen(gui, source, project, entry, savedPath);
            if (live == source && unchanged.getAsBoolean()) {
                ImageDataBranchUpdate.apply(source, snapshot.data(), ids);
                if (step != null) source.getHistoryWorkflow().addStep(step);
                if (Arrays.asList(options).contains(CreateObjectOptions.SELECT_NEW)
                        && originalSelection.equals(source.getHierarchy().getSelectionModel().getSelectedObjects().stream()
                                .map(PathObject::getID).collect(java.util.stream.Collectors.toSet())))
                    selectResult(source, snapshot.data());
            } else if (live != null) {
                var candidate = ImageDataSnapshot.capture(live);
                var branchUnchanged = ImageDataSnapshot.watchBranches(live, ids);
                try {
                    if (!BackgroundTasks.io(() -> matches(candidate.data(), ids, baseline)) || !branchUnchanged.getAsBoolean()
                            || findOpen(gui, source, project, entry, savedPath) != live)
                        throw new IOException("The target regions were edited while object creation was running");
                    ImageDataBranchUpdate.apply(live, snapshot.data(), ids);
                    if (step != null) live.getHistoryWorkflow().addStep(step);
                } finally { BackgroundTasks.io(() -> { candidate.close(); return null; }); }
            } else {
                progress.phase("Saving completed regions to the closed image");
                publishClosed(() -> findOpen(gui, source, project, entry, savedPath) != null,
                        entry, savedPath, snapshot.data(), ids, baseline, step);
            }
            notification.accept("Object creation finished" + (live == null ? " and results were saved." : "."));
            return true;
        } catch (Exception error) {
            if (completed) {
                progress.phase("Saving completed regions separately for recovery");
                var recovery = BackgroundTasks.io(() -> {
                    if (step != null) snapshot.data().getHistoryWorkflow().addStep(step);
                    var folder = recoveryDirectory;
                    Files.createDirectories(folder);
                    var file = folder.resolve("objects-" + UUID.randomUUID() + ".qpdata");
                    try (var prepared = PreparedImageDataSave.prepare(file, snapshot.data())) { prepared.commit(); }
                    return file;
                });
                throw new IOException(error.getMessage() + ". Your current annotations were kept. Completed results are saved separately at " + recovery, error);
            }
            if (error instanceof IOException io) throw io;
            throw new IOException(error);
        } finally {
            ACTIVE.remove(key);
            try { BackgroundTasks.io(() -> { snapshot.close(); return null; }); }
            finally { progress.close(); }
        }
    }

    private static boolean matches(ImageData<BufferedImage> data, List<String> ids, String signature) {
        try { return signature.equals(ImageDataFingerprint.compute(data, ids)); }
        catch (IllegalStateException removed) { return false; }
    }

    private static ImageData<BufferedImage> findOpen(QuPathGUI gui, ImageData<BufferedImage> source,
            Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry, Path path) {
        if (gui == null) return source;
        return gui.getAllViewers().stream().map(v -> v.getImageData()).filter(Objects::nonNull)
                .filter(d -> d == source || (entry != null && entry.equals(project.getEntry(d)))
                        || (entry == null && path != null && d.getLastSavedPath() != null
                        && path.toAbsolutePath().normalize().equals(Path.of(d.getLastSavedPath()).toAbsolutePath().normalize())))
                .findFirst().orElse(null);
    }

    static void publishClosed(java.util.function.BooleanSupplier imageIsOpen,
            ProjectImageEntry<BufferedImage> entry, Path path, ImageData<BufferedImage> result,
            List<String> ids, String signature, WorkflowStep step) throws IOException {
        if (entry == null && path == null) throw new IOException("The unsaved source image was closed");
        var prepared = BackgroundTasks.io(() -> {
            var target = entry == null ? path : entry.getImageDataPath();
            var version = FileVersion.read(target);
            try (var candidate = entry == null ? PathIO.<BufferedImage>readImageData(path) : entry.readImageData()) {
                if (!matches(candidate, ids, signature)) throw new IOException("The saved target regions have changed");
                ImageDataBranchUpdate.apply(candidate, result, ids);
                if (step != null) candidate.getHistoryWorkflow().addStep(step);
                var staged = entry == null ? PreparedImageDataSave.prepare(path, candidate) : entry.prepareImageDataSave(candidate);
                if (!Objects.equals(version, FileVersion.read(target))) {
                    staged.close();
                    throw new IOException("The saved image changed while results were being prepared");
                }
                return staged;
            }
        });
        try {
            if (imageIsOpen.getAsBoolean())
                throw new IOException("The image was reopened while its result was being saved");
            // Only guarded atomic file renames run on FX; serialization and rollback copies run on I/O.
            prepared.commit();
        } finally {
            BackgroundTasks.io(() -> { prepared.close(); return null; });
        }
    }

    private static void selectResult(ImageData<BufferedImage> target, ImageData<BufferedImage> result) {
        var ids = result.getHierarchy().getSelectionModel().getSelectedObjects().stream().map(PathObject::getID).toList();
        var objects = ImageDataFingerprint.branch(target.getHierarchy().getRootObject()).stream()
                .filter(p -> ids.contains(p.getID())).toList();
        target.getHierarchy().getSelectionModel().setSelectedObjects(objects, null);
    }
}
