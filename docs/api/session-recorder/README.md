# QuPath Session Recorder 1.0.0

Local extension for QuPath 0.7.0. It records evidence for debugging and converting
analysis work into scripts. It does not execute recordings or guarantee complete
replay of every QuPath feature.

## Use

Install the built `qupath-extension-recorder` JAR in the configured QuPath user
directory's `extensions` folder. QuPath's native extension manager discovers its
`QuPathExtension` service. Recording starts automatically when the extension loads,
unless **Extensions > Session recorder > Record automatically on launch** is off.

The same menu provides **Start recording**, **Stop recording**, **Capture
checkpoint**, and **Open recording folder**. Stopping flushes outstanding writes
asynchronously; it leaves the automatic-start preference unchanged.

Sessions are saved under `<QuPath user directory>/recordings/<timestamp>-<id>`.
There is no network upload, desktop input, or global input hook. Session files are
retained until the user removes them. Screenshots and geometry can occupy
substantial space over long sessions; use Stop recording when capture is unwanted.

QuPath's separate `PathPrefs.doCreateLogFilesProperty()` can be enabled to preserve
application startup logs before extensions load. Those logs live in the user
directory's `logs` folder and are independent of the recorder Start/Stop controls.

## Files and schema

| File | Meaning |
| --- | --- |
| `events.jsonl` | One JSON event per line: schema version, UTC time, event type, data; ordinary producer events also have an ordered sequence and thread |
| `workflows/image-N.groovy` | Latest native command history for each image attachment; review manual steps and selections before execution |
| `geometry/image-N-M.wkb.gz` | Gzip-compressed OGC WKB annotation geometry in image pixel coordinates; channel/Z/T plane is in the annotation event |
| `frames/NNNNNN.png` | App-scoped snapshots of visible QuPath JavaFX scenes, scaled to at most 1280 pixels on the long side |

`session.start`, `project`, and `image.attach` identify the session, project,
image name, saved data path, and project entry. Each attachment has a distinct
asset namespace, including when the same image is reopened.

`workflow` preserves ordered native command names, parameters, and available
scripts. `annotation.state` is emitted on baseline capture and on changes to
annotation ID, parent, geometry, name, classification, lock, color, or measurements.
`annotation.removed`, `hierarchy.event`, and `hierarchy.summary` describe removals
and object changes. `selection`, `image.settings`, `image.save_state`, and `viewer`
record surrounding state. Non-finite numeric values are JSON null.

`ui.menu`, `ui.action`, `ui.field`, and `ui.form` record interface intent and
committed values. `ui.pointer`, `ui.key`, and `ui.scroll` add context. The entered
field text and the native workflow parameters are separate evidence: this makes
locale/number interpretation problems visible. UI paths are descriptive rather
than guaranteed stable automation selectors.

`application.log` includes existing-level application logging and exception traces.
The recorder does not change application log levels. Activity schedules snapshots
at most once every two seconds; an inactive scene is not continuously captured.
`checkpoint` requests annotation comparison and a fresh snapshot.

## Completeness and failure signals

The writer uses a bounded queue and a single background thread. JSON ordering is
serialized at submission; geometry compression and PNG encoding run off the UI
thread. Asset creation and screenshots may lag the action timestamp. Callbacks
still do some synchronous state inspection, and screenshots require the JavaFX
thread, so recording is not free of performance overhead.

* `recorder.gap` means the queue filled and events were dropped; sequence gaps are
  also evidence of missing producer events.
* `recorder.asset_error` means the named asset was not successfully written, even
  if its intended asset event follows. Do not treat that file as valid evidence.
* `recorder.observer_error` means state capture failed; application work continues.
* `screenshot.skipped` and `screenshot.error` identify missing snapshots.
* A fatal event-file write failure stops recording and updates the recorder menu.
* `recorder.closed` marks a drained and closed queue and reports error/drop counts.
  Absence can indicate an active session, abrupt termination, or incomplete flush.

`RecorderExtension.recordingStatus()` is a read-only JavaFX-thread diagnostic API
returning installation/recording state, directory, queue length, dropped events,
asset errors, and observer errors. It is not a network service or control port.

## Boundaries

The extension observes QuPath public APIs without mutating or saving analysis
data. It attaches hierarchy, selection, workflow, image property, viewer, project,
JavaFX scene/menu, and Logback listeners. Stop removes these listeners and the
snapshot timer. A shutdown hook allows up to ten seconds for queued output.

Annotation geometry and measurements are captured; full per-detection state is
not captured. Detection operations are represented by hierarchy events, summaries,
and any native workflow commands. Continuous brush movement is represented by
committed geometry and snapshots, not a stroke-by-stroke video. Viewer state is
throttled to 250 ms; save-state polling is every two seconds and cannot prove a
disk save without external readback.

Native OS dialogs, other applications, desktop pointer images, and application
internals without public events are outside scope. Password field text is omitted.
Multiline text/script editors are not fully copied; visible text may still appear
in snapshots or application logs. Files may contain image/project names, paths,
annotation labels, and scientific data. Nothing is automatically redacted beyond
the field rule above, uploaded, or deleted.

QuPath itself documents that command histories do not include every operation:
[Workflows](https://qupath.readthedocs.io/en/stable/docs/scripting/workflows.html).
The additional recording evidence supports reconstructing manual steps; it does
not make them automatically portable to a different slide.

## Verification

`:qupath-extension-recorder:test` exercises ordered concurrent events, strict JSON,
queue overflow, asset and fatal storage failure, observer failure isolation,
annotation geometry edits/removal, workflow capture, real JavaFX menu/control
actions, parameter values, password omission, and listener removal. UI tests create
unshown scenes and do not take desktop focus. Live installation checks are recorded
separately; unit tests alone do not establish cold-launch or full-user-workflow
acceptance.
