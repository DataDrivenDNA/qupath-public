# Complete native SYNJ1 annotation measurements

Copy both Groovy scripts in this folder into the QuPath project's `scripts`
folder. Open a reviewed image, then choose the command under **Automate > Project
scripts** and use **Run > Run** in the script editor.

- **Measure tissue DAB at 0.5 um** measures DAB mean and standard deviation in
  every annotation classified exactly `tissue`, then adds permanent tissue/empty
  percentages and pixel areas to every non-empty area annotation, including the
  outer region and empty annotation.
- **Add tissue and empty measurements** runs the same save/export workflow while
  preserving all existing DAB measurements. Use it to complete an earlier analysis
  without recalculating DAB. Both scripts must be installed together.

The command requires a project image with Brightfield H-DAB, H-DAB stains, micron
calibration, and reviewed tissue annotations. It uses the project's saved classifier
`threshhold 220 very high synj1`. That classifier's current resolution is approximately
0.454494 µm; this is separate from the **0.5 µm DAB intensity resolution**. The saved
classifier supplies its own channel, threshold, classes and resolution.

Permanent fields have the prefix `Tissue/empty:` and include `tissue %`, `tissue
area µm^2`, `empty %`, and `empty area µm^2`. They survive image reopening and do not
require the classifier window to stay open. Percentages on the outer region describe
the full outlined region. Tissue and empty children also receive their own values.
Geometric ROI area and classifier pixel-count area are different measurements; both
are exported with distinct column names.

The classifier measures temporary annotation objects with the original ROIs, using
QuPath's native pixel measurement manager, with preferred parallelism set to four.
For three annotations this uses two annotation workers and two tile workers.
Large, detailed outlines can take several minutes each. It checks finite
values, percentages totaling 100%, and agreement between areas and percentages before
copying results to the original annotations. Original geometry, classes, names,
parents and lock states are checked. Existing DAB fields at other resolutions remain.
The command does not create replacement annotations or rerun segmentation.
The installed native measurement code rounds classifier values to float precision;
percentage checks allow approximately 0.000031 percentage points of rounding.
The script preserves the native values without normalizing them.

The image is saved through QuPath's native project API and read back. All annotation
identities, geometry/properties, and measurements must match. A full-precision UTF-8
CSV of **all annotations** and a JSON run record are written to
`Results/Complete annotation measurements/<timestamp-entry-id>/`. The CSV includes
object metadata, centroids, geometric area/perimeter, stored DAB measurements and the
four permanent classifier fields. Blank cells mean a measurement is not applicable
or absent, for example DAB intensity on the outer region. Temporary `(Live)` columns
are excluded. The JSON records classifier name/hash/resolution and whether DAB was
recomputed. `COMPLETED` requires native saved-data verification and successful export.

Run one image at a time and leave the image, stains and annotations unchanged while
it runs. The script checks for concurrent edits before saving but does not lock the
application. Repeating a command replaces its own measurement fields and creates a
new output folder. A failed DAB calculation can leave partial DAB updates in memory;
classifier values are staged until all objects pass validation. Native saving manages
a temporary backup, not an archival history.

The separate SYNJ1 tiled workflow remains available. This command measures the
existing reviewed ROIs with native QuPath APIs, rather than replacing that workflow.
