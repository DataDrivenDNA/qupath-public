/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private JsonFiles() {}
    public static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(value));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static <T> T read(Path file, Class<T> type) throws IOException {
        try { return GSON.fromJson(Files.readString(file), type); }
        catch (RuntimeException e) { throw new IOException("Cannot read " + file, e); }
    }
    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String sha256(Path file) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = input.read(buffer)) >= 0) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
