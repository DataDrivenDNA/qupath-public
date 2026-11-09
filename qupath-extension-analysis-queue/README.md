# Analysis queue

A local QuPath 0.7 extension for the SYNJ1 workflow. Draw the outer region(s), add
the current image, and continue annotating another slide while one background
worker performs the analysis.

1. Open an image in the saved project and draw/select its outer analysis region.
2. Choose **Extensions → Analysis queue → Add current image to queue**.
3. The queue window opens. Move to another slide and continue outlining. Use the
   window's **Add current image to queue** button for the next image.
4. A **Preparing** row saves and verifies the input in the background. A **Completed**
   row means the verified native result was published to the project,
   and its CSV was exported. Select the row and click **Open results folder**.

With no selection, all outer area annotations are included. Selecting tissue or
empty children gives a message asking you to select their outer region. Multiple
selected outer regions are supported. Adding the same image twice while it is
waiting or processing is rejected.

## Automatic steps

- Copy the current annotations quickly, then save and verify that snapshot in the background.
- For regions without existing tissue/empty children, create those annotations
  using the project's `threshhold 220 very high synj1` classifier: **Red threshold
  220**, minimum object area **0**, minimum hole area **0**.
- Measure **DAB mean and standard deviation at 0.5 µm/pixel** on tissue annotations.
- Add permanent tissue/empty percentages and areas to the queued annotation branches.
- Verify the native result, export full-precision CSV, prepare and reopen the project
  save in the background, then atomically publish those verified files.

The classifier retains its saved resolution (the current project's classifier is
0.4544938075 µm/pixel). This is separate from the **0.5 µm DAB measurement resolution**.
An unset image type becomes brightfield H-DAB; existing H-DAB stain settings are
preserved. An all-empty outline completes with DAB marked not applicable.

## Queue and editing behavior

The window is non-modal; closing it leaves the queue running. Reopen it with
**Extensions → Analysis queue → Show analysis queue**. Pause takes effect after
the current image. Waiting jobs can be removed; failed or interrupted jobs can
be retried from their saved inputs. Keep QuPath open while it processes.

The worker owns a separate image server and annotation hierarchy. It never uses
the editor's current selection for its calculations. Results replace only the
queued branches after their input is checked. Other unsaved branches survive.

Preparing inputs reserves their place in the queue. If you edit that image during
preparation, **Could not queue** asks you to add the latest outline again. Moving to
another slide is supported, including QuPath's automatic save of the previous one.
After a restart, an unfinished Preparing row also asks you to add the image again.

If the queued image is saved again, its selected region changes, or its image
settings change, the row becomes **Needs review** and the computed result is kept
separately. Your later edits take priority. Requeue the latest outline to process
those edits. Opening an image to inspect it is safe; saving it again invokes this
conservative conflict check. The same image open in multiple independent viewers
also requires review.

## Files and restart recovery

- Project exports: `Results/Analysis queue/<job ID>/measurements.csv` and `run.json`.
  The report identifies the image, selected region IDs, classifier checksum,
  resolutions, timings, and save/verification status.
- Queue history and frozen inputs: `<QuPath user directory>/analysis-queue/jobs/`.
- Completed jobs remove duplicate native input/result files after their completed
  state is durable. Failed, interrupted, and review jobs retain their inputs/results.
- Waiting jobs resume on the next launch unless paused. A job interrupted by
  shutdown is shown as **Interrupted** and requires Retry. A file lock prevents
  two QuPath processes from running the same queue.

## Build and checks

Build with `./gradlew :qupath-extension-analysis-queue:test :qupath-extension-analysis-queue:jar`.
Version 1.1 requires this build's patched `qupath-core` JAR for prepared native saves,
as well as the extension JAR in QuPath's user `extensions` folder. Restart QuPath
after updating both. Tests use temporary
projects and native image readers, classifiers, measurements, save/readback and
CSV export. See [native API notes](../docs/api/qupath/analysis-queue.md).
