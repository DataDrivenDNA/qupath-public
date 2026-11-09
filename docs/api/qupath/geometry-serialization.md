# Native geometry save/readback — QuPath 0.7 snapshot

## Defect and boundary

`GeometryROI.WKBSerializationProxy` writes two-dimensional WKB coordinates.
The old reader constructed `WKBReader` using QuPath's default geometry factory,
which combines packed float coordinates with a fixed 0.01-pixel precision grid.
JTS applies that grid while reading. Clipping and affine transforms can produce
native float coordinates between grid lines, so an otherwise valid native
save/readback changed coordinate bits and invalidated the queue's strict result
signature. Cached ROI statistics could then describe the pre-rounded boundary.

`GeometryWKB` owns the reader adaptation. WKB decoding uses floating precision;
all geometry creation is delegated to the native factory. This preserves the
existing packed coordinate representation, factory metadata, empty components,
image plane, and binary file format. Queue signatures remain strict; no geometry
tolerance or measurement rounding was added. This does not change the separate
existing precision limit of the native packed float coordinate representation.

## Evidence on 2026-10-18

The new serialization regression failed on the old reader and passed on the fix.
It checks exact WKB equality through two saves, with and without cached stats,
for polygons with holes, multipart and mixed geometry, lines, points, and empty
components. Native coordinate storage and factory metadata are checked too.
The focused geometry, I/O, prepared-save, and queue suites passed all 33 tests.

A broader 136-test ROI/I/O/project run exposed two existing failures:
`TestGeometryTools.testComplexROIs` and `TestROIs.testAreas`. Both also fail when
run against the unmodified HEAD reader. They concern the old hierarchy fixture's
validity and are not claimed fixed here. One other broad-suite test is skipped.

Full-size 9023 recovery compared each annotation's loaded WKB hash with its
original serialized bytes. The old reader changed 1,973 tissue and 2,060 empty
coordinate ordinates, by at most 0.0078125 pixel. The patched reader preserved
all stored coordinates exactly. All IDs and measurement maps were checked.
The complete native write/readback/export verification took 15.0 seconds,
including source loading and checksum checks, without rerunning segmentation.
The result retains 0.50 µm/pixel DAB mean 0.42330889381351156 and standard
deviation 0.14792676977926514. Parent tissue/empty percentages are
96.51117706298828 and 3.488821506500244.

The existing failed queue record and project data are retained. Recovery exports
are separate verified artifacts; they must not be described as a completed queue
publication. The original retained result is sufficient for recovery and was not
overwritten. Installation into the running GUI requires a safe restart.
