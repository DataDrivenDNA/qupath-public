/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.recorder;

import org.locationtech.jts.io.WKBWriter;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.objects.hierarchy.events.PathObjectSelectionListener;
import qupath.lib.plugins.workflow.ScriptableWorkflowStep;
import qupath.lib.plugins.workflow.WorkflowListener;
import qupath.lib.roi.interfaces.ROI;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Captures domain state, independent of interface labels and mouse coordinates. */
final class ImageRecording implements AutoCloseable {
    private final ImageData<BufferedImage> data;
    private final RecordWriter writer;
    private final String imageId;
    private final Runnable changed;
    // ROI.hashCode may traverse millions of coordinates. Native ROIs are immutable;
    // reuse by identity and release replaced ROIs after each snapshot instead.
    private final Map<ROI, String> geometries = new IdentityHashMap<>();
    private final Map<UUID, Map<String, Object>> previous = new HashMap<>();
    private int geometryNumber;
    private String lastSavedPath;
    private boolean lastChanged;
    private final PathObjectHierarchyListener hierarchyListener;
    private final PathObjectSelectionListener selectionListener;
    private final WorkflowListener workflowListener;
    private final PropertyChangeListener propertyListener;

    ImageRecording(ImageData<BufferedImage> data, RecordWriter writer, String imageId, Runnable changed) {
        this.data = data;
        this.writer = writer;
        this.imageId = imageId;
        this.changed = changed;
        hierarchyListener = event -> writer.observe("hierarchy", () -> {
            writer.event("hierarchy.event", Map.of("image", imageId, "kind", event.getEventType().name(),
                    "changing", event.isChanging(), "objects", event.getChangedObjects().stream().map(p -> p.getID().toString()).toList()));
            if (!event.isChanging()) { snapshotAnnotations(); changed.run(); }
        });
        selectionListener = (selected, old, all) -> writer.observe("selection", () -> {
            writer.event("selection", Map.of("image", imageId, "primary", selected == null ? "" : selected.getID().toString(),
                    "objects", all.stream().map(p -> p.getID().toString()).toList()));
            changed.run();
        });
        workflowListener = workflow -> writer.observe("workflow", () -> { workflow(); changed.run(); });
        propertyListener = event -> writer.observe("image.property", () -> {
            // Values may be servers or native objects; log their type, then the canonical image settings.
            writer.event("image.property", Map.of("image", imageId, "property", event.getPropertyName()));
            settings(); changed.run();
        });
        data.getHierarchy().addListener(hierarchyListener);
        data.getHierarchy().getSelectionModel().addPathObjectSelectionListener(selectionListener);
        data.getHistoryWorkflow().addWorkflowListener(workflowListener);
        data.addPropertyChangeListener(propertyListener);
        lastSavedPath = data.getLastSavedPath(); lastChanged = data.isChanged();
        writer.observe("image.baseline", () -> { settings(); workflow(); snapshotAnnotations(); });
    }

    synchronized void pollSavedState() {
        if (!Objects.equals(lastSavedPath, data.getLastSavedPath()) || lastChanged != data.isChanged()) {
            lastSavedPath = data.getLastSavedPath(); lastChanged = data.isChanged();
            var state = new LinkedHashMap<String, Object>();
            state.put("image", imageId); state.put("savedPath", lastSavedPath); state.put("unsaved", lastChanged);
            writer.event("image.save_state", state);
            changed.run();
        }
    }

    private synchronized void settings() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("image", imageId);
        fields.put("imageType", data.getImageType().toString());
        fields.put("savedPath", data.getLastSavedPath());
        fields.put("unsaved", data.isChanged());
        fields.put("stains", data.getColorDeconvolutionStains() == null ? null : data.getColorDeconvolutionStains().toString());
        var server = data.getServer();
        fields.put("width", server.getWidth()); fields.put("height", server.getHeight());
        fields.put("pixelWidthMicrons", server.getPixelCalibration().getPixelWidthMicrons());
        fields.put("pixelHeightMicrons", server.getPixelCalibration().getPixelHeightMicrons());
        writer.event("image.settings", fields);
    }

    private synchronized void workflow() {
        var steps = new ArrayList<Map<String, Object>>();
        var script = new StringBuilder("// Recorded QuPath command history; review selections and manual steps before replay.\n");
        for (var step : data.getHistoryWorkflow().getSteps()) {
            var item = new LinkedHashMap<String, Object>();
            item.put("name", step.getName());
            item.put("parameters", new LinkedHashMap<>(step.getParameterMap()));
            if (step instanceof ScriptableWorkflowStep executable) {
                item.put("script", executable.getScript());
                script.append(executable.getScript()).append('\n');
            } else script.append("// Manual/non-scriptable: ").append(step.getName().replace('\n', ' ')).append('\n');
            steps.add(item);
        }
        writer.event("workflow", Map.of("image", imageId, "steps", steps));
        String text = script.toString();
        writer.asset("workflow.script", "workflows/" + imageId + ".groovy", Map.of("image", imageId),
                path -> Files.writeString(path, text));
    }

    synchronized void snapshotAnnotations() {
        var current = new HashSet<UUID>();
        var currentRois = Collections.newSetFromMap(new IdentityHashMap<ROI, Boolean>());
        for (var object : data.getHierarchy().getAnnotationObjects()) {
            if (object.getROI() != null) currentRois.add(object.getROI());
            var state = state(object);
            current.add(object.getID());
            if (!state.equals(previous.get(object.getID()))) {
                writer.event("annotation.state", state);
                previous.put(object.getID(), state);
            }
        }
        geometries.keySet().removeIf(roi -> !currentRois.contains(roi));
        for (var id : new HashSet<>(previous.keySet())) {
            if (!current.contains(id)) {
                writer.event("annotation.removed", Map.of("image", imageId, "id", id.toString()));
                previous.remove(id);
            }
        }
        writer.event("hierarchy.summary", Map.of("image", imageId, "annotations", current.size(),
                "totalObjects", data.getHierarchy().nObjects()));
    }

    private Map<String, Object> state(PathObject object) {
        var state = new LinkedHashMap<String, Object>();
        state.put("image", imageId); state.put("id", object.getID().toString());
        state.put("parent", object.getParent() == null ? null : object.getParent().getID().toString());
        state.put("name", object.getName());
        state.put("classification", object.getPathClass() == null ? null : object.getPathClass().toString());
        state.put("locked", object.isLocked()); state.put("color", object.getColor());
        state.put("measurements", new LinkedHashMap<>(object.getMeasurementList().asMap()));
        var roi = object.getROI();
        if (roi != null) {
            String geometry = geometries.get(roi);
            if (geometry == null) {
                geometry = "geometry/" + imageId + "-" + (++geometryNumber) + ".wkb.gz";
                geometries.put(roi, geometry);
                writer.asset("annotation.geometry", geometry, Map.of("image", imageId, "id", object.getID().toString()), path -> {
                    try (var output = new GZIPOutputStream(Files.newOutputStream(path))) {
                        output.write(new WKBWriter().write(roi.getGeometry()));
                    }
                });
            }
            state.put("geometry", geometry);
            state.put("roiType", roi.getRoiName());
            state.put("plane", Map.of("c", roi.getC(), "z", roi.getZ(), "t", roi.getT()));
            state.put("bounds", List.of(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight()));
        }
        return state;
    }

    @Override public void close() {
        data.getHierarchy().removeListener(hierarchyListener);
        data.getHierarchy().getSelectionModel().removePathObjectSelectionListener(selectionListener);
        data.getHistoryWorkflow().removeWorkflowListener(workflowListener);
        data.removePropertyChangeListener(propertyListener);
        geometries.clear();
        writer.event("image.detach", Map.of("image", imageId));
    }
}
