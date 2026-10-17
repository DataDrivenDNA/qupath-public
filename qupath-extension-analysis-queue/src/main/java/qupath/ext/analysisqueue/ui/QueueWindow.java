/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import qupath.ext.analysisqueue.qupath.QueueController;
import qupath.ext.analysisqueue.queue.Job;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Function;

/** A non-modal monitor. Hiding it never pauses or cancels background work. */
public final class QueueWindow {
    private final QueueController controller;
    private final Stage stage = new Stage();
    private final BorderPane content = new BorderPane();
    private final TableView<Job> table = new TableView<>();
    private final Label summary = new Label();
    private final Label detail = new Label("Select a job to see its details.");
    private final Button pause = new Button();
    private final Button retry = new Button("Retry failed job");
    private final Button remove = new Button("Remove waiting job");
    private final Button results = new Button("Open results folder");
    private final Timeline refresh;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public QueueWindow(Stage owner, QueueController controller) {
        this.controller = controller;
        stage.initOwner(owner); stage.setTitle("Analysis queue");
        content.setPadding(new Insets(16));
        var title = new Label("Analysis queue"); title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        var recipe = new Label("SYNJ1  •  Red threshold 220  •  DAB at 0.5 µm");
        var help = new Label("Outline and select your outer region(s), then add the image. You can keep annotating other slides.");
        help.setWrapText(true);
        var add = new Button("Add current image to queue"); add.setId("analysis-queue-add");
        add.setOnAction(e -> run(() -> controller.enqueueCurrent()));
        pause.setId("analysis-queue-pause"); pause.setOnAction(e -> { controller.pause(!controller.paused()); refresh(); });
        var actions = new HBox(8, add, pause); actions.setPadding(new Insets(8, 0, 8, 0));
        var header = new VBox(6, title, recipe, help, actions, summary); header.setPadding(new Insets(0, 0, 12, 0));
        content.setTop(header);
        table.setId("analysis-queue-jobs"); table.setItems(FXCollections.observableArrayList());
        table.getColumns().add(column("Image", Job::imageName, 260));
        table.getColumns().add(column("Status", j -> j.state().toString(), 110));
        table.getColumns().add(column("Step", Job::phase, 260));
        table.getColumns().add(column("Added", j -> time(j.createdAt()), 85));
        table.getColumns().add(column("Finished", j -> time(j.finishedAt()), 85));
        table.setPlaceholder(new Label("No images queued yet. Draw an outer region and click Add current image to queue."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((v, old, value) -> updateDetail());
        content.setCenter(table);
        detail.setWrapText(true); detail.setMinHeight(40);
        retry.setOnAction(e -> run(() -> { controller.retry(table.getSelectionModel().getSelectedItem()); return null; }));
        remove.setOnAction(e -> run(() -> { controller.removeWaiting(table.getSelectionModel().getSelectedItem()); return null; }));
        results.setOnAction(e -> run(() -> { java.awt.Desktop.getDesktop().open(controller.results(table.getSelectionModel().getSelectedItem()).toFile()); return null; }));
        var footer = new VBox(8, detail, new HBox(8, results, retry, remove)); footer.setPadding(new Insets(12, 0, 0, 0));
        content.setBottom(footer);
        var scene = new Scene(content, 960, 560);
        if (owner.getScene() != null) scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        stage.setScene(scene); stage.setMinWidth(720); stage.setMinHeight(420);
        refresh = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh())); refresh.setCycleCount(Timeline.INDEFINITE);
        stage.setOnShown(e -> { refresh(); refresh.play(); }); stage.setOnHidden(e -> refresh.stop());
        refresh();
    }
    private TableColumn<Job, String> column(String name, Function<Job, String> value, int width) {
        var column = new TableColumn<Job, String>(name); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue()))); return column;
    }
    private static String time(String value) { return value == null ? "" : TIME.format(Instant.parse(value)); }
    public void refresh() {
        var selected = table.getSelectionModel().getSelectedItem();
        var jobs = controller.jobs();
        if (!table.getItems().equals(jobs)) {
            table.getItems().setAll(jobs);
            if (selected != null) jobs.stream().filter(j -> j.id().equals(selected.id())).findFirst().ifPresent(table.getSelectionModel()::select);
        }
        long waiting = jobs.stream().filter(j -> j.state() == Job.State.WAITING).count();
        long running = jobs.stream().filter(j -> j.state() == Job.State.PROCESSING).count();
        long done = jobs.stream().filter(j -> j.state() == Job.State.COMPLETED).count();
        summary.setText(controller.error().isBlank() ? waiting + " waiting   •   " + running + " processing   •   " + done + " completed" : controller.error());
        pause.setText(controller.paused() ? "Resume queue" : "Pause after current image");
        updateDetail();
    }
    private void updateDetail() {
        var job = table.getSelectionModel().getSelectedItem();
        retry.setDisable(job == null || (job.state() != Job.State.FAILED && job.state() != Job.State.INTERRUPTED));
        remove.setDisable(job == null || job.state() != Job.State.WAITING); results.setDisable(job == null);
        detail.setText(job == null ? "Closing this window keeps the queue running." :
                job.imageName() + " — " + job.phase() + (Objects.toString(job.message(), "").isBlank() ? "" : "\n" + job.message()));
    }
    private void run(java.util.concurrent.Callable<?> operation) {
        try { operation.call(); refresh(); }
        catch (Exception error) {
            var alert = new Alert(Alert.AlertType.ERROR, Objects.toString(error.getMessage(), error.toString()), ButtonType.OK);
            alert.initOwner(stage); alert.setHeaderText("Could not update the analysis queue"); alert.show();
        }
    }
    public BorderPane content() { return content; }
    public void show() { refresh(); stage.show(); }
}
