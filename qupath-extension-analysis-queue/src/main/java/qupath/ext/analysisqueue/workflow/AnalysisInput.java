/* SPDX-License-Identifier: GPL-3.0-or-later */
package qupath.ext.analysisqueue.workflow;

import java.util.List;

/** Frozen source identity and settings for a queued SYNJ1 job. */
public record AnalysisInput(String projectPath, String entryId, String imageName, String savedPath,
                            String savedSha256, List<String> regionIds, String sourceSignature,
                            String classifierSha256, String outputDirectory) {}
