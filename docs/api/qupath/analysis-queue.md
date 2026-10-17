# Native analysis queue integration (QuPath 0.7 snapshot)

The `qupath-extension-analysis-queue` module composes a generic durable FIFO
queue with a SYNJ1 processor, native project persistence, and a JavaFX monitor.
The queue package knows only jobs, state, a store, and a processor contract.
The workflow package owns the scientific parameters; QuPath adapters own editor
capture and publication. No external executor, shell runner, or global QP image
selection is used during processing.

## Native APIs and observed behavior

- `PathIO.writeImageData(OutputStream, ImageData)` snapshots without changing the
  viewer's last-saved path or dirty flag. The Path/File overload does change them.
- `PathIO.readImageData(Path)` opens an independently owned server. The overload
  accepting an existing server borrows it; do not close a borrowed GUI server.
- Native stain deserialization renormalizes vectors and can change their last
  floating-point bit. Input fingerprints use the native stain string at 12 decimal
  places. Annotation geometry, IDs, measurements, and metadata remain exact.
- `PixelClassifierTools.createAnnotationsFromPixelClassifier` acts on the supplied
  hierarchy's selection. The worker sets it explicitly. No split/delete options
  are used, matching the recorded workflow. Existing tissue/empty children are
  reused. Outer lock states are restored after native segmentation.
- `IntensityFeaturesPlugin` is non-generic. It receives explicit `ImageData`,
  `pixelSizeMicrons=0.5`, ROI, DAB, mean and standard deviation. It uses a native
  headless task runner with two threads. Measurement labels follow QuPath's locale.
- `PixelClassificationMeasurementManager` receives the whole annotation collection
  and preferred parallelism four. Native percentage/area float quantization is
  validated with the tolerance described in `native-dab-measurements.md`.
- `ObservableMeasurementTableData.getNumericValue` exposes full precision;
  `getStringValue` rounds numeric display. CSV uses the former and excludes live
  measurements. Empty/missing values are blank.
- `ProjectImageEntry.saveImageData` performs native backup and summary management.
  Publication occurs on the FX thread, serialized with editor actions. It waits
  for that actual operation to finish, so a delayed save cannot run after a
  timeout has already marked the job failed.
- Extension discovery uses QuPath's watched user extensions directory and the
  `QuPathExtension` ServiceLoader entry. Installation does not show a window;
  the user invokes the menu to open the non-modal monitor.

## Data protection and failure boundaries

Capture saves the outline and records the saved file SHA-256, selected branch
fingerprint, classifier checksum, and selected region IDs. Processing uses the
frozen classifier and snapshot. Before publication, the saved file and relevant
in-memory branches must still match. Changed/deleted branches, later saves, or
independent copies in multiple viewers retain results as **Needs review**.

The merger preserves selected root objects and unrelated in-memory annotations.
After merging, native save/readback must match the expected processed branch
fingerprint. A successful processor outcome becomes a durable Completed record
before temporary inputs/results are removed. Failed cleanup logs a warning and
does not erase successful results. An application crash during processing is
recovered as Interrupted, retaining the input for explicit retry. A crash after
completion but before cleanup can leave duplicate files in that completed job's
folder; these are safe to remove after checking its report.

One job failure does not stop later jobs. Queue-store write failure pauses the
queue and exposes an error. Inputs are never silently replaced on retry.

## Verification scope

Native temporary-project tests cover mixed tissue/background, all-empty regions,
multiple outlines, unset image type, repeat processing without duplicate children,
full-precision exports, result publication/readback, saved-source conflict,
preservation of unrelated edits, and changed/deleted outline rejection.
Queue tests cover sequential dispatch, failure continuation, pause, duplicate
rejection, cancellation, interrupted-job recovery, exclusive ownership, and
cleanup after durable completion.

Whole-slide processing time depends strongly on ROI geometry and image size.
Saving a very large annotation hierarchy can briefly occupy the FX thread;
segmentation and measurements run in the background. Native-input desktop
automation is not required for installation or app-scoped acceptance.

## Installed acceptance on 2026-10-17

The user extension JAR loaded into the running QuPath process without restarting.
Eight automated tests passed (three queue, five native workflow). In a separate
temporary project, two 2048×2048-pixel regions referencing slides 9464 and 9272
ran through the actual QueueController and native processor sequentially. Job
durations were 3.71 and 3.44 seconds; total setup-to-verified-result time was
14.57 seconds. These are small-region checks, not whole-slide timing estimates.

Each saved result contained outer, tissue, and empty annotations. Independent
CSV parsing matched native DAB means exactly: 0.5156617341612936 and
0.5026727165203797 for these test regions. Saved original slide-data checksums,
the active image, annotation selection, and dirty state were unchanged. FX-thread
callbacks completed during background processing. Preservation of concurrent
manual edits was tested through the native merger, without desktop input.

Offscreen JavaFX snapshots were visually inspected for empty, processing/waiting,
completed, and selected-row states at 960×560 and 720×420. Completed history was
reloaded from disk. The installed user queue remained empty and ready, and the
recorder remained active with zero dropped events or observer/asset errors. Test
workers and attach helpers exited. Full application restart was not exercised;
interrupted-job recovery and store reopening have automated coverage.
