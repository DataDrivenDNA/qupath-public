# Native DAB measurement command (QuPath 0.7.0)

Implementation: `scripts/synj1/Measure tissue DAB at 0.5 um.groovy`.

This command uses `IntensityFeaturesPlugin` with an explicit JSON numeric
`pixelSizeMicrons` of 0.5. It runs against the original image and existing tissue
ROIs. It does not use the separate SYNJ1 tiled classifier-mask sampling method.

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

This acceptance covers one complete real image with one tissue annotation.
Multiple tissue annotations and other slides have not yet received end-to-end
acceptance for this script. Run on another reviewed slide before adopting a batch
workflow. The script currently operates on the displayed image only.
