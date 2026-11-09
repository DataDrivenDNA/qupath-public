# Exact JTS overlay performance backport

## Observed bottleneck

The 2026-10-18 run of slide 9113 reached two native contour tasks with
20,766,322 and 20,849,394 coordinate pairs. Both workers later spent minutes
inside `OverlayEdgeRing.findEdgeRingContaining`, called from
`PolygonBuilder.placeFreeHoles`. JTS 1.20.0 scans the full shell list for each
unassigned hole. With many small polygons and holes, this is a quadratic search.
This worker bottleneck is distinct from JavaFX responsiveness.

## Change and ownership

The included build `jts-overlay-backport` packages the original JTS 1.20.0 jar
with one upstream class replacement, preserving every other class/resource.
It backports [JTS #1173, commit c874270](https://github.com/locationtech/jts/commit/c874270694e3985f9af29134d8828bd0cd557f9a).
The source matches the pinned upstream commit ignoring whitespace. An HPRtree
filters candidate shell envelopes before calling the existing exact containment
method. The operation still uses JTS's existing noding, precision model, ring
construction and coordinate output. No contour tracing, resolution, threshold,
hole-size filter, simplification or measurement parameter is changed.

Gradle composite substitution makes this the JTS artifact for local application
builds. The upstream configuration disables substitution for obtaining the
original comparison/packaging artifact. Exporting only the complete jar avoids
an incomplete compile classpath containing only the replacement class.
The replacement retains automatic module name `org.locationtech.jts` and has
manifest version `1.20.0-qupath.1`. Remove the included build when upgrading to
an official JTS release containing the fix. The normal external version catalog
continues to record the upstream baseline; this is a local application backport.

## Validation

100 relevant tests passed: 4 backport tests, 25 core contour/ROI/serialization
tests, 49 GUI tests, 5 native object-creation tests and 17 queue tests.
The backport tests contain 209 exact overlay comparisons with isolated original
JTS, covering all four overlay operations, empty geometries, nested islands,
holes, touching boundaries, fractional intersections and dense seeded geometry.
They compare raw WKB bytes, including coordinate/ring ordering, rather than only
area. Packaging verification permits only `PolygonBuilder.class` and manifest
changes. A synthetic intersection with 6,400 shells and 12,800 holes measured
1.89 s original versus 0.91 s patched (2.06x) in the recorded run; timing is not
a flaky pass/fail assertion.

The original and patched installed-classpath runtimes independently analyzed
three bounded regions from a private copy of 9113's queued input. They used the
saved Red threshold 220 classifier, native tissue/empty creation with zero area
or hole filtering, and the full native DAB mean/SD calculation at 0.5 µm.
Classifier resolution remained 0.4544938075218725 µm, independently of the DAB
measurement resolution. All 9 resulting geometries had byte-identical WKB;
all 42 stored numeric measurements, including DAB, matched bit-for-bit.

| Region | Original region creation | Patched region creation |
| --- | ---: | ---: |
| Interior, 4096 × 4096 px | 4.80 s | 3.77 s |
| Outline boundary, 4096 × 4096 px | 2.80 s | 1.34 s |
| Interior, 8192 × 8192 px | 18.22 s | 9.29 s |

Complete validation-loop time (loading, cropping, region creation, DAB and area
measurements, output validation data and server close) was 41.19 s original and
27.25 s patched. Measurements ran in separate JVMs with two native analysis
workers and 2 GiB heap limits, while the user's original full-slide queue kept
running. These are sample timings with shared machine load and warmer filesystem
caches on the second run, not a full-slide speed guarantee. The pure geometry
benchmark separately excludes file I/O. No project results were written by these
checks. Detailed evidence is in `D:\QuPath development\region-clipping-20261018`.

## Activation

The running JVM retains the original implementation. The safe activation path
is to verify/save current annotations, stop QuPath at an agreed boundary, back
up and replace its single JTS jar, and restart. An unfinished queued run must
either finish first or be explicitly authorized for interruption, then retried
from its preserved input. Merely replacing a file does not accelerate an
already-running method. Installed/live acceptance is recorded separately from
the source and isolated tests above.
