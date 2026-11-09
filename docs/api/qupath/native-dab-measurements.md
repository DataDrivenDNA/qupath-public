# Native SYNJ1 measurement commands (QuPath 0.7.0)

Implementation: `scripts/synj1/Measure tissue DAB at 0.5 um.groovy`.
Companion: `scripts/synj1/Add tissue and empty measurements.groovy`.

This command uses `IntensityFeaturesPlugin` with an explicit JSON numeric
`pixelSizeMicrons` of 0.5. It runs against the original image and existing tissue
ROIs. It does not use the separate SYNJ1 tiled classifier-mask sampling method.
The companion evaluates the same workflow with the boolean binding `measureDab`
set to false, preserving previous intensity measurements.

Relevant native API behavior verified from source and the installed build:

* `IntensityFeaturesPlugin.getParentObjects` reads the hierarchy selection model.
  Set targets on the JavaFX thread before dispatching the plugin off that thread.
* `TaskRunnerUtils.createHeadlessTaskRunner(2)` runs the native plugin without a
  progress dialog. The plugin tiles large ROIs internally. Thread count does not
  cap the image server's shared caches or all native memory allocations.
* Measurement names format the requested resolution using the current format
  locale. Construct keys with the same locale; JSON numeric 0.5 bypasses localized
  input parsing. The active installation uses en-US; its text entry is `0.5`.
* `ProjectImageEntry.saveImageData` writes native `.qpdata`, updates saved state and
  summary metadata, and manages a temporary backup during the write. The backup
  is removed on successful save; this is not archival versioning.
* `PathIO.readImageData(path, existingServer)` permits saved-data readback with the
  same server. Never close that borrowed server while the image is displayed.
* `PixelClassifierTools.createPixelClassificationServer(imageData, classifier)`
  uses the classifier's own resolution. The saved Red threshold-220 classifier
  requests 0.4544938075218725 micrometres, separately from DAB's 0.5 micrometres.
* `PixelClassificationMeasurementManager.addMeasurements(objects, prefix)`
  computes pixel-count areas and class percentages for each ROI. Its protected
  `calculatePreferredParallelism()` hook permits a preferred parallelism of four
  without changing global thread settings. This is not a strict total thread cap.
  Submit the object collection together so the
  manager uses two annotation workers and two tile workers for three annotations.
  Submitting one complex annotation at a time serializes costly shape processing.
  The manager shuts down its worker pools.
* The manager locks measured objects. Measure temporary annotations using the
  original ROIs, then copy validated values on the JavaFX thread to preserve
  original lock states and avoid partially modifying the real hierarchy.
* In this build, `NumericMeasurementList.DoubleList.setValue` casts its input to
  float, despite storing it in a double array. This pre-existing native precision
  behavior also affects the classifier manager's internal measurement list.
  A live 100-by-100-pixel diagnostic returned 99.44000244140625% tissue and
  0.5600000023841858% empty, totaling 100.00000244379044%. Checks allow four float
  ULPs at 100% (0.000030517578125 percentage points) and retain the native values.
  The workflow does not change this core implementation or normalize results.
* Classifier measurement keys are `Tissue/empty: tissue %`, `Tissue/empty: tissue
  area µm^2`, `Tissue/empty: empty %`, and `Tissue/empty: empty area µm^2`. They
  persist in native annotation measurement lists. Geometric ROI area is separate.
* `ObservableMeasurementTableData.getAllNames()` supplies annotation metadata and
  measurement columns. Numeric fields must use `getNumericValue` for unformatted
  precision; `getStringValue` formats numeric values for display. Export all
  annotation rows, including outer regions and empty children.
* `(Live)` classifier fields depend on a temporary `PIXEL_LAYER` image property.
  They are not substitutes for stored measurements and are excluded from exports.

Acceptance on 2026-10-17, project entry 9 (`9026 Corp_str_H. SYNJ1.ndpi`):

* Tissue ID `8cb2ea9b-079d-4211-9ced-7b1f7ceab4e0` measured at 0.5 micrometres.
* Mean `0.47651589489991913`; standard deviation `0.1461942742569477`.
* Measurement 88.43 seconds; measurement, save, saved-data verification and export
  98.55 seconds, excluding installation and preflight preparation.
* All three annotation IDs, exact ROIs, names/classes, parents and lock states
  passed native saved-data readback; unrelated 5.00 micrometre fields retained.
* CSV independently parsed and matched to the saved result at full precision.
* No unsaved image changes remained; the 0.50 micrometre columns were visible in
  the annotation measurement panel. Recorder queue/drop/error counts were zero.

Subsequent DAB acceptance on 9464 (entry 41) and 9272 (entry 31) preserved all
three annotations on each image and exported the requested 0.50 micrometre fields.
Means were 0.5317229239351402 and 0.522597343354813; standard deviations were
0.18032916851829972 and 0.16032282189545555, respectively. Each saved result was
read back and independently compared with the CSV. Multiple tissue annotations
per image have not received live acceptance. Commands operate on the displayed
image; the two-image validation driver opens each explicit project entry in turn.

Permanent classifier measurement acceptance on 2026-10-17:

| Image | Parent tissue % | Parent empty % | Tissue area µm² | Empty area µm² |
| --- | ---: | ---: | ---: | ---: |
| 9464 | 94.80162811279297 | 5.198374271392822 | 144871824 | 7943935.5 |
| 9272 | 98.02084350585938 | 1.9791574478149414 | 127285584 | 2570047.5 |

The companion command measured all three existing area annotations on each image.
Native readback verified all IDs, exact ROI geometry, parents, names, classes,
lock states and full measurement maps. Previous 0.50 and 5.00 micrometre DAB
values were unchanged. Both per-image CSVs and the combined six-row, 19-column
CSV passed an independent Python comparison with the native readback results.

9464 used one annotation at a time with preferred parallelism two: classification
took 1315.39 seconds and classification/save/readback/export took 1331.58 seconds.
9272 exercised the final implementation, submitting the annotation collection
together with preferred parallelism four: 325.83 and 335.67 seconds, respectively.
These are different images and do not establish a controlled speed comparison.
The successful two-image driver also includes image opening/switching overhead.

The final GUI check selected 9272's parent ROI and verified all four permanent
fields in the annotation measurement panel with no live pixel layer. There were
no unsaved changes, no remaining classification or intensity worker threads,
and recording was active with zero queued, dropped, asset-error or observer-error
events. Both Groovy commands compiled in the installed application's classloader.
The extended DAB-plus-classifier command was not rerun in full: its unchanged
DAB calculation had prior live acceptance, and this run exercised the companion
path to preserve the already verified DAB values.
