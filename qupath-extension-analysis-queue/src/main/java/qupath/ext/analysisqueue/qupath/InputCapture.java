/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import com.google.gson.JsonParser;
import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.workflow.*;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Quick editor snapshot followed by worker-owned serialization and verification. */
public final class InputCapture {
    public record Frozen(Project<BufferedImage> project, ProjectImageEntry<BufferedImage> entry,
                         ImageSnapshot snapshot, List<String> ids, Job job, Path directory) implements AutoCloseable {
        @Override public void close() throws Exception { snapshot.close(); }
    }
    public Frozen freeze(Project<BufferedImage> project, ImageData<BufferedImage> data, List<PathObject> selected,
                         Job job, Path directory) {
        var entry = project.getEntry(data);
        if (entry == null) throw new IllegalArgumentException("Open an image from a saved project first.");
        var roots = selected.isEmpty() ? data.getHierarchy().getAnnotationObjects().stream().filter(p -> p.getParent().isRootObject()).toList() : selected;
        if (roots.isEmpty() || roots.stream().anyMatch(p -> !p.isAnnotation() || p.getParent() == null || !p.getParent().isRootObject() || !p.getROI().isArea()))
            throw new IllegalArgumentException("Draw and select the outer analysis region(s). Select outer regions, not their tissue/empty children.");
        var metadata = data.getServerMetadata();
        if (!metadata.isRGB() || !metadata.getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("The image must be RGB with a pixel calibration in micrometres.");
        if (data.getImageType() != ImageData.ImageType.UNSET && data.getImageType() != ImageData.ImageType.BRIGHTFIELD_H_DAB)
            throw new IllegalArgumentException("This queue uses the brightfield H-DAB workflow.");
        return new Frozen(project, entry, ImageSnapshot.capture(data), roots.stream().map(p -> p.getID().toString()).sorted().toList(), job, directory);
    }
    /** Worker operation. FX only checks live state and publishes prepared files. */
    public void complete(Frozen frozen, FxExecutor fx, BooleanSupplier accepting) throws Exception {
        var project = frozen.project(); var entry = frozen.entry(); var directory = frozen.directory();
        var data = frozen.snapshot().data();
        if (SourceSignature.roots(data, frozen.ids()).stream().anyMatch(p -> p.getROI().getArea() <= 0))
            throw new IllegalArgumentException("The selected outline has no area.");
        var classifierPath = project.getPath().getParent().resolve("classifiers/pixel_classifiers/" + Synj1Analysis.CLASSIFIER + ".json");
        String classifierJson = Files.readString(classifierPath);
        var operation = JsonParser.parseString(classifierJson).getAsJsonObject().getAsJsonObject("op");
        if (operation == null || !operation.has("colorTransforms") ||
                !"Red".equals(operation.getAsJsonArray("colorTransforms").get(0).getAsJsonObject().get("channelName").getAsString()) ||
                operation.getAsJsonObject("op").getAsJsonArray("thresholds").get(0).getAsDouble() != 220.0)
            throw new IllegalArgumentException("The saved classifier must use the Red channel at threshold 220.");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("classifier.json"), classifierJson);
        var signature = SourceSignature.compute(data, frozen.ids());
        try (var prepared = entry.prepareImageDataSave(data)) {
            try (var verified = PathIO.<BufferedImage>readImageData(prepared.stagedPath())) {
                if (!signature.equals(SourceSignature.compute(verified, frozen.ids())))
                    throw new IllegalStateException("Saved input verification failed.");
            }
            Files.copy(prepared.stagedPath(), directory.resolve("input.qpdata"));
            String checksum = JsonFiles.sha256(prepared.stagedPath());
            boolean committed = fx.call(() -> {
                if (!accepting.getAsBoolean()) throw new IllegalStateException("QuPath closed while preparing the input. Add the image again.");
                if (!frozen.snapshot().unchanged()) throw new IllegalStateException("The image was edited while preparing its input. Add the latest outline again.");
                // Switching slides may trigger a normal save. Verify it on the worker.
                if (!prepared.sourceUnchanged()) return false;
                prepared.commit();
                boolean complete = !ImageDataSnapshot.hasUncommittedObjects(frozen.snapshot().source());
                frozen.snapshot().source().setLastSavedPath(prepared.targetPath().toString(), complete);
                if (!complete) frozen.snapshot().source().setChanged(true);
                project.syncChanges();
                return true;
            });
            if (!committed) {
                var saved = SavedFile.check(prepared.targetPath());
                try (var disk = PathIO.<BufferedImage>readImageData(prepared.targetPath())) {
                    if (!signature.equals(SourceSignature.compute(disk, frozen.ids())) || !saved.version().matches(prepared.targetPath()))
                        throw new IllegalStateException("The saved outline changed while preparing its input. Add the latest outline again.");
                }
                checksum = saved.sha256();
            }
            var input = new AnalysisInput(project.getPath().toAbsolutePath().toString(), entry.getID(), entry.getImageName(),
                    prepared.targetPath().toString(), checksum, frozen.ids(), signature,
                    JsonFiles.sha256(directory.resolve("classifier.json")),
                    project.getPath().getParent().resolve("Results/Analysis queue/" + frozen.job().id()).toString());
            JsonFiles.write(directory.resolve("input.json"), input);
        }
    }
    /** Convenience for isolated native tests; the GUI uses the two phases above. */
    public Job capture(Project<BufferedImage> project, ImageData<BufferedImage> data, List<PathObject> selected,
                       Job job, Path directory) throws Exception {
        try (var frozen = freeze(project, data, selected, job, directory)) { complete(frozen, FxExecutor.direct(), () -> true); }
        return job;
    }
}
