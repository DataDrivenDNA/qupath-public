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
- Object creation runs on a private snapshot. Unchanged target branches receive
  results while unrelated annotations and current selection survive. If an image
  was closed, its latest saved data is read and checked before publishing.
- Changed or reopened targets retain their current work. Completed results are
  saved separately under the QuPath user directory's `background-results/`, and
  the error identifies that recovery file. Duplicate jobs for an image are blocked.
- Native undo history and viewer repaint remain separate responsibilities.

## Verification

The initial verification passed 30 GUI tests, 4 processing tests, 15 queue tests,
and the native geometry serialization regression (nine geometry forms).
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

No desktop keyboard, pointer or foreground activation was used. These checks do
not claim that rendering arbitrary complex geometry can never cause a pause.
