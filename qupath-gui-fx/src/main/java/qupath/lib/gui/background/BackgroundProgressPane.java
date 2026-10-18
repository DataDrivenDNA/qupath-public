/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact, nonmodal progress area; it takes no space or timer while idle. */
public final class BackgroundProgressPane extends ScrollPane {
    private final VBox rows = new VBox(6);
    private final Map<BackgroundProgress.Operation, Row> displayed = new LinkedHashMap<>();
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e ->
            displayed.values().forEach(Row::updateElapsed)));

    public BackgroundProgressPane() {
        setId("background-progress");
        rows.setPadding(new Insets(7, 12, 7, 12));
        setContent(rows);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setFocusTraversable(false);
        setMinHeight(0);
        timer.setCycleCount(Timeline.INDEFINITE);
        BackgroundProgress.activeOperations().addListener((ListChangeListener<BackgroundProgress.Operation>) change -> refresh());
        refresh();
    }

    private void refresh() {
        var active = BackgroundProgress.activeOperations();
        displayed.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            entry.getValue().dispose();
            rows.getChildren().remove(entry.getValue());
            return true;
        });
        for (var operation : active) {
            if (!displayed.containsKey(operation)) {
                var row = new Row(operation);
                displayed.put(operation, row);
                rows.getChildren().add(row);
            }
        }
        boolean busy = !displayed.isEmpty();
        setVisible(busy);
        setManaged(busy);
        setPrefHeight(Math.min(158, 16 + displayed.size() * 46));
        if (busy) timer.play(); else timer.stop();
    }

    private static final class Row extends HBox {
        private final BackgroundProgress.Operation operation;
        private final Label phase = new Label();
        private final Label elapsed = new Label();
        private final Tooltip detail = new Tooltip();
        Row(BackgroundProgress.Operation operation) {
            super(12);
            this.operation = operation;
            setAlignment(Pos.CENTER_LEFT);
            var title = new Label(operation.title());
            title.setTooltip(new Tooltip(operation.title()));
            title.setMaxWidth(Double.MAX_VALUE);
            phase.textProperty().bind(operation.phaseProperty());
            detail.textProperty().bind(operation.phaseProperty());
            phase.setTooltip(detail);
            phase.setStyle("-fx-opacity: 0.8; -fx-font-size: 11px;");
            phase.setMaxWidth(Double.MAX_VALUE);
            var labels = new VBox(2, title, phase);
            labels.setMinWidth(0);
            HBox.setHgrow(labels, Priority.ALWAYS);
            var bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            bar.setId("background-progress-bar");
            bar.setPrefWidth(140);
            bar.setMinWidth(80);
            bar.setAccessibleText(operation.title());
            bar.accessibleHelpProperty().bind(operation.phaseProperty());
            elapsed.setMinWidth(52);
            elapsed.setAlignment(Pos.CENTER_RIGHT);
            elapsed.setTooltip(new Tooltip("Elapsed time; the native operation does not provide a completion percentage."));
            getChildren().addAll(labels, bar, elapsed);
            updateElapsed();
        }
        void updateElapsed() {
            long seconds = operation.elapsedSeconds();
            elapsed.setText(seconds < 3600 ? "%d:%02d".formatted(seconds / 60, seconds % 60)
                    : "%d:%02d:%02d".formatted(seconds / 3600, seconds / 60 % 60, seconds % 60));
        }
        void dispose() {
            phase.textProperty().unbind();
            detail.textProperty().unbind();
            getChildren().get(1).accessibleHelpProperty().unbind();
        }
    }
}
