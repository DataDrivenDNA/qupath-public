/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */
package qupath.lib.gui.measure.ui;

import javafx.application.Platform;
import javafx.scene.control.TableView;
import org.controlsfx.control.action.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectFilter;
import qupath.lib.objects.PathObjects;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TestSummaryMeasurementTableExport {

    @TempDir Path temporary;

    @Test
    void saveWritesMeasurementsOffTheUiThreadAndRestoresSelectionBinding() throws Exception {
        exerciseExport(false);
    }

    @Test
    void copyBuildsSelectedRowsOffTheUiThreadAndStaysDisabledAfterDeselection() throws Exception {
        exerciseExport(true);
    }

    private void exerciseExport(boolean copying) throws Exception {
        var server = new WrappedBufferedImageServer("export-test",
                new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_GRAY));
        var data = new ImageData<>(server);
        var first = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 8, 8));
        var second = PathObjects.createAnnotationObject(ROIs.createRectangleROI(16, 16, 8, 8));
        first.getMeasurementList().put("Signal", 123.456);
        second.getMeasurementList().put("Signal", 78.9);
        data.getHierarchy().addObjects(List.of(first, second));
        data.setChanged(false);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        var workerOnFx = new AtomicBoolean(true);
        var callbackOnFx = new AtomicBoolean(false);
        var exportExecutions = new AtomicInteger();
        var output = temporary.resolve("measurements.tsv");
        Fixture fixture = null;
        try {
            fixture = FxTestTools.call(() -> {
                var summary = new SummaryMeasurementTable(data, PathObjectFilter.ANNOTATIONS);
                var table = new TableView<PathObject>();
                table.getItems().setAll(first, second);
                field("table").set(summary, table);
                method("initActions").invoke(summary);
                var model = (ObservableMeasurementTableData) field("model").get(summary);
                model.setImageData(data, List.of(first, second));
                if (copying)
                    table.getSelectionModel().select(first);
                return new Fixture(summary, table, model,
                        (Action) field("actionCopy").get(summary), (Action) field("actionSave").get(summary));
            });
            var f = fixture;
            var snapshot = FxTestTools.call(() -> f.model.snapshotForExport(
                    copying ? List.of(first) : List.of(first, second)));
            var action = copying ? f.copy : f.save;
            var originalText = FxTestTools.call(action::getText);
            FxTestTools.call(() -> {
                // Exercise the real actions and shared post-file-chooser export path.
                // No windows are shown, and the user's clipboard is never touched.
                runExport(f.summary, action, () -> {
                    exportExecutions.incrementAndGet();
                    workerOnFx.set(Platform.isFxApplicationThread());
                    started.countDown();
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test export was not released");
                    if (copying)
                        return String.join("\n", snapshot.getRowStrings("\t", -1, name -> true));
                    if (!SummaryMeasurementTable.saveTableModel(snapshot, output.toFile(), List.of()))
                        throw new IllegalStateException("Test export could not write its file");
                    return Files.readString(output);
                }, value -> {
                    result.set(value);
                    callbackOnFx.set(Platform.isFxApplicationThread());
                    completed.countDown();
                });
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            FxTestTools.call(() -> {
                assertTrue(f.copy.isDisabled());
                assertTrue(f.save.isDisabled());
                if (copying)
                    f.table.getSelectionModel().clearSelection();
                else
                    f.table.getSelectionModel().select(first);
                assertTrue(f.copy.isDisabled(), "Changing selection must not enable Copy during export");
                runExport(f.summary, action, () -> {
                    exportExecutions.incrementAndGet();
                    return "duplicate";
                }, value -> {});
                return null;
            });
            assertFalse(workerOnFx.get());
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(callbackOnFx.get());
            assertEquals(1, exportExecutions.get(), "An in-flight export must reject duplicate requests");
            assertTrue(result.get().contains("Signal"));
            // Copy retains its existing one-decimal formatting; file exports retain full precision.
            assertTrue(result.get().contains(copying ? "123.5" : "123.456"), result.get());
            assertEquals(copying ? 2 : 3, result.get().lines().count());
            if (!copying)
                assertTrue(result.get().contains("78.9"));
            FxTestTools.call(() -> {
                assertEquals(originalText, action.getText());
                assertFalse(f.save.isDisabled());
                assertEquals(copying, f.copy.isDisabled());
                f.table.getSelectionModel().select(first);
                assertFalse(f.copy.isDisabled());
                f.table.getSelectionModel().clearSelection();
                assertTrue(f.copy.isDisabled());
                return null;
            });
            assertEquals(2, data.getHierarchy().nObjects());
            assertEquals(123.456, first.getMeasurementList().get("Signal"));
            assertFalse(data.isChanged());
        } finally {
            release.countDown();
            if (fixture != null) {
                var model = fixture.model;
                FxTestTools.call(() -> { model.setImageData(null, List.of()); return null; });
            }
            server.close();
        }
    }

    private static void runExport(SummaryMeasurementTable summary, Action action,
                                  Callable<String> work, Consumer<String> completed) throws Exception {
        var method = SummaryMeasurementTable.class.getDeclaredMethod("runTableExport",
                Action.class, String.class, Callable.class, Consumer.class);
        method.setAccessible(true);
        try {
            method.invoke(summary, action, "Exporting…", work, completed);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception)
                throw exception;
            throw e;
        }
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        var field = SummaryMeasurementTable.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static java.lang.reflect.Method method(String name) throws Exception {
        var method = SummaryMeasurementTable.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private record Fixture(SummaryMeasurementTable summary, TableView<PathObject> table,
                           ObservableMeasurementTableData model, Action copy, Action save) {}
}
