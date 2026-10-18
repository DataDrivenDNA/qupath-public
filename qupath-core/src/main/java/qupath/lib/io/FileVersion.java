/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.lib.io;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Cheap native-file version for detecting concurrent replacements without content hashing. */
public record FileVersion(long size, Object modified, Object created, Object key) {
    /** @return null for an absent file or a storage backend without a local path. */
    public static FileVersion read(Path path) throws IOException {
        if (path == null) return null;
        try {
            var a = Files.readAttributes(path, BasicFileAttributes.class);
            return new FileVersion(a.size(), a.lastModifiedTime(), a.creationTime(), a.fileKey());
        } catch (NoSuchFileException absent) { return null; }
    }
}
