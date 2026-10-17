/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.workflow.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/** GUI-thread publication is short and serialized with manual edits and native saves. */
public final class NativeResultPublisher implements ResultPublisher {
    private final QuPathGUI gui;
    private final FxExecutor fx;
    public NativeResultPublisher(QuPathGUI gui, FxExecutor fx) { this.gui = gui; this.fx = fx; }

    @Override public String publish(AnalysisInput input, ImageData<BufferedImage> result) throws Exception {
        var file = Path.of(input.savedPath());
        if (!JsonFiles.sha256(file).equals(input.savedSha256())) return "The source image was saved again after queuing. Results retained; requeue the current outline.";
        var projectPath = Path.of(input.projectPath());
        var project = fx.call(() -> gui != null && sameProject(gui.getProject(), projectPath) ? gui.getProject() : null);
        if (project == null) project = ProjectIO.loadProject(projectPath.toFile(), BufferedImage.class);
        var entry = project.getImageList().stream().filter(e -> e.getID().equals(input.entryId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The queued image is no longer in the project."));
        var savedProject = project;
        String expected = SourceSignature.compute(result, input.regionIds());
        try (var disk = entry.readImageData()) {
            return fx.call(() -> {
                if (!JsonFiles.sha256(file).equals(input.savedSha256())) return "The saved source changed during processing. Results retained for review.";
                var open = gui == null || !sameProject(gui.getProject(), projectPath) ? List.<ImageData<BufferedImage>>of() :
                        gui.getAllViewers().stream().map(v -> v.getImageData()).filter(d -> d != null &&
                                gui.getProject().getEntry(d) != null && gui.getProject().getEntry(d).getID().equals(input.entryId())).distinct().toList();
                if (open.size() > 1) return "This image is open in multiple independent viewers. Results retained; close duplicate views and requeue.";
                var target = open.isEmpty() ? disk : open.getFirst();
                if (!SourceSignature.matches(target, input))
                    return "The outlined region or image settings changed after queuing. Your edits were kept; requeue the current outline.";
                ResultMerger.merge(target, result, input);
                entry.saveImageData(target); savedProject.syncChanges();
                var verified = PathIO.readImageData(Path.of(target.getLastSavedPath()), disk.getServer());
                if (!SourceSignature.compute(verified, input.regionIds()).equals(expected))
                    throw new IllegalStateException("Project saved-data verification did not match the result.");
                return null;
            });
        }
    }
    private static boolean sameProject(Project<?> project, Path path) {
        return project != null && project.getPath().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
    }
}
