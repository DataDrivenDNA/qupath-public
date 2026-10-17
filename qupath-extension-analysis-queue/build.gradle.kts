plugins {
    id("qupath.extension-conventions")
    id("qupath.javafx-conventions")
    `java-library`
}
extra["moduleName"] = "qupath.extension.analysis.queue"
base {
    archivesName = "qupath-extension-analysis-queue"
    description = "Background analysis queue for reviewed QuPath regions."
}
tasks.jar { manifest.attributes("Implementation-Version" to "1.1.0") }
