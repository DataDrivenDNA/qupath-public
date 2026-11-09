/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import qupath.ext.analysisqueue.persistence.JsonFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** A worker computes the checksum; FX only checks the inexpensive file version. */
final class SavedFile {
    record Version(long size, Object modified, Object created, Object key) {
        static Version read(Path path) throws IOException {
            var a = Files.readAttributes(path, BasicFileAttributes.class);
            return new Version(a.size(), a.lastModifiedTime(), a.creationTime(), a.fileKey());
        }
        boolean matches(Path path) throws IOException { return equals(read(path)); }
    }
    record Checked(Version version, String sha256) {}
    static Checked check(Path path) throws IOException {
        var before = Version.read(path);
        var hash = JsonFiles.sha256(path);
        if (!before.matches(path)) throw new IOException("Saved image changed while being checked; add its latest outline again.");
        return new Checked(before, hash);
    }
}
