/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.measure;

import javafx.beans.value.ObservableValue;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.tools.BackgroundCache;
import qupath.lib.lazy.interfaces.LazyNumericValue;
import qupath.lib.lazy.interfaces.LazyValue;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ml.pixel.PixelClassificationMeasurementManager;

import java.util.Map;
import java.util.HashMap;
import java.lang.ref.WeakReference;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns the asynchronous presentation of one classifier's live measurements. */
final class LivePixelMeasurements {

    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2,
            30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            ThreadTools.createThreadFactory("live-measurements", true, Thread.NORM_PRIORITY - 1));

    static {
        WORKERS.allowCoreThreadTimeOut(true);
    }

    private record Request(PathObject object, ROI roi) {}
    private record Result(Map<String, Number> values, long eventCount) {}

    private final PixelClassificationMeasurementManager manager;
    private final PathObjectHierarchy hierarchy;
    private final BackgroundCache<Request, Result> cache = new BackgroundCache<>(WORKERS);
    private final Map<PathObject, Request> currentRequests = new HashMap<>();
    private final PathObjectHierarchyListener listener;
    private boolean disposed;
    private boolean listening;

    LivePixelMeasurements(PixelClassificationMeasurementManager manager, PathObjectHierarchy hierarchy) {
        this.manager = manager;
        this.hierarchy = hierarchy;
        var reference = new WeakReference<>(this);
        listener = event -> {
            var owner = reference.get();
            if (owner != null)
                owner.cache.requestRefresh();
        };
    }

    LazyValue<PathObject, Number> createValue(String name) {
        return new Value(name);
    }

    private Number getValueForDisplay(PathObject object, String name) {
        if (disposed)
            return null;
        // Synchronous/script-only models must not initialize or depend on JavaFX.
        if (!listening) {
            hierarchy.addListener(listener);
            listening = true;
        }
        var request = new Request(object, object.getROI());
        if (request.roi() == null && !object.isRootObject())
            return null;
        var previous = currentRequests.put(object, request);
        if (previous != null && !previous.equals(request))
            cache.invalidate(previous);
        long eventCount = hierarchy.getEventCount();
        // Snapshot the ROI so editing the object cannot change a queued calculation.
        var snapshot = object.isRootObject() ? object : PathObjects.createAnnotationObject(request.roi());
        var result = cache.get(request, () -> new Result(manager.getCachedMeasurements(snapshot), eventCount));
        if (result != null && result.values().isEmpty() && result.eventCount() != eventCount) {
            // Previously missing tiles may now be cached. Completed non-empty results remain valid for this ROI.
            cache.invalidate(request);
            result = cache.get(request, () -> new Result(manager.getCachedMeasurements(snapshot), eventCount));
        }
        return result == null ? null : result.values().get(name);
    }

    private void dispose() {
        disposed = true;
        hierarchy.removeListener(listener);
        cache.clear();
        currentRequests.clear();
    }

    private final class Value implements LazyNumericValue<PathObject>, ObservableLazyValue<PathObject, Number> {
        private final String name;
        private final LazyNumericValue<PathObject> display;

        private Value(String name) {
            this.name = name;
            display = LazyValue.createNumeric(getName(), getHelpText(), this::getValueForDisplay);
        }

        @Override public String getName() { return "(Live)" + name; }
        @Override public String getHelpText() { return "A temporary measurement calculated in the background by the current pixel classifier"; }
        @Override public Number getValue(PathObject object) { return manager.getCachedMeasurementValue(object, name); }
        @Override public Number getValueForDisplay(PathObject object) { return LivePixelMeasurements.this.getValueForDisplay(object, name); }
        @Override public String getStringValueForDisplay(PathObject object, int decimalPlaces) { return display.getStringValue(object, decimalPlaces); }
        @Override public ObservableValue<?> getObservable() { return cache.revisionProperty(); }
        @Override public void cancelPending() { dispose(); }
    }
}
