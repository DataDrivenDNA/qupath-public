# Local QuPath display-thread fix

Base: upstream v0.7.0, commit `04ccfa4fb7d43e9b566393e08e83690b72248d44`.
This is a local development build, identified as `0.7.0-SNAPSHOT`, not an official release.

## Observed problem

Two independent stack traces from a large SYNJ1 slide showed expensive geometry work on the JavaFX Application Thread:

* `GeometryROI.intersects → RegionFilter.overlapsObjects → PixelClassificationOverlay.paintOverlay`.
* `Path2D.intersects → PixelClassificationMeasurementManager.mayIntersectTile → calculateMeasurements → getCachedMeasurementValue → SummaryMeasurementTable`.

The word "cached" refers to input image tiles. Calling `getCachedMeasurementValue` can still calculate an entire ROI's measurements. Moving only classification or annotation creation to more workers cannot prevent either display freeze.

## Ownership and behavior

`BackgroundCache` owns deduplication, a limit of 128 pending requests per view, cancellation, stale-result rejection, and coalesced JavaFX notifications. It contains no classifier or annotation policy. Cancelled queued tasks are removed from a thread-pool queue. Work that is already inside an indivisible geometry operation may finish that operation before noticing interruption.

`LivePixelMeasurements` owns classifier-specific presentation. Two shared daemon workers compute all columns for a captured ROI together. Requests distinguish both object and ROI; changing the ROI invalidates its previous request. Replacing the model disposes its requests and hierarchy listener. Missing tiles produce no value and are retried after a hierarchy notification, without polling. Completed results never write measurements, geometry, selection, or project data.

`ObservableLazyValue` is the view-facing contract. Explicit display accessors can return a pending value; the existing synchronous model accessors still calculate complete cached-tile results. Tables and plots use display accessors and subscribe to revision notifications. A pending numeric display value is NaN; formatted live values are blank until available. Historical stored measurements keep their original behavior.

The measurement table's Save and Copy actions capture rows, columns and value providers and calculate exports on a separate task. The snapshot retains object references, rather than deep-copying the project: editing an object during an export is not an atomic snapshot of that object's state. The synchronous static export APIs remain synchronous for scripts and extensions.

Save and Copy bind their disabled state to a shared `exportRunning` property; Copy additionally requires selected rows. Export code updates that source property, never the bound action property. Success, failure, cancellation and thread-start failure restore the export state. Changing selection while an export runs cannot enable another export, and completing an export preserves the current selection rule. Regression tests exercise both real actions and the asynchronous export path, including actual file output, selection changes during work, and duplicate requests.

`PixelClassificationOverlay` schedules region filters on its existing workers. Painting uses completed checks, and completion schedules repainting. Structural or filter changes invalidate results. The overlay's own tile-measurement notifications do not invalidate geometry checks; external measurement events still invalidate custom filters. Multiple image hierarchies can be observed by one overlay. Stop detaches listeners and cancels work.

`PixelClassificationMeasurementManager` uses a concurrent per-server ROI cache and one computation per ROI. New all-column access exposes the existing computation, without changing classification, masking, area, percentage or intensity formulas. Cancellation is checked between geometry intersections and tiles.

## Scope and limits

This fixes the two observed automatic display stalls, plus measurement table Save/Copy calculations. It does not promise that every QuPath operation or third-party extension is asynchronous. Complex polygons can still take a long time to calculate, draw, serialize or deserialize. It does not simplify existing annotations, change classification resolution, increase throughput of every algorithm, or change the project format.

The existing lightweight tiled measurement workflow remains preferable for routine large-slide quantification because it avoids generating giant classified polygons. This build allows live display work to proceed independently of interface events.

## Verification

Regression tests use a real JavaFX event thread without showing windows, the real measurement manager and raster calculations, synthetic cached image servers, and gated geometry work. They cover interface responsiveness, all-column deduplication, repeated completion notifications, tile arrival, ROI edits, classifier replacement, export isolation, unchanged annotations and stored measurements, stale worker completion, queue bounds, cancellation, overlay completion and invalidation.

Build and test with Java 25:

```powershell
.\gradlew.bat :qupath-core-processing:test :qupath-gui-fx:test --no-daemon --max-workers=2
.\gradlew.bat jpackage --no-daemon --max-workers=2
```

The portable app is generated under `build/dist/QuPath-0.7.0-SNAPSHOT`. The machine-specific verification record is delivered separately with the build. No upstream submission is part of this work.
