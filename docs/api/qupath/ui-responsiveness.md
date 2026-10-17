# UI responsiveness investigation — 2026-10-17

The user's recording showed about 9.3 seconds on FX when adding 9272 to the
analysis queue and at least 12 seconds when publishing its completed result.
Live thread samples later showed an idle event loop, not a persistent deadlock.

Queue 1.1 copies mutable object state on FX, sharing native immutable ROI
references. Workers serialize, fingerprint, prepare rollback files and verify
native saves. FX checks a snapshot stamp and file versions before atomically
publishing the verified files. See [analysis queue](analysis-queue.md) for the
save contract, conflict handling and crash behavior.

The final full-size 9272 verification used all three annotations (245,892,976
bytes) in an isolated project, a real JavaFX loop, offscreen native viewer, undo
manager and recorder. Snapshot work on FX took 5.93 ms; input commit took 21.10 ms;
the final result update including native listeners took 883.24 ms. The full
setup-to-verified-result check took 33.65 seconds. Background input preparation
took 7.39 seconds; result preparation, verification and publication took 11.32
seconds. These are save/publication timings, not a new whole-slide analysis.
The original file checksum and DAB values were unchanged.

Recorder 1.1 samples the JavaFX stack from its background monitor when a pending
FX pulse exceeds one second. It permits one outstanding pulse and at most three
stack samples per stall; `ui.resumed` records the elapsed duration. Session close
stops the monitor. Annotation geometry caching uses ROI identity, avoiding
geometry hashing on the caller thread and releasing removed ROIs after snapshots.

After installation, this monitor caught a separate 1,502 ms mouse-hover stall in
`SortedPackedIntervalRTree.buildTree → GeometryROI.contains →
QuPathViewer.getImageObjectClassificationString`. The label queried containment
for every object and only then discarded annotations. The new typed overload of
`PathObjectTools.getObjectsForLocation` filters candidates before exact ROI
containment. The viewer requests `PathDetectionObject`, including subclasses.
The existing unfiltered overload retains selection semantics.

A cold native viewer test on the complete saved 9091 hierarchy measured 2.46 ms
for the first corrected hover lookup; subsequent calls were below 0.4 ms. The
unfiltered lookup still required 1,659 ms, confirming that annotation containment
was skipped rather than warmed up by the test. The source checksum was unchanged.
Regression tests cover detection subclasses, image planes, point tolerance and
unchanged annotation hit testing.

All 36 selected core, queue and recorder tests passed. UI was rendered and
inspected offscreen at 960×560 and 720×420. No OS keyboard or pointer input was
used. This fixes the evidenced queue and hover paths; native image loading,
annotation selection, undo and third-party extensions may still cause pauses.
The recorder now provides evidence for any remaining intermittent stalls.
