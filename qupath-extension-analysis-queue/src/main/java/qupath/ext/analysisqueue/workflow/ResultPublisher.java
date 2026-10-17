/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import qupath.lib.images.ImageData;
import java.awt.image.BufferedImage;

@FunctionalInterface
public interface ResultPublisher {
    /** Null means saved and verified; a message means the original was retained for review. */
    String publish(AnalysisInput input, ImageData<BufferedImage> result) throws Exception;
}
