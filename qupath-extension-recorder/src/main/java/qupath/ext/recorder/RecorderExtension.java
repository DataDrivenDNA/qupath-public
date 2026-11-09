/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.LoggerFactory;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Persistent extension entry point, discovered by QuPath's native extension loader. */
public final class RecorderExtension implements QuPathExtension {
    private static RecorderExtension installed;
    private QuPathGUI gui;
    private RecordingSession session;
    private Path lastDirectory;
    private MenuItem status;
    private MenuItem start;
    private MenuItem stop;

    @Override public void installExtension(QuPathGUI gui) {
        if (installed != null) return;
        installed = this; this.gui = gui;
        var menu = new Menu("Session recorder");
        status = new MenuItem("Stopped"); status.setDisable(true);
        start = new MenuItem("Start recording"); start.setOnAction(event -> start());
        stop = new MenuItem("Stop recording"); stop.setOnAction(event -> stop()); stop.setDisable(true);
        var capture = new MenuItem("Capture checkpoint");
        capture.setOnAction(event -> { if (session != null) session.checkpoint(); });
        var folder = new MenuItem("Open recording folder");
        folder.setOnAction(event -> {
            if (lastDirectory == null) return;
            try { java.awt.Desktop.getDesktop().open(lastDirectory.toFile()); }
            catch (Exception e) { LoggerFactory.getLogger(getClass()).error("Cannot open recording folder", e); }
        });
        var autoStart = PathPrefs.createPersistentPreference("sessionRecorderAutoStart", true);
        var automatic = new CheckMenuItem("Record automatically on launch");
        automatic.selectedProperty().bindBidirectional(autoStart);
        menu.getItems().setAll(status, start, stop, capture, folder, automatic);
        gui.getMenu("Extensions", false).getItems().add(menu);
        if (autoStart.get()) Platform.runLater(this::start);
    }

    private void start() {
        if (session != null) return;
        try {
            Path base = UserDirectoryManager.getInstance().userDirectoryProperty().get();
            if (base == null) base = PathPrefs.getDefaultQuPathUserDirectory();
            String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + "-" + UUID.randomUUID().toString().substring(0,8);
            lastDirectory = base.resolve("recordings").resolve(name);
            Path startingDirectory = lastDirectory;
            session = new RecordingSession(gui, lastDirectory, failure -> Platform.runLater(() -> {
                if (session == null || !session.directory().equals(startingDirectory)) return;
                stop(); status.setText("Recording failed: " + failure.getClass().getSimpleName());
                LoggerFactory.getLogger(getClass()).error("Session recorder stopped after a storage failure", failure);
            }));
            status.setText("Recording: " + name); start.setDisable(true); stop.setDisable(false);
            LoggerFactory.getLogger(getClass()).info("Session recording started: {}", lastDirectory);
        } catch (Exception e) {
            session = null; status.setText("Recording failed: " + e.getClass().getSimpleName());
            LoggerFactory.getLogger(getClass()).error("Cannot start session recording", e);
        }
    }

    private void stop() {
        if (session != null) { session.close(); session = null; }
        status.setText("Stopped"); start.setDisable(false); stop.setDisable(true);
    }

    /** Read-only status for diagnostics and automation clients. Call on the JavaFX thread. */
    public static java.util.Map<String, Object> recordingStatus() {
        if (installed == null) return java.util.Map.of("installed", false);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("installed", true); result.put("recording", installed.session != null && installed.session.healthy());
        result.put("directory", installed.lastDirectory == null ? null : installed.lastDirectory.toString());
        result.put("queued", installed.session == null ? 0 : installed.session.backlog());
        result.put("dropped", installed.session == null ? 0 : installed.session.droppedEvents());
        result.put("assetErrors", installed.session == null ? 0 : installed.session.assetErrors());
        result.put("observerErrors", installed.session == null ? 0 : installed.session.observerErrors());
        return result;
    }

    @Override public String getName() { return "Session recorder"; }
    @Override public String getDescription() { return "Local action, workflow, annotation and screenshot recording for automation."; }
    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }
    @Override public Version getVersion() { return Version.parse("1.1.0"); }
}
