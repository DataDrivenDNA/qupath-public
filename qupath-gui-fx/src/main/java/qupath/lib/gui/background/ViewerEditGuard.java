/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.background;

import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageDataSnapshot;

/** Drawing tools can hold uncommitted geometry outside the persisted hierarchy. */
public final class ViewerEditGuard {
    private ViewerEditGuard() {}
    public static boolean isEditing(QuPathViewer viewer) {
        var editor = viewer.getROIEditor();
        var data = viewer.getImageData();
        return editor.isTranslating() || editor.hasActiveHandle()
                || (data != null && ImageDataSnapshot.hasUncommittedObjects(data));
    }
}
