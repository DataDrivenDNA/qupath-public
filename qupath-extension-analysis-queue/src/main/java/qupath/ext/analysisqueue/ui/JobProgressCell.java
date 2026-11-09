/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.ui;

import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.Tooltip;
import qupath.ext.analysisqueue.queue.Job;

/** Native processing has stages but no measurable fraction; only completed jobs show a full bar. */
final class JobProgressCell extends TableCell<Job, Job> {
    private final ProgressBar bar = new ProgressBar();
    private final Tooltip detail = new Tooltip();
    JobProgressCell() {
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setPrefWidth(120);
        bar.setTooltip(detail);
    }
    @Override protected void updateItem(Job job, boolean empty) {
        super.updateItem(job, empty);
        setText(null);
        setGraphic(null);
        if (empty || job == null) return;
        switch (job.state()) {
            case PREPARING, PROCESSING -> bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            case WAITING -> bar.setProgress(0);
            case COMPLETED -> bar.setProgress(1);
            default -> { setText(job.state().toString()); return; }
        }
        String description = job.state() + ": " + job.phase();
        bar.setAccessibleText(description);
        detail.setText(description);
        setGraphic(bar);
    }
}
