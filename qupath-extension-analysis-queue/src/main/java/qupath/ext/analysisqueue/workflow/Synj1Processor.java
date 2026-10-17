/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.ext.analysisqueue.persistence.JsonFiles;
import qupath.ext.analysisqueue.queue.Job;
import qupath.ext.analysisqueue.queue.JobProcessor;
import qupath.lib.io.PathIO;
import qupath.opencv.ml.pixel.PixelClassifiers;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public final class Synj1Processor implements JobProcessor {
    private final ResultPublisher publisher;
    public Synj1Processor(ResultPublisher publisher) { this.publisher = publisher; }

    @Override public Outcome process(Job job, Path directory, Consumer<String> progress) throws Exception {
        var input = JsonFiles.read(directory.resolve("input.json"), AnalysisInput.class);
        var output = Path.of(input.outputDirectory());
        Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        report.put("jobId", job.id()); report.put("image", input.imageName()); report.put("input", input);
        report.put("started", Instant.now().toString()); report.put("dabPixelSizeMicrons", Synj1Analysis.DAB_PIXEL_SIZE);
        long started = System.nanoTime();
        try (var data = PathIO.<BufferedImage>readImageData(directory.resolve("input.qpdata"))) {
            if (!JsonFiles.sha256(directory.resolve("classifier.json")).equals(input.classifierSha256()))
                throw new IllegalStateException("The queued classifier snapshot changed.");
            if (!SourceSignature.compute(data, input.regionIds()).equals(input.sourceSignature()))
                throw new IllegalStateException("The queued annotation snapshot changed.");
            var classifier = PixelClassifiers.readClassifier(directory.resolve("classifier.json"));
            report.put("classifierPixelSizeMicrons", classifier.getMetadata().getInputResolution().getAveragedPixelSizeMicrons());
            int tissueCount = new Synj1Analysis().run(data, input.regionIds(), classifier, phase -> {
                progress.accept(phase); report.put("phase", phase);
                try { JsonFiles.write(output.resolve("run.json"), report); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            });
            report.put("tissueAnnotations", tissueCount);
            progress.accept("Verifying and exporting results");
            new AnalysisArtifacts().write(output, data, input.regionIds());
            progress.accept("Saving results to project");
            String conflict = publisher.publish(input, data);
            var state = conflict == null ? Job.State.COMPLETED : Job.State.NEEDS_REVIEW;
            String message = conflict == null ? (tissueCount == 0 ? "No tissue detected; DAB is not applicable." : "Saved and verified") : conflict;
            report.put("state", state.name()); report.put("message", message);
            report.put("nativeResultReadbackVerified", true); report.put("projectSavedAndVerified", conflict == null);
            report.put("finished", Instant.now().toString()); report.put("totalSeconds", (System.nanoTime() - started) / 1e9);
            JsonFiles.write(output.resolve("run.json"), report);
            return new Outcome(state, message, output.toString());
        } catch (Exception error) {
            report.put("state", "FAILED"); report.put("error", error.toString());
            report.put("finished", Instant.now().toString()); report.put("totalSeconds", (System.nanoTime() - started) / 1e9);
            JsonFiles.write(output.resolve("run.json"), report); throw error;
        }
    }
    @Override public void releaseCompletedInput(Job job, Path directory) throws Exception {
        var input = JsonFiles.read(directory.resolve("input.json"), AnalysisInput.class);
        Files.deleteIfExists(Path.of(input.outputDirectory()).resolve("result.qpdata"));
        Files.deleteIfExists(directory.resolve("input.qpdata"));
    }
}
