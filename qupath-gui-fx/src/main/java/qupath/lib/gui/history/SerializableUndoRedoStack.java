/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.lib.gui.history;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.MemoryTools;
import qupath.lib.io.PathIO;
import java.io.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serialize objects on request for use in undo/redo.
 *
 * @param <T>
 */
public class SerializableUndoRedoStack<T> {
    private static final Logger logger = LoggerFactory.getLogger(SerializableUndoRedoStack.class);
    private byte[] current;
    private final Deque<byte[]> undoStack = new ArrayDeque<>();
    private final Deque<byte[]> redoStack = new ArrayDeque<>();

    public SerializableUndoRedoStack(T object) {
        if (object != null) current = serializeObject(object, 1024);
    }
    public synchronized boolean canUndo() { return !undoStack.isEmpty(); }
    public synchronized boolean canRedo() { return !redoStack.isEmpty(); }
    synchronized int undoCount() { return undoStack.size(); }
    synchronized int redoCount() { return redoStack.size(); }
    synchronized int initialSize() {
        return current == null ? 1024 : (int)Math.min(Integer.MAX_VALUE - 64L, (long)(current.length * 1.1));
    }
    public synchronized long totalBytes() {
        long total = current == null ? 0 : current.length;
        for (var bytes : undoStack) total += bytes.length;
        for (var bytes : redoStack) total += bytes.length;
        return total;
    }
    public synchronized void clear() { current = null; undoStack.clear(); redoStack.clear(); }

    public T undoOnce() { return moveNow(false); }
    public T redoOnce() { return moveNow(true); }
    private T moveNow(boolean redo) {
        var move = prepareMove(redo);
        if (move == null) return null;
        T object = move.readObject();
        return object != null && commitMove(move) ? object : null;
    }

    public void addLatest(T object, int historySize) {
        addSerialized(serializeObject(object, initialSize()), historySize);
    }
    /** Only byte-array bookkeeping; serialization is completed before this lock. */
    synchronized void addSerialized(byte[] bytes, int historySize) {
        if (bytes == null) return;
        if (current != null) {
            if (MemoryTools.getTotalFreeMemory().orElse(MemoryTools.getCurrentFreeMemory()) < current.length * 1.5) undoStack.clear();
            else undoStack.push(current);
        }
        current = bytes;
        redoStack.clear();
        if (historySize > 0) while (undoStack.size() > historySize) undoStack.pollLast();
    }

    /** Prepare without mutating history; decode on a worker, then commit if still current. */
    synchronized Move<T> prepareMove(boolean redo) {
        var target = (redo ? redoStack : undoStack).peek();
        return target == null ? null : new Move<>(current, target, redo);
    }
    synchronized boolean commitMove(Move<T> move) {
        var from = move.redo ? redoStack : undoStack;
        var to = move.redo ? undoStack : redoStack;
        if (current != move.previous || from.peek() != move.target) return false;
        from.pop();
        if (current != null) to.push(current);
        current = move.target;
        return true;
    }
    static final class Move<T> {
        private final byte[] previous, target;
        private final boolean redo;
        private Move(byte[] previous, byte[] target, boolean redo) {
            this.previous = previous; this.target = target; this.redo = redo;
        }
        T readObject() { return deserialize(target); }
    }

    static byte[] serializeObject(Object object, int initialSize) {
        try (var stream = new ByteArrayOutputStream(initialSize); var out = new ObjectOutputStream(stream)) {
            out.writeObject(object); out.flush(); return stream.toByteArray();
        } catch (IOException error) { logger.error("Error serializing undo history", error); return null; }
    }
    @SuppressWarnings("unchecked")
    private static <T> T deserialize(byte[] bytes) {
        try (var stream = new ByteArrayInputStream(bytes); var in = PathIO.createObjectInputStream(stream)) {
            return (T)in.readObject();
        } catch (ClassNotFoundException | IOException error) { logger.error("Error reading undo history", error); return null; }
    }
}
