/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import java.awt.image.BufferedImage;
import java.util.*;

/** Replaces only checked result branches; unrelated in-memory annotations survive. */
public final class ResultMerger {
    private ResultMerger() {}
    public static void merge(ImageData<BufferedImage> target, ImageData<BufferedImage> result, AnalysisInput input) {
        if (!SourceSignature.matches(target, input))
            throw new IllegalStateException("Queued regions or image settings have changed.");
        applyVerified(target, result, input);
    }
    /** Apply after a worker fingerprint check and an FX snapshot-stamp check. */
    public static void applyVerified(ImageData<BufferedImage> target, ImageData<BufferedImage> result, AnalysisInput input) {
        var destinations = SourceSignature.roots(target, input.regionIds());
        var sources = SourceSignature.roots(result, input.regionIds());
        var selection = target.getHierarchy().getSelectionModel();
        var unfinished = new HashMap<UUID, PathObject>();
        selection.getSelectedObjects().stream().filter(p -> !p.isRootObject() && p.getParent() == null)
                .forEach(p -> unfinished.put(p.getID(), p));
        var selectedIds = selection.getSelectedObjects().stream().map(PathObject::getID).toList();
        var primaryId = selection.getSelectedObject() == null ? null : selection.getSelectedObject().getID();
        for (int i = 0; i < destinations.size(); i++) {
            var destination = destinations.get(i); var source = sources.get(i);
            destination.getMeasurementList().putAll(source.getMeasurementList()); destination.getMeasurementList().close();
            var children = new ArrayList<>(source.getChildObjects());
            destination.clearChildObjects(); destination.addChildObjects(children);
        }
        if (target.getImageType() == ImageData.ImageType.UNSET) {
            target.setImageType(result.getImageType()); target.setColorDeconvolutionStains(result.getColorDeconvolutionStains());
        }
        target.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Analysis queue results",
                Map.of("DAB pixel size µm", Synj1Analysis.DAB_PIXEL_SIZE, "classifier SHA-256", input.classifierSha256(), "regions", input.regionIds().size()),
                "// Native SYNJ1 results imported after checking the queued outline snapshot."));
        target.getHierarchy().fireHierarchyChangedEvent(ResultMerger.class);
        var objects = new HashMap<UUID, PathObject>();
        SourceSignature.branch(target.getHierarchy().getRootObject()).forEach(p -> objects.put(p.getID(), p));
        unfinished.forEach(objects::putIfAbsent);
        var selected = selectedIds.stream().map(objects::get).filter(Objects::nonNull).toList();
        selection.setSelectedObjects(selected, objects.get(primaryId));
        target.setChanged(true);
    }
}
