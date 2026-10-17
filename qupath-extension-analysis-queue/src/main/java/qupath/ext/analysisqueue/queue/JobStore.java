/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.queue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface JobStore extends AutoCloseable {
    List<Job> load() throws IOException;
    void save(Job job) throws IOException;
    Path directory(String id);
    @Override void close() throws IOException;
}
