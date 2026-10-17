/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.queue;

import java.nio.file.Path;
import java.util.function.Consumer;

@FunctionalInterface
public interface JobProcessor {
    Outcome process(Job job, Path directory, Consumer<String> progress) throws Exception;
    /** Called only after the completed queue record is durable. */
    default void releaseCompletedInput(Job job, Path directory) throws Exception {}
    record Outcome(Job.State state, String message, String outputPath) {
        public Outcome {
            if (state != Job.State.COMPLETED && state != Job.State.NEEDS_REVIEW)
                throw new IllegalArgumentException("A processor must complete or retain a result for review");
        }
    }
}
