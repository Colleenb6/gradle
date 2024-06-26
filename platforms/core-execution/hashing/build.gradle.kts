plugins {
    id("gradlebuild.distribution.api-java")
    id("gradlebuild.publish-public-libraries")
}

description = "Tools for creating secure hashes for files and other content"

gradlebuildJava.usedInWorkers() // org.gradle.internal.nativeintegration.filesystem.Stat is used in workers

errorprone {
    disabledChecks.addAll(
        "ReturnValueIgnored", // 1 occurrences
    )
}

dependencies {
    api(projects.javaLanguageExtensions)

    implementation(libs.guava)
    api(libs.jsr305)
}
