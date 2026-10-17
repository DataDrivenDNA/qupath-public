/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */
package qupath.lib.gui.measure;

import javafx.scene.control.TextField;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import qupath.lib.algorithms.IntensityFeaturesPlugin;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.FxTestTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.plugins.TaskRunnerUtils;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TestIntensityResolutionWorkflow {

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "da-DK"})
    void halfMicronInputReachesImageReadsAndMeasurementNames(String localeTag) throws Exception {
        FxTestTools.call(() -> { PathPrefs.defaultLocaleFormatProperty(); return null; });
        var previousLocale = Locale.getDefault(Locale.Category.FORMAT);
        var locale = Locale.forLanguageTag(localeTag);
        var requests = Collections.synchronizedList(new ArrayList<Double>());
        var pixels = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++)
            for (int x = 0; x < 64; x++)
                pixels.setRGB(x, y, 0xffc09060);
        try (var server = new WrappedBufferedImageServer("intensity-test", pixels) {
            @Override public BufferedImage readRegion(RegionRequest request) throws IOException {
                requests.add(request.getDownsample());
                return super.readRegion(request);
            }
        }) {
            Locale.setDefault(Locale.Category.FORMAT, locale);
            server.setMetadata(new ImageServerMetadata.Builder(server.getMetadata())
                    .pixelSizeMicrons(0.25, 0.25).build());
            var data = new ImageData<>(server, ImageData.ImageType.BRIGHTFIELD_H_DAB);
            var annotation = PathObjects.createAnnotationObject(ROIs.createRectangleROI(0, 0, 64, 64));
            data.getHierarchy().addObject(annotation);
            data.getHierarchy().getSelectionModel().setSelectedObject(annotation);
            var plugin = new IntensityFeaturesPlugin();
            var params = plugin.getDefaultParameterList(data);
            for (var key : List.of("colorStain2", "doMean", "doStdDev"))
                assertTrue(params.getParameters().get(key).setStringLastValue(Locale.US, "true"));
            var json = FxTestTools.call(() -> {
                var panel = new ParameterPanelFX(params);
                var input = (TextField)panel.getPane().lookup("#pixelSizeMicrons");
                assertNotNull(input);
                input.setText(localeTag.equals("en-US") ? "0.5" : "0,5");
                assertEquals(0.5, params.getDoubleParameterValue("pixelSizeMicrons"));
                return ParameterList.convertToJson(params);
            });
            assertTrue(plugin.runPlugin(TaskRunnerUtils.getDefaultInstance().createHeadlessTaskRunner(1), data, json));
            assertFalse(requests.isEmpty());
            assertTrue(requests.stream().allMatch(downsample -> downsample == 2.0),
                    "0.5 microns on a 0.25 micron source must read at downsample 2");
            var prefix = String.format(locale, "ROI: %.2f µm per pixel: DAB: ", 0.5);
            assertTrue(annotation.getMeasurementList().containsKey(prefix + "Mean"));
            assertTrue(annotation.getMeasurementList().containsKey(prefix + "Std.dev."));
            assertTrue(Double.isFinite(annotation.getMeasurementList().get(prefix + "Mean")));
            assertEquals(1, data.getHierarchy().nObjects());
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previousLocale);
        }
    }
}
