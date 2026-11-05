// Shared configuration lives in build-logic convention plugins (plexus.java-conventions, plexus.minecraft-dev).
// The root project lists the supported Minecraft versions and adds new ones (ADR-0011), starts new
// mod projects from templates/plexus-mod-template (newPlexusMod), publishes the mod SDK for the website's
// Maven repository (publishSdk, ADR-0008), and builds the test mods for the version a build runs (below).
plugins {
    id("plexus.minecraft-versions")
    id("plexus.mod-template")
    id("plexus.sdk-repository")
}

// The test mods and the transform example are written for Minecraft 26.2 and name it in their metadata
// (fabric.mod.json "~26.2", plexus.mod.json "26.2" for exact transform targets, quilt.mod.json "26.2.x"). They are built for the
// version this build runs (-PplexusMinecraft, ADR-0011), so the verification tasks check every supported
// version with the same mods; the game members they use were checked with javap in each one. With the
// default version nothing changes.
val runMinecraft = dev.plexusmodloader.devtools.gradle.minecraft.MinecraftVersions.of(project).selected(project)
val writtenFor = Regex("""(?<![\d.])26\.2(?![\d.])""")
// The examples that use only the Native API declare "26.*". A run of an experimental version (ADR-0019, such as
// 1.20.1) adds that version to their range in the built jar, so the same mods check its game layer; the
// sources and the published examples keep "26.*", and supported versions build them unchanged.
val runsExperimental = dev.plexusmodloader.devtools.gradle.minecraft.MinecraftVersions.of(project).isExperimental(runMinecraft)
val runsPre26 = runMinecraft.startsWith("1.")
val apiOnlyExamples = setOf(
    ":examples:minimal-native-mod",
    ":examples:custom-system-mod",
    ":examples:native-showcase-mod",
    // Native API-only test mods that declare "26.*" too, for the compatibility matrix.
    ":test-mods:native-addon",
    ":test-mods:native-library",
)
subprojects {
    if ((runsExperimental || runsPre26) && path in apiOnlyExamples) {
        plugins.withId("java") {
            tasks.named<ProcessResources>("processResources") {
                inputs.property("plexusMinecraft", runMinecraft)
                // Gradle does not track the filter itself; this input reruns the task when the range changes.
                inputs.property("plexusMinecraftRange", "\"26.*\", \"$runMinecraft\"")
                filesMatching("plexus.mod.json") {
                    filter { line -> line.replace("\"26.*\"", "\"26.*\", \"$runMinecraft\"") }
                }
                // project.path: inside the task's block, path is the task's own path.
                if (runsPre26 && project.path == ":examples:native-showcase-mod") {
                    // The showcase ships data in the 26.x format. A version with its own folder under
                    // plexus-bootstrap/compat/legacy-showcase-data gets those files in its format instead (a version's
                    // data pack format differs: 1.21.1 has no environment attributes, other noise router keys, ...).
                    val legacyData = rootProject.file("plexus-bootstrap/compat/legacy-showcase-data/$runMinecraft")
                    if (legacyData.isDirectory) {
                        inputs.dir(legacyData)
                        // Added last, so for a path both have, the legacy file is the one that stays in the jar.
                        duplicatesStrategy = DuplicatesStrategy.INCLUDE
                        from(legacyData)
                    } else {
                        // 1.21.11 reads the 26.x files but for one environment attribute it does not have.
                        filesMatching("data/native_showcase/dimension_type/pocket.json") {
                            filter { line -> if (line.contains("ambient_light_color")) "" else line }
                        }
                    }
                }
            }
        }
    }
    if (path.startsWith(":test-mods:") || path == ":examples:transform-demo-mod") {
        plugins.withId("java") {
            tasks.named<ProcessResources>("processResources") {
                inputs.property("plexusMinecraft", runMinecraft)
                filesMatching(listOf("fabric.mod.json", "plexus.mod.json")) {
                    filter { line -> line.replace(writtenFor, runMinecraft) }
                }
                // quilt.mod.json names the version Quilt's way, "26.2.x" (26.2 and its hotfixes); a run of a version
                // outside that range names exactly that version.
                if (runMinecraft != "26.2" && !runMinecraft.startsWith("26.2.")) {
                    filesMatching("quilt.mod.json") {
                        filter { line -> line.replace("\"26.2.x\"", "\"=$runMinecraft\"") }
                    }
                }
                // fabric_old asks for "~1.21.4", which a 1.21.x run would meet; there it asks for 1.20.4 so it stays blocked.
                // project.path: inside the task's block, path is the task's own path.
                if (project.path == ":test-mods:fabric-wrong-version" && runMinecraft.startsWith("1.21")) {
                    inputs.property("fabricOldMinecraft", "~1.20.4")
                    filesMatching("fabric.mod.json") {
                        filter { line -> line.replace("~1.21.4", "~1.20.4") }
                    }
                }
            }
        }
    }
}
