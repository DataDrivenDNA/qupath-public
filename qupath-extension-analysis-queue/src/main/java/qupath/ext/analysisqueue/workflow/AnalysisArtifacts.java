/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.images.ImageData;
import qupath.lib.io.PathIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native readback and full-precision CSV export, limited to the queued regions. */
public final class AnalysisArtifacts {
    public void write(Path folder, ImageData<BufferedImage> data, List<String> ids) throws Exception {
        Files.createDirectories(folder);
        var nativeFile = folder.resolve("result.qpdata");
        PathIO.writeImageData(nativeFile, data);
        var saved = PathIO.readImageData(nativeFile, data.getServer()); // Borrow worker-owned server.
        if (!SourceSignature.compute(data, ids).equals(SourceSignature.compute(saved, ids)))
            throw new IllegalStateException("Native result readback did not match the processed regions.");
        var annotations = SourceSignature.roots(saved, ids).stream().flatMap(p -> SourceSignature.branch(p).stream())
                .filter(p -> p.isAnnotation()).distinct().toList();
        var table = new ObservableMeasurementTableData(); table.setImageData(saved, annotations);
        var names = new ArrayList<>(table.getAllNames().stream().filter(n -> !n.startsWith("(Live)")).toList());
        for (var key : List.of(Synj1Analysis.dabKey("Mean"), Synj1Analysis.dabKey("Std.dev."))) if (!names.contains(key)) names.add(key);
        var lines = new ArrayList<String>(); lines.add(csv(names));
        for (var annotation : annotations) {
            var values = new ArrayList<String>();
            for (var name : names) {
                if (table.isNumericMeasurement(name)) {
                    double value = table.getNumericValue(annotation, name);
                    values.add(Double.isFinite(value) ? Double.toString(value) : "");
                } else if (table.getAllNames().contains(name)) values.add(table.getStringValue(annotation, name));
                else values.add("");
            }
            lines.add(csv(values));
        }
        Files.writeString(folder.resolve("measurements.csv"), "\uFEFF" + String.join("\r\n", lines) + "\r\n");
    }
    private static String csv(Collection<String> values) {
        return values.stream().map(v -> "\"" + Objects.toString(v, "").replace("\"", "\"\"") + "\"")
                .collect(java.util.stream.Collectors.joining(","));
    }
}
