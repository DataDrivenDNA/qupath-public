/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import com.google.gson.JsonParser;
import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.workflow.AnalysisInput;
import qupath.ext.analysisqueue.workflow.SourceSignature;
import qupath.ext.analysisqueue.workflow.Synj1Analysis;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObject;
import qupath.lib.projects.Project;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Called on the GUI thread to capture a coherent input before the user continues editing. */
public final class InputCapture {
    public Job capture(Project<BufferedImage> project, ImageData<BufferedImage> data, List<PathObject> selected,
                       Job job, Path directory) throws Exception {
        var entry = project.getEntry(data);
        if (entry == null) throw new IllegalArgumentException("Open an image from a saved project first.");
        var roots = selected.isEmpty() ? data.getHierarchy().getAnnotationObjects().stream().filter(p -> p.getParent().isRootObject()).toList() : selected;
        if (roots.isEmpty() || roots.stream().anyMatch(p -> !p.isAnnotation() || !p.getParent().isRootObject() || !p.getROI().isArea() || p.getROI().getArea() <= 0))
            throw new IllegalArgumentException("Draw and select the outer analysis region(s). Select outer regions, not their tissue/empty children.");
        if (!data.getServer().isRGB() || !data.getServer().getPixelCalibration().hasPixelSizeMicrons())
            throw new IllegalArgumentException("The image must be RGB with a pixel calibration in micrometres.");
        if (data.getImageType() != ImageData.ImageType.UNSET && data.getImageType() != ImageData.ImageType.BRIGHTFIELD_H_DAB)
            throw new IllegalArgumentException("This queue uses the brightfield H-DAB workflow.");
        var classifierPath = project.getPath().getParent().resolve("classifiers/pixel_classifiers/" + Synj1Analysis.CLASSIFIER + ".json");
        String classifierJson = Files.readString(classifierPath);
        var operation = JsonParser.parseString(classifierJson).getAsJsonObject().getAsJsonObject("op");
        if (operation == null || !operation.has("colorTransforms") ||
                !"Red".equals(operation.getAsJsonArray("colorTransforms").get(0).getAsJsonObject().get("channelName").getAsString()) ||
                operation.getAsJsonObject("op").getAsJsonArray("thresholds").get(0).getAsDouble() != 220.0)
            throw new IllegalArgumentException("The saved classifier must use the Red channel at threshold 220.");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("classifier.json"), classifierJson);
        var ids = roots.stream().map(p -> p.getID().toString()).sorted().toList();
        var signature = SourceSignature.compute(data, ids);
        // The stream overload does not change the viewer's last-saved path or dirty state.
        try (var stream = Files.newOutputStream(directory.resolve("input.qpdata"))) { PathIO.writeImageData(stream, data); }
        entry.saveImageData(data); project.syncChanges();
        var input = new AnalysisInput(project.getPath().toAbsolutePath().toString(), entry.getID(), entry.getImageName(),
                data.getLastSavedPath(), JsonFiles.sha256(Path.of(data.getLastSavedPath())), ids, signature,
                JsonFiles.sha256(directory.resolve("classifier.json")),
                project.getPath().getParent().resolve("Results/Analysis queue/" + job.id()).toString());
        JsonFiles.write(directory.resolve("input.json"), input);
        return job;
    }
}
