plugins { `java-library` }

group = "org.locationtech.jts"
version = "1.20.0-qupath.1"
repositories { mavenCentral() }
java { toolchain.languageVersion = JavaLanguageVersion.of(25) }

// Resolve the original artifact without substituting this included build into itself.
val upstreamJts by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
    resolutionStrategy.useGlobalDependencySubstitutionRules = false
}
dependencies {
    upstreamJts("org.locationtech.jts:jts-core:1.20.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}
sourceSets.main { compileClasspath += upstreamJts }
sourceSets.test {
    compileClasspath += upstreamJts
    runtimeClasspath += upstreamJts
}
tasks.compileJava {
    options.release = 8
    options.encoding = "UTF-8"
}
tasks.jar {
    // Preserve every upstream class/resource except the one backported implementation.
    from({ zipTree(upstreamJts.singleFile) }) {
        exclude("org/locationtech/jts/operation/overlayng/PolygonBuilder.class", "META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
    manifest.attributes(
        "Automatic-Module-Name" to "org.locationtech.jts",
        "Implementation-Title" to "JTS 1.20.0 with indexed OverlayNG hole placement",
        "Implementation-Version" to project.version,
        "JTS-Upstream-Commit" to "c874270694e3985f9af29134d8828bd0cd557f9a"
    )
}
// Tests compare against the unmodified distribution, and inspect the packaged replacement.
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    dependsOn(tasks.jar)
    inputs.files(upstreamJts)
    doFirst {
        systemProperty("jts.upstream.jar", upstreamJts.singleFile.absolutePath)
        systemProperty("jts.patched.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    }
    testLogging.showStandardStreams = true
}
// Consumers need the complete replacement jar, not this project's one-class directory.
listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) { outgoing.variants.clear() }
}
