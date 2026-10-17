plugins {
    id("qupath.extension-conventions")
    id("qupath.javafx-conventions")
    `java-library`
}

extra["moduleName"] = "qupath.extension.recorder"
base {
    archivesName = "qupath-extension-recorder"
    description = "Local QuPath session recording for reproducible automation."
}

tasks.jar {
    manifest.attributes("Implementation-Version" to "1.1.0")
}
