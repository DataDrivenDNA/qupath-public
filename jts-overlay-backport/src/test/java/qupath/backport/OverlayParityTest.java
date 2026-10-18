package qupath.backport;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;

class OverlayParityTest {
    static { System.setProperty("jts.overlay", "ng"); }
    private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(100), 0,
            PackedCoordinateSequenceFactory.FLOAT_FACTORY);

    @Test void exactCoordinatesForHolesIslandsAndBoundaryContacts() throws Exception {
        var reader = new WKTReader(factory);
        var shapes = List.of(
            "POLYGON EMPTY",
            "POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0), (3 3, 3 12, 12 12, 12 3, 3 3), (17 17, 17 26, 26 26, 26 17, 17 17))",
            "MULTIPOLYGON (((0 0, 30 0, 30 30, 0 30, 0 0), (3 3, 3 27, 27 27, 27 3, 3 3)), ((6 6, 24 6, 24 24, 6 24, 6 6), (9 9, 9 21, 21 21, 21 9, 9 9)), ((12 12, 18 12, 18 18, 12 18, 12 12)))",
            "MULTIPOLYGON (((0 0, 10 0, 10 10, 0 10, 0 0)), ((10 10, 20 10, 20 20, 10 20, 10 10)))"
        );
        var clips = List.of(
            "POLYGON ((-1 -1, 31 -1, 31 31, -1 31, -1 -1))",
            "POLYGON ((0 0, 30 0, 30 15, 0 15, 0 0))",
            "POLYGON ((-2 1.123, 32 17.457, 13 32, -2 1.123))",
            "POLYGON ((0 0, 30 0, 30 30, 0 30, 0 0), (5 5, 5 20, 20 20, 20 5, 5 5))",
            "POLYGON ((30 0, 40 0, 40 30, 30 30, 30 0))"
        );
        try (var reference = new Reference()) {
            for (var shape : shapes) for (var clip : clips) {
                var a = reader.read(shape); var b = reader.read(clip);
                for (var operation : List.of("intersection", "difference", "union", "symDifference")) {
                    assertParity(reference, a, b, operation);
                    assertParity(reference, b, a, operation);
                }
            }
        }
    }

    @Test void exactCoordinatesForSeededDenseHoleGeometries() throws Exception {
        var random = new Random(9113);
        try (var reference = new Reference()) {
            for (int seed = 0; seed < 24; seed++) {
                var subject = donuts(8 + seed, 4 + seed % 7);
                double x = random.nextDouble() * 10;
                var clip = new WKTReader(factory).read(String.format(Locale.ROOT,
                    "POLYGON ((%.8f -1, 180 15.237, 12.713 140, %.8f -1))", x, x));
                assertParity(reference, subject, clip, "intersection");
                assertParity(reference, clip, subject, "intersection");
            }
        }
    }

    @Test void packedJarChangesOnlyTheBackportedClass() throws Exception {
        try (var original = new JarFile(System.getProperty("jts.upstream.jar"));
             var patched = new JarFile(System.getProperty("jts.patched.jar"))) {
            var names = original.stream().filter(e -> !e.isDirectory()).map(e -> e.getName()).collect(java.util.stream.Collectors.toSet());
            var actualNames = patched.stream().filter(e -> !e.isDirectory()).map(e -> e.getName()).collect(java.util.stream.Collectors.toSet());
            assertEquals(names, actualNames);
            String changed = "org/locationtech/jts/operation/overlayng/PolygonBuilder.class";
            for (String name : names) {
                if (name.equals("META-INF/MANIFEST.MF")) continue;
                var before = original.getInputStream(original.getJarEntry(name)).readAllBytes();
                var after = patched.getInputStream(patched.getJarEntry(name)).readAllBytes();
                if (name.equals(changed)) assertFalse(Arrays.equals(before, after));
                else assertArrayEquals(before, after, name);
            }
            assertEquals("org.locationtech.jts", patched.getManifest().getMainAttributes().getValue("Automatic-Module-Name"));
        }
    }

    @Test void benchmarkDenseHolesWithByteIdenticalResults() throws Exception {
        var subject = donuts(80, 80);
        var clip = new WKTReader(factory).read("POLYGON ((-1 -1, 645 17.239, 610 630, -1 645, -1 -1))");
        try (var reference = new Reference()) {
            // Warm both implementations on a separate small geometry.
            var warm = donuts(5, 5);
            assertParity(reference, warm, clip, "intersection");
            long start = System.nanoTime();
            byte[] expected = reference.overlay(subject, clip, "intersection");
            double baselineMs = reference.lastOverlayNanos / 1e6;
            start = System.nanoTime();
            Geometry actual = subject.intersection(clip);
            double patchedMs = (System.nanoTime() - start) / 1e6;
            byte[] result = new WKBWriter().write(actual);
            assertArrayEquals(expected, result);
            System.out.printf(Locale.ROOT, "DENSE_HOLE_BENCHMARK shells=6400 originalMs=%.3f patchedMs=%.3f speedup=%.2f exactWkb=true%n",
                    baselineMs, patchedMs, baselineMs / patchedMs);
        }
    }

    private Geometry donuts(int width, int height) {
        var polygons = new Polygon[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double xx = x * 8, yy = y * 8;
            polygons[y * width + x] = factory.createPolygon(ring(xx, yy, 6), new LinearRing[]{ring(xx + 1, yy + 1, 1), ring(xx + 3, yy + 3, 2)});
        }
        return factory.createMultiPolygon(polygons);
    }
    private LinearRing ring(double x, double y, double size) {
        return factory.createLinearRing(new Coordinate[]{new Coordinate(x,y), new Coordinate(x+size,y),
                new Coordinate(x+size,y+size),new Coordinate(x,y+size),new Coordinate(x,y)});
    }
    private void assertParity(Reference reference, Geometry a, Geometry b, String operation) throws Exception {
        var actual = (Geometry)Geometry.class.getMethod(operation, Geometry.class).invoke(a, b);
        assertArrayEquals(reference.overlay(a, b, operation), new WKBWriter().write(actual), operation);
        assertTrue(actual.isValid(), operation);
    }

    /** Original JTS in an isolated loader; no copied/reimplemented geometry algorithm. */
    private static class Reference implements AutoCloseable {
        private final URLClassLoader loader;
        private final Object reader, writer;
        private long lastOverlayNanos;
        private final Class<?> geometry;
        Reference() throws Exception {
            loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("jts.upstream.jar")).toUri().toURL()}, null);
            geometry = loader.loadClass("org.locationtech.jts.geom.Geometry");
            var precision = loader.loadClass("org.locationtech.jts.geom.PrecisionModel");
            var sequences = loader.loadClass("org.locationtech.jts.geom.CoordinateSequenceFactory");
            var factoryType = loader.loadClass("org.locationtech.jts.geom.GeometryFactory");
            var packed = loader.loadClass("org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory").getField("FLOAT_FACTORY").get(null);
            var factory = factoryType.getConstructor(precision, int.class, sequences).newInstance(precision.getConstructor(double.class).newInstance(100.0), 0, packed);
            reader = loader.loadClass("org.locationtech.jts.io.WKBReader").getConstructor(factoryType).newInstance(factory);
            writer = loader.loadClass("org.locationtech.jts.io.WKBWriter").getConstructor().newInstance();
        }
        byte[] overlay(Geometry a, Geometry b, String operation) throws Exception {
            var read = reader.getClass().getMethod("read", byte[].class);
            var input = new WKBWriter();
            Object aa = read.invoke(reader, input.write(a)), bb = read.invoke(reader, input.write(b));
            long started = System.nanoTime();
            Object result = geometry.getMethod(operation, geometry).invoke(aa, bb);
            lastOverlayNanos = System.nanoTime() - started;
            return (byte[])writer.getClass().getMethod("write", geometry).invoke(writer, result);
        }
        public void close() throws Exception { loader.close(); }
    }
}
