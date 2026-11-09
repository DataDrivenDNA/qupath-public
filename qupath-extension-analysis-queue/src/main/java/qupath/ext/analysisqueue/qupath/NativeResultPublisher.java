/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.ext.analysisqueue.workflow.*;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.ImageRenderingPreparation;
import qupath.lib.images.ImageData;
import qupath.lib.images.ImageDataSnapshot;
import qupath.lib.io.PathIO;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

/** Workers prepare and verify native files; FX checks edits and commits the result. */
public final class NativeResultPublisher implements ResultPublisher {
    private final EditorAccess editor;
    private final FxExecutor fx;
    public NativeResultPublisher(QuPathGUI gui, FxExecutor fx) {
        this(fx, new EditorAccess() {
            public Project<BufferedImage> project() { return gui == null ? null : gui.getProject(); }
            public List<ImageData<BufferedImage>> openImages(AnalysisInput input) {
                if (gui == null || !sameProject(gui.getProject(), Path.of(input.projectPath()))) return List.of();
                return gui.getAllViewers().stream().map(v -> v.getImageData()).filter(d -> d != null &&
                        gui.getProject().getEntry(d) != null && gui.getProject().getEntry(d).getID().equals(input.entryId())).distinct().toList();
            }
        });
    }
    public NativeResultPublisher(FxExecutor fx, EditorAccess editor) { this.editor = editor; this.fx = fx; }

    @Override public String publish(AnalysisInput input, ImageData<BufferedImage> result) throws Exception {
        var file = Path.of(input.savedPath());
        var saved = SavedFile.check(file);
        if (!saved.sha256().equals(input.savedSha256())) return "The source image was saved again after queuing. Results retained; requeue the current outline.";
        var projectPath = Path.of(input.projectPath());
        var project = fx.call(() -> sameProject(editor.project(), projectPath) ? editor.project() : null);
        if (project == null) project = ProjectIO.loadProject(projectPath.toFile(), BufferedImage.class);
        var entry = project.getImageList().stream().filter(e -> e.getID().equals(input.entryId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The queued image is no longer in the project."));
        var savedProject = project;
        var open = fx.call(() -> editor.openImages(input));
        if (open.size() > 1) return "This image is open in multiple independent viewers. Results retained; close duplicate views and requeue.";
        var snapshot = fx.call(() -> open.isEmpty() ? null : ImageSnapshot.capture(open.getFirst()));
        // Avoid the entry monitor while reading a large data file.
        try (var candidate = snapshot == null ? PathIO.<BufferedImage>readImageData(file) : snapshot.data()) {
            if (!SourceSignature.matches(candidate, input)) return "The outlined region or image settings changed after queuing. Your edits were kept; requeue the current outline.";
            String expected = SourceSignature.compute(result, input.regionIds());
            ImageRenderingPreparation.prepare(result.getHierarchy());
            ResultMerger.applyVerified(candidate, result, input);
            try (var prepared = entry.prepareImageDataSave(candidate)) {
                try (var verified = PathIO.<BufferedImage>readImageData(prepared.stagedPath())) {
                    if (!SourceSignature.compute(verified, input.regionIds()).equals(expected))
                        throw new IllegalStateException("Prepared project data did not match the result.");
                }
                return fx.call(() -> {
                    if (!saved.version().matches(file) || !prepared.sourceUnchanged())
                        return "The saved source changed while publishing. Results retained for review.";
                    if (!open.equals(editor.openImages(input)) || snapshot != null && !snapshot.unchanged())
                        return "The image was opened, closed or edited while preparing its results. Your work was kept; requeue the latest outline.";
                    prepared.commit();
                    if (snapshot != null) {
                        ResultMerger.applyVerified(snapshot.source(), candidate, input);
                        boolean complete = !ImageDataSnapshot.hasUncommittedObjects(snapshot.source());
                        snapshot.source().setLastSavedPath(prepared.targetPath().toString(), complete);
                        if (!complete) snapshot.source().setChanged(true);
                    }
                    savedProject.syncChanges();
                    return null;
                });
            }
        }
    }
    private static boolean sameProject(Project<?> project, Path path) {
        return project != null && project.getPath().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
    }
}
