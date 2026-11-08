plugins {
    id("plexus.java-conventions")
    id("plexus.minecraft-compile")
}

description = "Game layer for Minecraft 1.20.4: implements the Native API with game classes; loaded by the game class loader."

// HostPayloadHooks' hooks receive NeoForge 20.4's NetworkRegistry (their rules target its instance methods); the
// game layer compiles against this empty stand-in, and the real class links at run time. Not in the jar.
val neoForgeStubs by sourceSets.creating

dependencies {
    implementation(project(":plexus-version-adapter-api"))
    implementation(project(":plexus-bridge-api"))
    implementation(libs.slf4j.api)
    compileOnly(neoForgeStubs.output)
    // BuiltInRulesTest plans and applies the adapter's rules to the real game classes on the test class
    // path, so client-only rules are checked without starting a client.
    testImplementation(project(":plexus-version-adapter-v1_20_4"))
    testImplementation(project(":plexus-transform-engine"))
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)
}

// The game jar references JetBrains annotations that are not on the class path; javac warns about
// every use (classfile lint), which -Werror would turn into errors.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
}

// Game classes log to logs/ under the working directory; keep that out of the source tree.
tasks.withType<Test>().configureEach {
    val testRun = layout.buildDirectory.dir("test-run")
    doFirst { testRun.get().asFile.mkdirs() }
    workingDir = testRun.get().asFile
}
