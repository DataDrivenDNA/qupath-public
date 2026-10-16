/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Local modifications to QuPath; see LICENSE.
 */

package qupath.lib.gui.tools;

import javafx.application.Platform;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** JavaFX toolkit only: no windows, desktop input or foreground activation. */
public final class FxTestTools {
    private static boolean started;

    private FxTestTools() {}

    public static synchronized void start() throws Exception {
        if (started)
            return;
        var ready = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                ready.countDown();
            });
        } catch (IllegalStateException alreadyRunning) {
            Platform.runLater(ready::countDown);
        }
        if (!ready.await(10, TimeUnit.SECONDS))
            throw new AssertionError("JavaFX did not start");
        started = true;
    }

    public static <T> T call(Callable<T> action) throws Exception {
        start();
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(2, TimeUnit.SECONDS);
    }
}
