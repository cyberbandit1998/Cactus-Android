import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "app.cactusai"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Host-side check of the JNI shim: builds native/host-test (the shim linked to a TEST DOUBLE of the
// engine's C ABI) and hands its path to the tests. Needs cmake, a C++ compiler and a JDK; on a machine
// without them (or on Windows) the shim tests are skipped, never failed.
val hostShimDir = layout.buildDirectory.dir("host-shim")
val hostShimSource = layout.projectDirectory.dir("../native/host-test")
val canBuildHostShim = !System.getProperty("os.name").lowercase().contains("win")

val buildHostShim by tasks.registering(Exec::class) {
    onlyIf { canBuildHostShim }
    inputs.dir(hostShimSource)
    inputs.dir(layout.projectDirectory.dir("../native/shim"))
    outputs.dir(hostShimDir)
    isIgnoreExitValue = true
    environment("JAVA_HOME", System.getProperty("java.home"))
    val out = hostShimDir.get().asFile.path
    commandLine(
        "sh", "-c",
        "cmake -S '${hostShimSource.asFile.path}' -B '$out' -DCMAKE_BUILD_TYPE=Release > '$out.log' 2>&1 && " +
            "cmake --build '$out' >> '$out.log' 2>&1 || echo 'host JNI shim not built (see $out.log); shim tests will be skipped'",
    )
}

tasks.test {
    dependsOn(buildHostShim)
    doFirst {
        val lib = hostShimDir.get().asFile.listFiles { f -> f.name.startsWith("libcactusai_native_hosttest") }?.firstOrNull()
        if (lib != null) systemProperty("cactusai.hostShim", lib.absolutePath)
    }
    useJUnit()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
