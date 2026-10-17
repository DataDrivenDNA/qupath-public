package qupath.lib.projects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TestPreparedImageDataSave {
    @TempDir Path folder;
    private List<Path> targets() throws Exception {
        var paths = List.of(folder.resolve("data.qpdata"), folder.resolve("summary.json"), folder.resolve("server.json"));
        for (var p : paths) Files.writeString(p, "original " + p.getFileName());
        return paths;
    }
    private PreparedImageDataSave prepare(List<Path> paths, Runnable committed) throws Exception {
        var save = new PreparedImageDataSave(this, paths, committed);
        for (int i = 0; i < paths.size(); i++) Files.writeString(save.staged(i), "replacement " + i);
        return save;
    }
    @Test void preparationAndAbandonmentLeaveOriginalFilesUntouched() throws Exception {
        var paths = targets();
        Path stage;
        try (var save = prepare(paths, () -> fail("Must not commit"))) {
            stage = save.stagedPath().getParent();
            for (var p : paths) assertEquals("original " + p.getFileName(), Files.readString(p));
        }
        assertFalse(Files.exists(stage));
        for (var p : paths) assertEquals("original " + p.getFileName(), Files.readString(p));
    }
    @Test void commitInstallsPreparedBytesAndUpdatesEntryOnce() throws Exception {
        var paths = targets(); var committed = new AtomicBoolean();
        try (var save = prepare(paths, () -> committed.set(true))) {
            save.commit(); assertTrue(committed.get());
            assertThrows(IllegalStateException.class, save::commit);
        }
        for (int i = 0; i < paths.size(); i++) assertEquals("replacement " + i, Files.readString(paths.get(i)));
    }
    @Test void concurrentNativeSaveIsNeverOverwritten() throws Exception {
        var paths = targets();
        try (var save = prepare(paths, () -> fail("Must reject changed source"))) {
            Files.writeString(paths.getFirst(), "user saved a later annotation");
            assertFalse(save.sourceUnchanged()); assertThrows(IOException.class, save::commit);
        }
        assertEquals("user saved a later annotation", Files.readString(paths.getFirst()));
    }
    @Test void failureHalfwayThroughCommitRestoresEveryOriginalFile() throws Exception {
        var paths = targets();
        try (var save = prepare(paths, () -> fail("Must not finish failed save"))) {
            Files.delete(save.staged(1));
            assertThrows(IOException.class, save::commit);
        }
        for (var p : paths) assertEquals("original " + p.getFileName(), Files.readString(p));
    }
}
