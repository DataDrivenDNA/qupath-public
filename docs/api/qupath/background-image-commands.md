# Background native image commands

The 2026-10-18 recording showed the JavaFX thread waiting for native contour
tracing during Create objects, serializing large hierarchies during Save, and
deserializing them during image loading. Some segmentation waits lasted over
30 minutes. This change keeps the native algorithms, classifier settings and
measurement resolution intact.

## Ownership and scheduling

`ImageDataSnapshot` copies mutable object state on the owner thread, sharing
immutable native ROIs. It does not serialize geometry or open a new server.
The snapshot owns its independently opened server when workers need pixels.
`ImageDataBranchUpdate` publishes only validated branches and copies their
objects, so publication does not consume the retained result.

`BackgroundTasks` has separate single-worker lanes for I/O and object creation.
The classifier can still use its native internal parallelism. Workers expire
after 30 idle seconds. The public synchronous GUI methods retain their return
value semantics through JavaFX's public nested event loop: input and painting
continue while the caller awaits completion. GUI commands must begin in an
input handler or `Platform.runLater`, not layout or animation. There is no
modal progress window and no application-wide input block.

Reference: [JavaFX 25 Platform API](https://openjfx.io/javadoc/25/javafx.graphics/javafx/application/Platform.html#enterNestedEventLoop(java.lang.Object)).

## Preservation rules

- Save captures the click-time state. A later edit stays dirty. Saves are ordered;
  an older completion cannot reset a newer saved state. A separate native save
  invalidates an older pending snapshot rather than being overwritten.
- Native project and standalone files are staged, then atomically replaced with
  rollback protection. Serialization, copying and cleanup run on the I/O lane.
  Other project backends retain their own native persistence implementation.
- A load owns its server and warms the thumbnail on the worker. It is applied only
  if its viewer, source state, project context, request token and native file
  version are still current. Abandoned servers are closed on the worker.
- Save-before-close, image changes and project changes recheck viewer identity
  and edits after background work. A failed save cannot authorize closing.
- An unfinished outline lives outside the hierarchy until mouse release. Loads,
  closes and undo cannot discard it. Native and queue publication preserve its
  selected object identity, and a save cannot mark the unfinished stroke clean.
- Object creation runs on a private snapshot. Unchanged target branches receive
  results while unrelated annotations and current selection survive. If an image
  was closed, its latest saved data is read and checked before publishing.
- Changed or reopened targets retain their current work. Completed results are
  saved separately under the QuPath user directory's `background-results/`, and
  the error identifies that recovery file. Duplicate jobs for an image are blocked.
- Undo history uses the same owner-thread snapshot boundary. Its serialization
  runs on the I/O worker. Pending records retain the configured undo depth;
  obsolete records and closed viewers cannot recreate cleared history. Undo and
  redo decode on the worker, then commit only if the image and edits remain
  unchanged. A second undo waits until the first finishes; drawing stays enabled.
  Viewer repaint remains a separate responsibility.
- Native display shapes are prepared on the worker before loading, undo, or
  segmentation publication. The existing simplification algorithm and analysis
  geometry are unchanged. Cache creation never holds the shared cache lock
  while converting a large ROI; cached levels remain readable while a worker
  prepares additional levels.

## Verification

Final verification passed 42 GUI tests, 5 processing tests, 17 queue tests,
and the native geometry serialization regression (nine geometry forms): 65 tests.
Tests include worker stalls with live FX callbacks, concurrent edits, ordered
saves, external file replacement, failed saves, load supersession, abandoned
server closure, full-image detections, selection, recovery, and closed-image
publication. Native tissue/empty geometry matches direct classifier execution.

A real 9272 hierarchy (245,892,976 bytes) was copied into an isolated project.
The native viewer and undo manager were attached without a window. An annotation
added during saving survived, the first snapshot left it dirty, and the next
save retained it. Background save took 0.97 s and load 4.62 s; the original file's
SHA-256 and all original branch fingerprints, including 0.50 µm DAB values,
were unchanged. The full check took 32.07 s. The load still exposed a 1.18 s FX
pause in undo history initialization, motivating a separate undo-history fix.

After moving undo serialization to the worker, the same native check retained
the concurrent edit and exact original fingerprints. Saving while annotating
took 1.84 s including pending history work, with a maximum FX callback delay of
0.31 ms. The next save took 2.24 s, with 0.57 ms maximum FX delay. Loading took
4.87 s, with 0.46 s maximum FX delay during viewer initialization. The complete
check took 21.17 s; filesystem caches were warm, so overall runtime is not a
cold-cache comparison. The original 245,892,976-byte file remained unchanged.

Final verification adds undo ordering, redo invalidation, edits during pending
undo, disposal, bounded pending history, two viewers sharing one hierarchy, and
unfinished strokes during loading, saving, undo, native segmentation, queue
capture and queue publication.

The first live restart exposed a further 5-second pause in native display-shape
preparation on 9222. The follow-up change prepares these shapes on workers for
loads, undo and completed segmentation. Tests compare native shape paths across
zoom levels, concurrent cache requests, and another viewer rendering while one
worker is blocked preparing a shape.

An isolated copy of the actual 429,485,780-byte 9222 hierarchy was then tested
with an offscreen viewer sized 1726 × 1048. Direct, unprepared viewer setup took
7.18 s. Background loading with display preparation took 3.78 s, with a maximum
sampled FX callback delay of 0.84 s. Saving during a concurrent annotation took
3.11 s, with a maximum callback delay of 0.15 s; the next save took 3.62 s with
1.13 ms maximum delay. Original geometry fingerprints and file checksum matched.
The full check took 29.81 s. This test used a 12 GiB heap; diagnostic GC logging
recorded memory-cleanup pauses up to 0.61 s. The installed app retains its original
50% RAM heap limit. No claim of zero rendering or garbage-collection pauses is made.

No desktop keyboard, pointer or foreground activation was used. These checks do
not claim that rendering arbitrary complex geometry can never cause a pause.
