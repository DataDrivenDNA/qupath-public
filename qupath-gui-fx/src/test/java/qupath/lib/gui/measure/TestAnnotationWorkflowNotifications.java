/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */
package qupath.lib.gui.measure;

import javafx.beans.property.SimpleObjectProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.gui.panes.SelectedMeasurementTableView;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.TileRequest;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjectTools;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.ROIs;
import qupath.opencv.ml.pixel.PixelClassifierTools;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestAnnotationWorkflowNotifications {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void liveTableRefreshAllowsUnlockAndClassifierCreationThenSaveAndReload(boolean createObjects) throws Exception {
        var pixels = new BufferedImage(32, 16, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 16; y++)
            for (int x = 16; x < 32; x++)
                pixels.getRaster().setSample(x, y, 0, 1);
        try (var server = new WrappedBufferedImageServer("notification-test", pixels) {
            @Override public BufferedImage getCachedTile(TileRequest tile) { return pixels; }
        }) {
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .channelType(ImageServerMetadata.ChannelType.CLASSIFICATION)
                    .classificationLabels(Map.of(0, PathClass.fromString("tissue"),
                            1, PathClass.fromString("empty")))
                    .build());
            var data = new ImageData<>(server);
            var parent = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 32, 16));
            parent.setLocked(true);
            parent.getMeasurementList().put("preserved", 123.0);
            data.getHierarchy().addObject(parent);
            data.getHierarchy().getSelectionModel().setSelectedObject(parent);
            ObservableMeasurementTableData.setPixelLayer(data, server);
            var notifications = new AtomicInteger();
            var view = FxTestTools.call(() -> {
                var tableView = new SelectedMeasurementTableView(new SimpleObjectProperty<>());
                tableView.getPane();
                tableView.changed(null, null, data);
                var table = tableView.getTable();
                int row = table.getItems().indexOf("(Live)tissue %");
                assertTrue(row >= 0);
                // Evaluate the real cell binding, registering the live measurement listener.
                table.getColumns().get(1).getCellObservableValue(row).getValue();
                data.getHierarchy().addListener(event -> notifications.incrementAndGet());
                return tableView;
            });
            try {
                FxTestTools.call(() -> {
                    if (createObjects) {
                        assertTrue(PixelClassifierTools.createAnnotationsFromPixelClassifier(
                                data.getHierarchy(), server, 0, 0));
                        assertEquals(2, parent.nChildObjects());
                        assertEquals(512.0, parent.getChildObjects().stream()
                                .mapToDouble(child -> child.getROI().getArea()).sum());
                    } else {
                        PathObjectTools.unlockObjects(data.getHierarchy(), List.of(parent));
                        assertFalse(parent.isLocked());
                        PathObjectTools.lockObjects(data.getHierarchy(), List.of(parent));
                        PathObjectTools.unlockObjects(data.getHierarchy(), List.of(parent));
                        assertFalse(parent.isLocked());
                    }
                    return null;
                });
                assertTrue(notifications.get() >= 1, "Remaining listeners must receive the update");
                assertEquals(123.0, parent.getMeasurementList().get("preserved"));
                var saved = temporaryDirectory.resolve("annotations.qpdata");
                PathIO.writeImageData(saved, data);
                var reopened = PathIO.readImageData(saved, server);
                assertEquals(data.getHierarchy().nObjects(), reopened.getHierarchy().nObjects());
                var originalObjects = data.getHierarchy().getAnnotationObjects();
                var reopenedObjects = reopened.getHierarchy().getAnnotationObjects();
                for (var original : originalObjects) {
                    var restored = reopenedObjects.stream().filter(p -> p.getID().equals(original.getID()))
                            .findFirst().orElseThrow();
                    assertEquals(original.getROI(), restored.getROI());
                    assertEquals(original.getPathClass(), restored.getPathClass());
                    assertEquals(original.isLocked(), restored.isLocked());
                    assertEquals(original.getMeasurementList().asMap(), restored.getMeasurementList().asMap());
                }
            } finally {
                FxTestTools.call(() -> { view.changed(null, data, null); return null; });
            }
        }
    }
}
