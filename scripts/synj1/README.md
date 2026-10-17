# Native tissue DAB measurements

Copy `Measure tissue DAB at 0.5 um.groovy` into a QuPath project's `scripts` folder.
Open an annotated image, then use **Automate > Project scripts** to open this
script and **Run > Run** in the script editor.

The command measures every existing annotation classified exactly `tissue` on the
current image. It requires Brightfield H-DAB, H-DAB stains, and micron calibration.
It preserves the current stain settings and uses QuPath's native intensity plugin
with an explicit numeric pixel size of 0.5 micrometres, ROI region, DAB channel,
mean, and standard deviation. Two worker threads keep the analysis bounded.

It saves the image through the native project API, reads the saved data back,
checks annotation identity/geometry/properties and measurements, then exports a
full-precision UTF-8 CSV and JSON run record under
`Results/DAB 0.5 um/<timestamp-entry-id>/`. CSV rows are tissue annotations only;
area is the vector ROI area converted using the slide's pixel calibration.

Repeated runs recalculate the same 0.50 micrometre measurement fields and create a
new result folder. Existing measurements at other resolutions remain. No classifier
is rerun and no annotation is created or removed. This native-ROI measurement is
separate from the existing SYNJ1 tiled method, which samples a classifier mask.

Run one image at a time. Leave the image, stain settings, and annotations unchanged
while measurement runs. The command checks for changes before saving. It does not
lock the application against concurrent edits. A failed/cancelled measurement may
leave partial measurement updates in memory; it is not saved by the command unless
the run reaches the explicit save step. `run.json` reports which phase succeeded;
`COMPLETED` requires saved-data verification and a successful CSV write.

Project/API details: QuPath's `IntensityFeaturesPlugin.getParentObjects` uses the
selection model, so the script explicitly selects its tissue targets. Native
`ProjectImageEntry.saveImageData` manages a temporary backup during the write;
that backup is not a permanent archive. `PathIO.readImageData(path, server)` is
used for readback without opening another slide server. The existing shared server
must not be closed by the readback code.
