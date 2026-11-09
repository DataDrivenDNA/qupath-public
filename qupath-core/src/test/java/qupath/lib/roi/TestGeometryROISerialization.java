package qupath.lib.roi;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKBWriter;
import qupath.lib.regions.ImagePlane;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestGeometryROISerialization {
    @Test
    void preservesOffGridBoundariesAndPlaneThroughRepeatedNativeSerialization() throws Exception {
        var factory = GeometryTools.getDefaultFactory();
        // Clipping and transforms can produce float coordinates between the 0.01-pixel grid lines.
        var shell = factory.createLinearRing(new Coordinate[]{
                new Coordinate(20464.033203125, 6989.0810546875), new Coordinate(20474.125, 6989.0810546875),
                new Coordinate(20474.125, 6999.125), new Coordinate(20464.033203125, 6999.125),
                new Coordinate(20464.033203125, 6989.0810546875)});
        var hole = factory.createLinearRing(new Coordinate[]{
                new Coordinate(20466.125, 6991.125), new Coordinate(20466.125, 6993.125),
                new Coordinate(20468.125, 6993.125), new Coordinate(20468.125, 6991.125),
                new Coordinate(20466.125, 6991.125)});
        var polygon = factory.createPolygon(shell, new LinearRing[]{hole});
        var second = GeometryTools.createRectangle(68630.125, 7000.125, 12, 14);
        var multipart = factory.createMultiPolygon(new Polygon[]{polygon, (Polygon)second});
        var line = factory.createLineString(new Coordinate[]{
                new Coordinate(0.003, 1.007), new Coordinate(5.003, 6.007)});
        var plane = ImagePlane.getPlaneWithChannel(2, 3, 4);
        var withEmptyPart = factory.createMultiPolygon(new Polygon[]{polygon, factory.createPolygon()});
        var point = factory.createPoint(new Coordinate(0.003, 1.007));
        var multiPoint = factory.createMultiPoint(new org.locationtech.jts.geom.Point[]{point, factory.createPoint()});
        var multiLine = factory.createMultiLineString(new org.locationtech.jts.geom.LineString[]{line, factory.createLineString()});
        var collection = factory.createGeometryCollection(new Geometry[]{multipart, point, line, factory.createPolygon()});
        for (Geometry geometry : List.of(polygon, multipart, line, factory.createPolygon(), withEmptyPart,
                point, multiPoint, multiLine, collection)) {
            for (boolean cacheStats : List.of(false, true)) {
                var original = new GeometryROI(geometry, plane);
                if (cacheStats) { original.getArea(); original.getLength(); original.getCentroidX(); }
                var restored = roundTrip(original);
                var twice = roundTrip(restored);
                for (var actual : List.of(restored, twice)) {
                    assertArrayEquals(new WKBWriter(2).write(original.getGeometry()),
                            new WKBWriter(2).write(actual.getGeometry()), geometry.getGeometryType());
                    assertEquals(plane, actual.getImagePlane());
                    assertEquals(original.getArea(), actual.getArea());
                    assertEquals(original.getLength(), actual.getLength());
                    assertEquals(factory.getPrecisionModel(), actual.getGeometry().getPrecisionModel());
                    assertSame(factory.getCoordinateSequenceFactory(), actual.getGeometry().getFactory().getCoordinateSequenceFactory());
                }
            }
        }
    }

    private static GeometryROI roundTrip(GeometryROI roi) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(roi); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (GeometryROI)input.readObject();
        }
    }
}
