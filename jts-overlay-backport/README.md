# JTS OverlayNG hole-placement backport

This included Gradle build replaces JTS 1.20.0 with the same distribution plus
one upstream implementation change. It addresses long tissue/empty creation
waits inside `PolygonBuilder.placeFreeHoles` when raster contours have many
polygons and holes. No QuPath classifier, mask resolution, filters, measurements
or geometry coordinates are changed by the patch.

Source: [JTS commit c874270](https://github.com/locationtech/jts/commit/c874270694e3985f9af29134d8828bd0cd557f9a),
“Added spatial index to OverlayNG PolygonBuilder.placeFreeHoles (#1173)”,
2026-01-16. The included Java source matches that commit, ignoring whitespace.
It retains its upstream EPL-2.0/EDL-1.0 license header. The built jar preserves
all other upstream files and adds an identifying manifest. The original
`org.locationtech.jts` automatic module name is retained.

The change uses JTS's existing HPRtree to limit candidate shells, then calls the
same containment function to assign each hole. Result rings and coordinates
are not simplified, resampled, rounded differently or filtered.

The parent build discovers this replacement by Gradle's normal composite-build
module substitution (`org.locationtech.jts:jts-core`). The isolated upstream
configuration disables that substitution to obtain the original artifact for
compilation, packaging and comparison. Consumers get the complete jar rather
than the one-class directory, preventing partial JTS classpaths. The jar is
identified as `1.20.0-qupath.1`; the version catalog still records its upstream
base, 1.20.0. Upgrading to an official release containing #1173 should remove
this included build and its parent `includeBuild` entry together.

From the repository root:

```
./gradlew :jts-overlay-backport:test
```

Tests compare byte-identical WKB against the unmodified library loaded in an
isolated classloader, including all four overlay operations, nested islands,
holes, boundary contacts, fractional intersections and dense seeded masks.
A packaging test verifies that only PolygonBuilder.class and the manifest
change. Timing output is a benchmark observation, not a pass/fail limit.
See `docs/api/qupath/jts-overlay-performance.md` for native acceptance evidence.
