/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.roi;

import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

/** Reads stored ROI coordinates without applying a second precision-grid rounding. */
final class GeometryWKB {
    private GeometryWKB() {}

    static Geometry read(byte[] bytes) throws ParseException {
        return new WKBReader(new ReadingFactory(GeometryTools.getDefaultFactory())).read(bytes);
    }

    /** WKBReader uses floating precision; the objects it constructs retain QuPath's native factory. */
    private static final class ReadingFactory extends GeometryFactory {
        private final GeometryFactory nativeFactory;

        private ReadingFactory(GeometryFactory nativeFactory) {
            super(new PrecisionModel(), nativeFactory.getSRID(), nativeFactory.getCoordinateSequenceFactory());
            this.nativeFactory = nativeFactory;
        }

        @Override public Point createPoint(CoordinateSequence coordinates) {
            return nativeFactory.createPoint(coordinates);
        }
        @Override public LinearRing createLinearRing(CoordinateSequence coordinates) {
            return nativeFactory.createLinearRing(coordinates);
        }
        @Override public LineString createLineString(CoordinateSequence coordinates) {
            return nativeFactory.createLineString(coordinates);
        }
        @Override public Polygon createPolygon(LinearRing shell, LinearRing[] holes) {
            return nativeFactory.createPolygon(shell, holes);
        }
        @Override public MultiPoint createMultiPoint(Point[] points) {
            return nativeFactory.createMultiPoint(points);
        }
        @Override public MultiLineString createMultiLineString(LineString[] lines) {
            return nativeFactory.createMultiLineString(lines);
        }
        @Override public MultiPolygon createMultiPolygon(Polygon[] polygons) {
            return nativeFactory.createMultiPolygon(polygons);
        }
        @Override public GeometryCollection createGeometryCollection(Geometry[] geometries) {
            return nativeFactory.createGeometryCollection(geometries);
        }
    }
}
