package qupath.ext.analysisqueue.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.qupath.*;
import qupath.ext.analysisqueue.queue.Job;
import qupath.lib.io.PathIO;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.opencv.ml.pixel.PixelClassifiers;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeWorkflowTest {
    @TempDir Path root;
    @Test void completeCaptureNativeSegmentationDabSaveReadbackAndExport() throws Exception {
        try (var f = new NativeFixture(root.resolve("project"), 150)) {
            var job = Job.waiting("slide", "Synthetic slide"); var directory = root.resolve("job");
            new InputCapture().capture(f.project, f.data, List.of(f.region), job, directory);
            var input = JsonFiles.read(directory.resolve("input.json"), AnalysisInput.class);
            try (var snapshot = PathIO.<BufferedImage>readImageData(directory.resolve("input.qpdata"))) {
                assertTrue(SourceSignature.matches(snapshot, input), "Native stain renormalization must preserve snapshot identity");
            }
            var processor = new Synj1Processor(new NativeResultPublisher(null, FxExecutor.direct()));
            var outcome = processor.process(job, directory, phase -> {});
            assertEquals(Job.State.COMPLETED, outcome.state());
            try (var saved = f.entry.readImageData()) {
                var region = SourceSignature.roots(saved, f.ids()).getFirst();
                assertEquals(2, region.nChildObjects());
                assertEquals(75.0, region.getMeasurementList().get(Synj1Analysis.CLASS_KEYS.getFirst()), 1.0);
                assertEquals(25.0, region.getMeasurementList().get(Synj1Analysis.CLASS_KEYS.get(2)), 1.0);
                var tissue = region.getChildObjects().stream().filter(p -> "tissue".equals(p.getPathClass().toString())).findFirst().orElseThrow();
                assertTrue(Double.isFinite(tissue.getMeasurementList().get(Synj1Analysis.dabKey("Mean"))));
                assertTrue(tissue.getMeasurementList().get(Synj1Analysis.dabKey("Mean")) > 0);
                assertTrue(tissue.getMeasurementList().getNames().stream().noneMatch(n -> n.contains("5.00 µm") || n.contains("9.50 µm")));
                // Reprocessing existing classification annotations must preserve their IDs.
                var childIds = region.getChildObjects().stream().map(p -> p.getID()).toList();
                new Synj1Analysis().run(saved, f.ids(), PixelClassifiers.readClassifier(f.classifier()), phase -> {});
                assertEquals(childIds, region.getChildObjects().stream().map(p -> p.getID()).toList());
            }
            var output = Path.of(outcome.outputPath());
            String csv = Files.readString(output.resolve("measurements.csv"));
            assertTrue(csv.contains(Synj1Analysis.dabKey("Mean"))); assertFalse(csv.contains("(Live)"));
            assertEquals(4, csv.lines().count()); // Header, outer, tissue, empty.
            assertTrue(Files.exists(output.resolve("result.qpdata")));
            processor.releaseCompletedInput(job, directory);
            assertFalse(Files.exists(directory.resolve("input.qpdata")));
            assertFalse(Files.exists(output.resolve("result.qpdata")));
            assertTrue(Files.exists(Path.of(input.savedPath()))); assertTrue(Files.exists(output.resolve("measurements.csv")));
        }
    }
    @Test void allEmptyRegionIsValidAndExportsMissingDabAsBlank() throws Exception {
        try (var f = new NativeFixture(root.resolve("empty"), 0)) {
            var job = Job.waiting("blank", "Blank"); var directory = root.resolve("job");
            new InputCapture().capture(f.project, f.data, List.of(), job, directory);
            var outcome = new Synj1Processor(new NativeResultPublisher(null, FxExecutor.direct())).process(job, directory, p -> {});
            assertEquals(Job.State.COMPLETED, outcome.state()); assertTrue(outcome.message().contains("No tissue"));
            try (var saved = f.entry.readImageData()) {
                var region = SourceSignature.roots(saved, f.ids()).getFirst();
                assertEquals(100, region.getMeasurementList().get(Synj1Analysis.CLASS_KEYS.get(2)), 0.001);
                assertEquals(1, region.nChildObjects());
            }
            String csv = Files.readString(Path.of(outcome.outputPath()).resolve("measurements.csv"));
            assertTrue(csv.contains(Synj1Analysis.dabKey("Mean"))); assertFalse(csv.contains("NaN"));
            assertEquals(3, csv.lines().count());
        }
    }
    @Test void multipleOutlinesAndUnsetImageTypeUseTheSameWorkflow() throws Exception {
        try (var f = new NativeFixture(root.resolve("multiple"), 150)) {
            f.data.getHierarchy().removeObject(f.region, false);
            var first = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100, ImagePlane.getDefaultPlane()));
            var second = PathObjects.createAnnotationObject(ROIs.createRectangleROI(100, 0, 100, 100, ImagePlane.getDefaultPlane()));
            f.data.getHierarchy().addObjects(List.of(first, second));
            f.data.setImageType(ImageData.ImageType.UNSET);
            var job = Job.waiting("multiple", "Multiple"); var directory = root.resolve("job");
            new InputCapture().capture(f.project, f.data, List.of(first, second), job, directory);
            var outcome = new Synj1Processor(new NativeResultPublisher(null, FxExecutor.direct())).process(job, directory, p -> {});
            assertEquals(Job.State.COMPLETED, outcome.state());
            try (var saved = f.entry.readImageData()) {
                assertEquals(ImageData.ImageType.BRIGHTFIELD_H_DAB, saved.getImageType());
                var roots = SourceSignature.roots(saved, List.of(first.getID().toString(), second.getID().toString()));
                assertEquals(100, roots.get(0).getMeasurementList().get(Synj1Analysis.CLASS_KEYS.getFirst()), 1);
                assertEquals(50, roots.get(1).getMeasurementList().get(Synj1Analysis.CLASS_KEYS.getFirst()), 1);
                assertEquals(2, roots.stream().flatMap(p -> p.getChildObjects().stream())
                        .filter(p -> Double.isFinite(p.getMeasurementList().get(Synj1Analysis.dabKey("Mean")))).count());
            }
        }
    }
    @Test void changedSavedSourceIsRetainedForReviewWithoutOverwriting() throws Exception {
        try (var f = new NativeFixture(root.resolve("edited"), 80)) {
            var job = Job.waiting("changed", "Changed"); var directory = root.resolve("job");
            new InputCapture().capture(f.project, f.data, List.of(f.region), job, directory);
            f.region.setName("Edited after queueing"); f.entry.saveImageData(f.data);
            var before = JsonFiles.sha256(Path.of(f.data.getLastSavedPath()));
            var outcome = new Synj1Processor(new NativeResultPublisher(null, FxExecutor.direct())).process(job, directory, p -> {});
            assertEquals(Job.State.NEEDS_REVIEW, outcome.state());
            assertEquals(before, JsonFiles.sha256(Path.of(f.data.getLastSavedPath())));
            assertTrue(Files.exists(Path.of(outcome.outputPath()).resolve("result.qpdata")));
            assertTrue(Files.exists(directory.resolve("input.qpdata")));
        }
    }
    @Test void mergePreservesUnrelatedManualWorkAndRejectsChangedOrRemovedOutline() throws Exception {
        try (var f = new NativeFixture(root.resolve("merge"), 80)) {
            var job = Job.waiting("merge", "Merge"); var directory = root.resolve("job");
            new InputCapture().capture(f.project, f.data, List.of(f.region), job, directory);
            var input = JsonFiles.read(directory.resolve("input.json"), AnalysisInput.class);
            try (var result = PathIO.<BufferedImage>readImageData(directory.resolve("input.qpdata"))) {
                new Synj1Analysis().run(result, f.ids(), PixelClassifiers.readClassifier(f.classifier()), p -> {});
                var other = PathObjects.createAnnotationObject(ROIs.createRectangleROI(220, 0, 10, 10, ImagePlane.getDefaultPlane()));
                other.setName("Manual work while processing"); f.data.getHierarchy().addObject(other);
                f.data.getHierarchy().getSelectionModel().setSelectedObject(other);
                assertTrue(SourceSignature.matches(f.data, input));
                f.region.setName("Edited"); assertFalse(SourceSignature.matches(f.data, input));
                assertThrows(IllegalStateException.class, () -> ResultMerger.merge(f.data, result, input));
                f.region.setName("Outer region"); ResultMerger.merge(f.data, result, input);
                assertTrue(f.data.getHierarchy().getAnnotationObjects().contains(other));
                assertSame(other, f.data.getHierarchy().getSelectionModel().getSelectedObject());
                assertEquals(2, f.region.nChildObjects());
                f.data.getHierarchy().removeObject(f.region, false); assertFalse(SourceSignature.matches(f.data, input));
            }
        }
    }
}
