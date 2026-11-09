/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.queue.JobProcessor;
import qupath.lib.gui.background.BackgroundProgress;
import java.nio.file.Path;
import java.util.function.Consumer;

/** GUI adapter: queue processing remains visible when its monitor window is closed. */
final class ProgressReportingProcessor implements JobProcessor {
    private final JobProcessor delegate;
    ProgressReportingProcessor(JobProcessor delegate) { this.delegate = delegate; }
    @Override public Outcome process(Job job, Path directory, Consumer<String> progress) throws Exception {
        try (var activity = BackgroundProgress.start("Analysis queue — " + job.imageName(), "Opening saved input")) {
            return delegate.process(job, directory, phase -> {
                activity.phase(phase);
                progress.accept(phase);
            });
        }
    }
    @Override public void releaseCompletedInput(Job job, Path directory) throws Exception {
        delegate.releaseCompletedInput(job, directory);
    }
}
