/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.io.WKBReader;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.roi.ROIs;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import static org.junit.jupiter.api.Assertions.*;

class TestImageRecording {
    @TempDir Path temporary;

    @Test void geometryCacheNeverHashesComplexRoisOnTheCallerThread() throws Exception {
        var delegate = ROIs.createRectangleROI(1, 2, 8, 9);
        var roi = (qupath.lib.roi.interfaces.ROI)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{qupath.lib.roi.interfaces.ROI.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hashCode") || method.getName().equals("equals"))
                        throw new AssertionError("Recording must use ROI identity, not geometry hashing");
                    return method.invoke(delegate, args);
                });
        try (var server = new WrappedBufferedImageServer("identity", new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB))) {
            var data = new ImageData<>(server); data.getHierarchy().addObject(PathObjects.createAnnotationObject(roi));
            var writer = new RecordWriter(temporary, error -> fail(error));
            try (var recording = new ImageRecording(data, writer, "image-1", () -> {})) { recording.snapshotAnnotations(); }
            writer.close(); assertTrue(writer.awaitClosed(5000));
            assertEquals(0, writer.assetErrors()); assertEquals(0, writer.observerErrors());
            try (var files = Files.list(temporary.resolve("geometry"))) { assertEquals(1, files.count()); }
        }
    }

    @Test void recordsGeometryEditsWorkflowSelectionAndRemovalWithoutChangingData() throws Exception {
        try (var server = new WrappedBufferedImageServer("recorder-test", new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB))) {
            var data = new ImageData<>(server);
            var annotation = (qupath.lib.objects.PathAnnotationObject)PathObjects.createAnnotationObject(ROIs.createRectangleROI(1, 2, 8, 9));
            data.getHierarchy().addObject(annotation); data.setChanged(false);
            var writer = new RecordWriter(temporary, error -> fail(error));
            var recording = new ImageRecording(data, writer, "image-1", () -> {});
            assertFalse(data.isChanged(), "Attaching a recorder must not dirty analysis data");
            annotation.setROI(ROIs.createRectangleROI(4, 5, 10, 12));
            annotation.setPathClass(PathClass.fromString("tissue"));
            annotation.getMeasurementList().put("DAB", 0.5);
            PathObjectTools.lockObjects(data.getHierarchy(), List.of(annotation));
            data.getHierarchy().getSelectionModel().setSelectedObject(annotation);
            data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("Measure", Map.of("pixelSizeMicrons", 0.5), "println 'measured'"));
            data.getHierarchy().removeObject(annotation, false);
            recording.close();
            int countBefore = data.getHistoryWorkflow().size();
            data.getHistoryWorkflow().addStep(new DefaultScriptableWorkflowStep("After stop", "println 'not recorded'"));
            assertEquals(countBefore+1, data.getHistoryWorkflow().size());
            writer.close(); assertTrue(writer.awaitClosed(5000));
            var records = Files.readAllLines(temporary.resolve("events.jsonl")).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
            assertTrue(records.stream().anyMatch(r -> r.get("type").getAsString().equals("selection")));
            assertTrue(records.stream().anyMatch(r -> r.get("type").getAsString().equals("annotation.removed")));
            var text = Files.readString(temporary.resolve("events.jsonl"));
            assertFalse(text.contains("not recorded"));
            assertTrue(Files.readString(temporary.resolve("workflows/image-1.groovy")).contains("println 'measured'"));
            try (var input = new GZIPInputStream(Files.newInputStream(temporary.resolve("geometry/image-1-2.wkb.gz")))) {
                assertTrue(new WKBReader().read(input.readAllBytes()).equalsExact(annotation.getROI().getGeometry()));
            }
        }
    }
}
