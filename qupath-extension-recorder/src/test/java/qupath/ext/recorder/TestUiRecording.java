/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TestUiRecording {
    @TempDir Path temporary;
    @BeforeAll static void startFx() {
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException ignored) {}
    }
    private static void fx(Runnable action) throws Exception {
        var completed = new CompletableFuture<Void>();
        Platform.runLater(() -> { try { action.run(); completed.complete(null); } catch (Throwable e) { completed.completeExceptionally(e); } });
        completed.get(10, TimeUnit.SECONDS);
    }

    @Test void capturesRealActionsAndParametersThenDetachesWithoutRecordingPasswords() throws Exception {
        var writer = new RecordWriter(temporary, error -> fail(error));
        fx(() -> {
            var input = new TextField("0.5"); input.setId("pixelSizeMicrons");
            var secret = new PasswordField(); secret.setText("must-never-appear");
            var button = new Button("Run analysis");
            var scene = new Scene(new VBox(input, secret, button));
            var recorder = new UiRecording(writer, () -> {});
            recorder.observeScene(scene);
            var item = new MenuItem("Unlock");
            var menu = new Menu("Annotations", null, item);
            recorder.observeMenus(List.of(menu));
            item.fire(); button.fire(); input.fireEvent(new ActionEvent());
            recorder.releaseScene(scene);
            input.setText("after-release"); button.fire();
            recorder.close(); item.setText("after-stop"); item.fire();
        });
        writer.close(); assertTrue(writer.awaitClosed(5000));
        var text = Files.readString(temporary.resolve("events.jsonl"));
        assertTrue(text.contains("pixelSizeMicrons")); assertTrue(text.contains("0.5"));
        assertTrue(Files.readAllLines(temporary.resolve("events.jsonl")).stream().map(com.google.gson.JsonParser::parseString)
                .map(element -> element.getAsJsonObject()).anyMatch(record -> record.get("type").getAsString().equals("ui.menu") &&
                        record.getAsJsonObject("data").get("path").getAsString().equals("Annotations > Unlock")));
        assertTrue(text.contains("Run analysis"));
        assertFalse(text.contains("must-never-appear")); assertFalse(text.contains("after-release")); assertFalse(text.contains("after-stop"));
    }
}
