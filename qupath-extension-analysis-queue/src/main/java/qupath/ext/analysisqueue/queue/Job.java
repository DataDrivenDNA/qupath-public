/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.queue;

import java.time.Instant;
import java.util.UUID;

/** Immutable durable queue record; input and result formats belong to the processor. */
public record Job(String id, String imageKey, String imageName, State state, String phase,
                  String message, String createdAt, String startedAt, String finishedAt, String outputPath) {
    public enum State {
        PREPARING("Preparing"), PREPARATION_FAILED("Could not queue"),
        WAITING("Waiting"), PROCESSING("Processing"), COMPLETED("Completed"),
        NEEDS_REVIEW("Needs review"), FAILED("Failed"), INTERRUPTED("Interrupted"), CANCELLED("Cancelled");
        private final String label;
        State(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public static Job waiting(String imageKey, String imageName) {
        return new Job(UUID.randomUUID().toString(), imageKey, imageName, State.WAITING,
                "Waiting", "", Instant.now().toString(), null, null, null);
    }
    public static Job preparing(String imageKey, String imageName) {
        var job = waiting(imageKey, imageName);
        return new Job(job.id, imageKey, imageName, State.PREPARING, "Saving and verifying input", "", job.createdAt, null, null, null);
    }
    public boolean active() { return state == State.PREPARING || state == State.WAITING || state == State.PROCESSING; }
    public Job processing(String step) {
        return new Job(id, imageKey, imageName, State.PROCESSING, step, "", createdAt,
                startedAt == null ? Instant.now().toString() : startedAt, null, outputPath);
    }
    public Job finish(State status, String detail, String output) {
        return new Job(id, imageKey, imageName, status, status.toString(), detail, createdAt,
                startedAt, Instant.now().toString(), output);
    }
    public Job retry() {
        return new Job(id, imageKey, imageName, State.WAITING, "Waiting", "", createdAt, null, null, outputPath);
    }
}
