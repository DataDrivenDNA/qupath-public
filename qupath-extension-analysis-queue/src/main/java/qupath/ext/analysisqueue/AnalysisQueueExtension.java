/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue;

import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.WindowEvent;
import qupath.ext.analysisqueue.qupath.QueueController;
import qupath.ext.analysisqueue.ui.QueueWindow;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.UserDirectoryManager;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

public final class AnalysisQueueExtension implements QuPathExtension {
    private static AnalysisQueueExtension installed;
    private QueueController controller;
    private QueueWindow window;
    @Override public void installExtension(QuPathGUI gui) {
        if (installed != null) return;
        try {
            var directory = UserDirectoryManager.getInstance().userDirectoryProperty().get();
            if (directory == null) directory = PathPrefs.getDefaultQuPathUserDirectory();
            controller = new QueueController(gui, directory.resolve("analysis-queue"));
            window = new QueueWindow(gui.getStage(), controller);
            var menu = new Menu("Analysis queue");
            var add = new MenuItem("Add current image to queue");
            add.setOnAction(e -> {
                try { controller.enqueueCurrent(); window.show(); }
                catch (Exception error) {
                    var alert = new Alert(Alert.AlertType.ERROR, error.getMessage()); alert.initOwner(gui.getStage());
                    alert.setHeaderText("Could not add this image"); alert.show();
                }
            });
            var show = new MenuItem("Show analysis queue"); show.setOnAction(e -> window.show());
            menu.getItems().addAll(add, show); gui.getMenu("Extensions", false).getItems().add(menu);
            gui.getStage().addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> controller.close());
            installed = this;
        } catch (Exception error) { throw new IllegalStateException("Cannot start the analysis queue", error); }
    }
    /** App-scoped diagnostics and trusted script integration; capture calls require the FX thread. */
    public static AnalysisQueueExtension installed() { return installed; }
    public QueueController controller() { return controller; }
    public QueueWindow window() { return window; }
    @Override public String getName() { return "Analysis queue"; }
    @Override public String getDescription() { return "Outline a region, then queue native SYNJ1 segmentation, measurements, save and export."; }
    @Override public Version getVersion() { return Version.parse("1.0.0"); }
    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }
}
