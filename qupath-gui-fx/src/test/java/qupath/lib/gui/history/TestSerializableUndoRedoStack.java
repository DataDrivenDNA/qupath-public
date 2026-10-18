/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.gui.history;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestSerializableUndoRedoStack {
    @Test void editsUndoRedoAndHistoryLimitRetainNativeOrder() {
        var history = new SerializableUndoRedoStack<>("initial");
        history.addLatest("one", 2);
        history.addLatest("two", 2);
        history.addLatest("three", 2);
        assertEquals("two", history.undoOnce());
        assertEquals("one", history.undoOnce());
        assertNull(history.undoOnce());
        assertEquals("two", history.redoOnce());
        history.addLatest("new edit", 2);
        assertFalse(history.canRedo());
        assertEquals("two", history.undoOnce());
        assertTrue(history.totalBytes() > 0);
        history.clear();
        assertEquals(0, history.totalBytes());
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }
}
