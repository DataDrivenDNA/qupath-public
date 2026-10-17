/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.ext.analysisqueue.workflow.AnalysisInput;
import qupath.lib.images.ImageData;
import qupath.lib.projects.Project;
import java.awt.image.BufferedImage;
import java.util.List;

/** Read editor-owned state only inside the supplied FX executor. */
public interface EditorAccess {
    Project<BufferedImage> project();
    List<ImageData<BufferedImage>> openImages(AnalysisInput input);
}
