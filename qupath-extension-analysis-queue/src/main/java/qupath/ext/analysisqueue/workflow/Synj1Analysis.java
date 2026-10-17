/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.lib.algorithms.IntensityFeaturesPlugin;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.TaskRunnerUtils;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.opencv.ml.pixel.PixelClassificationMeasurementManager;
import qupath.opencv.ml.pixel.PixelClassifierTools;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Consumer;

/** Native analysis on exclusively owned ImageData. Never reads GUI selection or global QP state. */
public final class Synj1Analysis {
    public static final String CLASSIFIER = "threshhold 220 very high synj1";
    public static final String PREFIX = "Tissue/empty";
    public static final double DAB_PIXEL_SIZE = 0.5;
    public static final double PERCENT_TOLERANCE = 4.0 * Math.ulp(100.0f);
    public static final List<String> CLASS_KEYS = List.of(PREFIX + ": tissue %", PREFIX + ": tissue area µm^2",
            PREFIX + ": empty %", PREFIX + ": empty area µm^2");

    public static Map<String, Object> intensityParameters() {
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("pixelSizeMicrons", DAB_PIXEL_SIZE); parameters.put("region", "ROI"); parameters.put("tileSizeMicrons", 25.0);
        for (var name : List.of("colorOD", "colorStain1", "colorStain3", "colorRed", "colorGreen", "colorBlue",
                "colorHue", "colorSaturation", "colorBrightness", "doMinMax", "doMedian", "doHaralick")) parameters.put(name, false);
        parameters.put("colorStain2", true); parameters.put("doMean", true); parameters.put("doStdDev", true);
        parameters.put("haralickDistance", 1); parameters.put("haralickBins", 32);
        return parameters;
    }
    public static String dabKey(String feature) { return String.format("ROI: %.2f µm per pixel: DAB: %s", DAB_PIXEL_SIZE, feature); }

    public int run(ImageData<BufferedImage> data, List<String> ids, PixelClassifier classifier, Consumer<String> progress) throws Exception {
        if (data.getImageType() == ImageData.ImageType.UNSET) data.setImageType(ImageData.ImageType.BRIGHTFIELD_H_DAB);
        require(data.getImageType() == ImageData.ImageType.BRIGHTFIELD_H_DAB && data.getServer().isRGB(), "A brightfield H-DAB RGB image is required.");
        require(data.getColorDeconvolutionStains() != null && data.getColorDeconvolutionStains().isH_DAB(), "H-DAB stains are required.");
        require(data.getServer().getPixelCalibration().hasPixelSizeMicrons(), "The image needs a pixel calibration in micrometres.");
        var labels = new HashSet<>(classifier.getMetadata().getClassificationLabels().values().stream().map(Object::toString).toList());
        require(labels.equals(Set.of("tissue", "empty")), "The classifier must label tissue and empty.");
        var hierarchy = data.getHierarchy();
        var roots = SourceSignature.roots(data, ids);
        var originalLocks = new HashMap<UUID, Boolean>();
        roots.forEach(p -> originalLocks.put(p.getID(), p.isLocked()));
        progress.accept("Creating tissue and empty regions");
        // Already reviewed classification children remain usable; don't create duplicate regions.
        var bare = roots.stream().filter(p -> p.getChildObjects().stream().noneMatch(Synj1Analysis::classified)).toList();
        try (var server = PixelClassifierTools.createPixelClassificationServer(data, classifier)) {
            if (!bare.isEmpty()) {
                hierarchy.getSelectionModel().setSelectedObjects(bare, bare.getFirst());
                require(PixelClassifierTools.createAnnotationsFromPixelClassifier(hierarchy, server, 0.0, 0.0), "Tissue segmentation did not complete.");
                roots.forEach(parent -> parent.getChildObjects().stream().filter(Synj1Analysis::classified).forEach(child -> {
                    if (child.getName() == null) child.setName(Objects.toString(parent.getName(), "Region") + " — " + child.getPathClass());
                }));
            }
            var annotations = roots.stream().flatMap(root -> SourceSignature.branch(root).stream())
                    .filter(p -> p.isAnnotation() && p.getROI().isArea() && p.getROI().getArea() > 0).distinct().toList();
            var tissues = annotations.stream().filter(p -> !roots.contains(p) && "tissue".equals(Objects.toString(p.getPathClass(), ""))).toList();
            progress.accept("Measuring DAB at 0.5 µm");
            if (!tissues.isEmpty()) {
                hierarchy.getSelectionModel().setSelectedObjects(tissues, tissues.getFirst());
                var runner = TaskRunnerUtils.getDefaultInstance().createHeadlessTaskRunner(2);
                require(new IntensityFeaturesPlugin().runPlugin(runner, data, JsonFiles.GSON.toJson(intensityParameters())) && !runner.isCancelled(),
                        "DAB measurement did not complete.");
                for (var tissue : tissues) for (var feature : List.of("Mean", "Std.dev."))
                    require(Double.isFinite(tissue.getMeasurementList().get(dabKey(feature))), "Invalid DAB " + feature);
            }
            progress.accept("Measuring tissue and empty areas");
            var manager = new PixelClassificationMeasurementManager(server) {
                @Override protected int calculatePreferredParallelism() { return 4; }
            };
            require(manager.addMeasurements(annotations, PREFIX), "Tissue/empty measurement did not complete.");
            for (var annotation : annotations) validateClassMeasurements(annotation);
            roots.forEach(p -> p.setLocked(originalLocks.get(p.getID())));
            hierarchy.getSelectionModel().clearSelection();
            data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Queued SYNJ1 analysis",
                    Map.of("classifier", CLASSIFIER, "DAB pixel size µm", DAB_PIXEL_SIZE, "regions", roots.size()),
                    "// Completed from saved outline snapshot by the Analysis queue extension."));
            return tissues.size();
        }
    }
    private static boolean classified(PathObject object) {
        return object.isAnnotation() && Set.of("tissue", "empty").contains(Objects.toString(object.getPathClass(), ""));
    }
    public static void validateClassMeasurements(PathObject annotation) {
        var ml = annotation.getMeasurementList();
        for (var key : CLASS_KEYS) require(Double.isFinite(ml.get(key)) && ml.get(key) >= 0, "Invalid classifier value: " + key);
        double tissue = ml.get(CLASS_KEYS.get(0)), tissueArea = ml.get(CLASS_KEYS.get(1));
        double empty = ml.get(CLASS_KEYS.get(2)), emptyArea = ml.get(CLASS_KEYS.get(3));
        require(Math.abs(tissue + empty - 100) <= PERCENT_TOLERANCE, "Class percentages disagree beyond native precision.");
        require(tissueArea + emptyArea > 0 && Math.abs(tissue - 100 * tissueArea / (tissueArea + emptyArea)) <= PERCENT_TOLERANCE,
                "Class areas and percentages disagree beyond native precision.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
