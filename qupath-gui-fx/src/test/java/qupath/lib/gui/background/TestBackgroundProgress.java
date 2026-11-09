/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import javafx.application.Platform;
import javafx.scene.control.ProgressBar;
import org.junit.jupiter.api.Test;
import qupath.lib.gui.tools.FxTestTools;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TestBackgroundProgress {
    @Test void overlappingOperationsReportFromWorkersAndDisappearAfterSuccessOrFailure() throws Exception {
        var pane = FxTestTools.call(() -> {
            var result = new BackgroundProgressPane();
            new javafx.scene.Scene(result, 600, 150);
            result.applyCss();
            return result;
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var activity = new AtomicReference<BackgroundProgress.Operation>();
        var reportedOnFx = new AtomicBoolean();
        var failing = TestBackgroundCommands.submit(() -> {
            try (var operation = BackgroundProgress.start("Create regions — slide A", "Waiting")) {
                activity.set(operation);
                operation.phaseProperty().addListener((v, old, value) -> reportedOnFx.set(Platform.isFxApplicationThread()));
                return BackgroundTasks.analysis(() -> {
                    operation.phase("Creating classified annotations");
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    throw new IOException("native failure");
                });
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            FxTestTools.call(() -> {
                assertTrue(pane.isVisible());
                assertTrue(pane.isManaged());
                assertEquals("Creating classified annotations", activity.get().phaseProperty().get());
                assertTrue(reportedOnFx.get());
                assertEquals(1, pane.lookupAll("#background-progress-bar").size());
                try (var save = BackgroundProgress.start("Save — slide B", "Writing annotations")) {
                    assertEquals(2, BackgroundProgress.activeOperations().size());
                    assertEquals(2, pane.lookupAll("#background-progress-bar").size());
                    pane.lookupAll("#background-progress-bar").forEach(node -> assertTrue(((ProgressBar)node).isIndeterminate()));
                    assertEquals(42, BackgroundTasks.io(() -> 42));
                }
                assertEquals(1, BackgroundProgress.activeOperations().size());
                return null;
            });
        } finally { release.countDown(); }
        assertEquals("native failure", assertThrows(ExecutionException.class,
                () -> failing.get(10, TimeUnit.SECONDS)).getCause().getMessage());
        FxTestTools.call(() -> {
            assertTrue(BackgroundProgress.activeOperations().isEmpty());
            assertFalse(pane.isVisible());
            assertFalse(pane.isManaged());
            return null;
        });
    }

    @Test void lateWorkerReportsCannotResurrectAClosedOperation() throws Exception {
        FxTestTools.start();
        var operation = BackgroundProgress.start("Queue", "Preparing");
        FxTestTools.call(() -> {
            assertTrue(BackgroundProgress.activeOperations().contains(operation));
            return null;
        });
        operation.phase("Processing");
        operation.close();
        operation.phase("Late update");
        operation.close();
        FxTestTools.call(() -> {
            assertFalse(BackgroundProgress.activeOperations().contains(operation));
            assertNotEquals("Late update", operation.phaseProperty().get());
            return null;
        });
    }
}
