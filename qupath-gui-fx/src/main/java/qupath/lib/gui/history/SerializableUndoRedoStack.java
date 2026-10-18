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
import qupath.lib.common.GeneralTools;
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
	
	private byte[] current = null;
	private Deque<byte[]> undoStack = new ArrayDeque<>();
	private Deque<byte[]> redoStack = new ArrayDeque<>();
	
	public SerializableUndoRedoStack(T object) {
		if (object != null)
			current = serialize(object, 1024);
	}
	
	/**
	 * Returns true if the undo stack is not empty.
	 * @return
	 */
	public boolean canUndo() {
		return !undoStack.isEmpty();
	}
	
	/**
	 * Returns true if the redo stack is not empty.
	 * @return
	 */
	public boolean canRedo() {
		return !redoStack.isEmpty();
	}
	
	/**
	 * Get the total number of allocated bytes.
	 * @return
	 */
	public synchronized long totalBytes() {
		long total = 0L;
		if (current != null)
			total = current.length;
		for (byte[] bytes : undoStack)
			total += bytes.length;
		for (byte[] bytes : redoStack)
			total += bytes.length;
		return total;
	}
	
	/**
	 * Clear the undo and redo stacks, in addition to the current bytes.
	 */
	public synchronized void clear() {
		current = null;
		undoStack.clear();
		redoStack.clear();
	}
	
	/**
	 * Request redo once, updating the current (serialized) object, 
	 * and return the deserialized version of the object at the top of the 'redo' stack.
	 * @return
	 */
	public synchronized T redoOnce() {
		if (redoStack.isEmpty()) {
			logger.debug("Cannot redo! Stack is empty.");
			return null;
		}
		if (current != null) {
			undoStack.push(current);
		}
		current = redoStack.pop();
		return deserialize(current);
	}
	
	/**
	 * Request undo once, updating the current (serialized) object, 
	 * and return the deserialized version of the object at the top of the 'undo' stack.
	 * @return
	 */
	public synchronized T undoOnce() {
		if (undoStack.isEmpty()) {
			logger.debug("Cannot undo! Stack is empty.");
			return null;
		}
		if (current != null) {
			redoStack.push(current);
		}
		current = undoStack.pop();
		return deserialize(current);
	}
	
	/**
	 * Record a new change event, updating the current object.
	 * This will clear any redo status, on the assumption that redo is no longer possible.
	 * 
	 * @param object
	 * @param historySize
	 */
	public synchronized void addLatest(final T object, int historySize) {
		int initialSize = 1024;
		if (current != null) {
			// If we are low on memory, clear the undo stack
			long remainingMemory = GeneralTools.estimateAvailableMemory();
			if (remainingMemory < current.length * 1.5) {
				undoStack.clear();
			} else {
				// Default to something a bit bigger than the last things we had
				initialSize = Math.min(Integer.MAX_VALUE-64, (int)(current.length * 1.1));
				undoStack.push(current);
			}
		}
		current = serialize(object, initialSize);
		// Reset the ability to redo
		redoStack.clear();
		// Check the history size
		if (historySize > 0) {
			while (undoStack.size() > historySize)
				undoStack.pollLast();
		}
	}
	
	/**
	 * Serialize an object to an array of bytes.
	 * Providing an estimate of the initial array size can help performance.
	 * 
	 * @param object
	 * @param initialSize
	 * @return
	 */
	private byte[] serialize(T object, int initialSize) {
		try (ByteArrayOutputStream stream = new ByteArrayOutputStream(initialSize)) {
			ObjectOutputStream out = new ObjectOutputStream(stream);
			out.writeObject(object);
			out.flush();
			return stream.toByteArray();
		} catch (IOException e) {
			logger.error("Error serializing " + object, e);
			return null;
		}
	}
	
	/**
	 * Deserialize an object from an array of bytes.
	 * It's assumed at the object is of the generic type used elsewhere in this class.
	 * 
	 * @param bytes
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private T deserialize(byte[] bytes) {
		try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
			ObjectInputStream in = PathIO.createObjectInputStream(stream);
			return (T)in.readObject();
		} catch (ClassNotFoundException | IOException e) {
			logger.error("Error deserializing object", e);
			return null;
		}
	}
	
}

