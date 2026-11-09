/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.qupath;

import javafx.application.Platform;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public interface FxExecutor {
    <T> T call(Callable<T> operation) throws Exception;
    static FxExecutor application() {
        return new FxExecutor() {
            @Override public <T> T call(Callable<T> operation) throws Exception {
                if (Platform.isFxApplicationThread()) return operation.call();
                var result = new CompletableFuture<T>();
                Platform.runLater(() -> { try { result.complete(operation.call()); } catch (Throwable e) { result.completeExceptionally(e); } });
                // A save may wait behind a user dialog. Do not report failure while its
                // already-dispatched mutation could still run later on the FX thread.
                return result.get();
            }
        };
    }
    static FxExecutor direct() { return new FxExecutor() { public <T> T call(Callable<T> operation) throws Exception { return operation.call(); } }; }
}
