package qupath.lib.objects;

import org.junit.jupiter.api.Test;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TestObjectLocationFilter {
    @Test void detectionLookupNeverTestsContainingAnnotationGeometry() {
        var calls = new AtomicInteger();
        var delegate = ROIs.createRectangleROI(0, 0, 100, 100);
        var roi = (ROI)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ROI.class}, (proxy, method, args) -> {
            if (method.getName().equals("contains")) calls.incrementAndGet();
            return method.invoke(delegate, args);
        });
        var hierarchy = new PathObjectHierarchy();
        var annotation = PathObjects.createAnnotationObject(roi);
        var detection = PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 10, 10));
        hierarchy.addObjects(List.of(annotation, detection));
        assertEquals(List.of(detection), List.copyOf(PathObjectTools.getObjectsForLocation(hierarchy, PathDetectionObject.class, 10, 10, 0, 0, 0)));
        assertEquals(0, calls.get(), "Hover classification must not build annotation containment indexes");
        assertTrue(PathObjectTools.getObjectsForLocation(hierarchy, 10, 10, 0, 0, 0).contains(annotation));
        assertTrue(calls.get() > 0, "Unfiltered selection must still test annotations");
    }
    @Test void typedQueriesPreserveDetectionSubclassesPlanesAndVertexTolerance() {
        var hierarchy = new PathObjectHierarchy();
        hierarchy.addObjects(List.of(
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 100, 100)),
                PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 10, 10)),
                PathObjects.createCellObject(ROIs.createEllipseROI(5, 5, 10, 10), ROIs.createEllipseROI(8, 8, 4, 4), null, null),
                PathObjects.createTileObject(ROIs.createRectangleROI(20, 20, 10, 10)),
                PathObjects.createDetectionObject(ROIs.createPointsROI(30, 30)),
                PathObjects.createDetectionObject(ROIs.createRectangleROI(5, 5, 10, 10, ImagePlane.getPlane(1, 0)))));
        for (int z : List.of(0, 1)) for (double coordinate : List.of(10.0, 25.0, 30.5, 101.0)) for (double tolerance : List.of(-1.0, 0.0, 2.0)) {
            var expected = PathObjectTools.getObjectsForLocation(hierarchy, coordinate, coordinate, z, 0, tolerance)
                    .stream().filter(PathObject::isDetection).collect(java.util.stream.Collectors.toSet());
            assertEquals(expected, new HashSet<>(PathObjectTools.getObjectsForLocation(hierarchy, PathDetectionObject.class,
                    coordinate, coordinate, z, 0, tolerance)));
        }
    }
}
