import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

plugins {
    id("plexus.java-conventions")
}

description = "End-user installer: adds a Plexus Mod Loader profile to the Minecraft Launcher (PlexusModLoader-<version>.jar)."

// The installer runs outside the game, on whatever Java the user has; Java 17 is common enough.
tasks.compileJava {
    options.release = 17
}

dependencies {
    implementation(libs.gson)
    // Remaps the game jar of an obfuscated version (before 26.1) to Mojang names on the player's machine (ADR-0019
    // point 4). ASM is BSD-3-Clause and already ships in the Plexus Mod Loader runtime; the installer jar now carries it too.
    implementation(libs.asm)
    implementation(libs.asm.commons)
}

// The Plexus Mod Loader runtime the installer copies into the launcher's libraries/ folder: the launcher main
// class, the runtime, every supported version's adapter and game layer, and the loader bridges.
val installerRuntime = configurations.create("installerRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    installerRuntime(project(":plexus-bootstrap"))
}

// Minecraft ships these at the same versions (ADR-0005; see gradle/libs.versions.toml), so the profile uses
// the game's copy. error_prone_annotations only carries gson's compile-time annotations.
val providedByMinecraft = setOf(
    "com.google.code.gson:gson",
    "org.slf4j:slf4j-api",
    "com.google.errorprone:error_prone_annotations",
)
val plexusModLoaderVersion = version.toString()
val plexusGroup = group.toString()
val supportedMinecraft = dev.plexusmodloader.devtools.gradle.minecraft.MinecraftVersions.of(project).runnable()
// plexus-bootstrap carries an experimental version's adapter when the build runs it (ADR-0019); players never get it.
val runsExperimental = dev.plexusmodloader.devtools.gradle.minecraft.MinecraftVersions.of(project).let {
    it.isExperimental(it.modulesVersion(it.selected(project)))
}

val installerPayload = tasks.register<dev.plexusmodloader.devtools.gradle.release.InstallerPayloadTask>("installerPayload") {
    description = "Collects the Plexus Mod Loader runtime jars and their index for the installer."
    doFirst {
        if (runsExperimental) {
            throw GradleException("-PplexusMinecraft picks an experimental Minecraft version; the installer is built"
                + " only for supported versions $supportedMinecraft. Build it without -PplexusMinecraft.")
        }
    }
    val artifacts = installerRuntime.incoming.artifacts.resolvedArtifacts
    libraries.set(artifacts.map { resolved ->
        resolved.mapNotNull { artifact ->
            val coordinates = when (val id = artifact.id.componentIdentifier) {
                is ProjectComponentIdentifier -> "$plexusGroup:${id.projectName}:$plexusModLoaderVersion"
                is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
                else -> throw GradleException("Unexpected installer runtime component $id")
            }
            if (coordinates.substringBeforeLast(':') in providedByMinecraft) null
            else coordinates to artifact.file.absolutePath
        }.toMap()
    })
    libraryFiles.from(installerRuntime)
    this.plexusVersion = plexusModLoaderVersion
    this.minecraftVersions = supportedMinecraft
    mainClass = "dev.plexusmodloader.bootstrap.PlexusLauncherMain"
    // Plexus Mod Loader's Java agent (ADR-0024): -javaagent in the launcher profile, Launcher-Agent-Class on the server.
    agentLibrary = "$plexusGroup:plexus-bootstrap:$plexusModLoaderVersion"
    agentClass = "dev.plexusmodloader.bootstrap.SystemClassPathAgent"
    outputDirectory = layout.buildDirectory.dir("installer-payload")
}

// PlexusModLoader-<version>.jar: the installer with gson and the payload inside; double-click or java -jar.
val installerJar = tasks.register<Jar>("installerJar") {
    group = "distribution"
    description = "Builds the end-user installer PlexusModLoader-<version>.jar."
    archiveFileName = "PlexusModLoader-$plexusModLoaderVersion.jar"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class",
            "META-INF/versions/**/module-info.class")
    }
    from(installerPayload)
    from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.plexusmodloader.installer.InstallerMain",
            "Implementation-Title" to "Plexus Mod Loader installer",
            "Implementation-Version" to plexusModLoaderVersion,
        )
    }
}

// The jar is the only download, on every platform.
tasks.register("installerDistributions") {
    group = "distribution"
    description = "Builds PlexusModLoader-<version>.jar."
    dependsOn(installerJar)
}

// Other projects (the launcher profile test) take the installer jar from here.
configurations.create("installerJarElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(installerJar)
}

// The dedicated server path end to end: the installer jar installs a server (Mojang's server jar, network needed)
// into a fresh folder, then java -jar plexusmodloader-server.jar starts it through Plexus Mod Loader. Headless; the Minecraft
// version is the one this build runs (the default, or -PplexusMinecraft), which the test mods are built for, or
// -PserverInstallMinecraft=<version> (one the installer lists).
//  1. no mods: up to Minecraft's EULA check, which nobody accepts here; nothing of Forge is downloaded or logged
//  2. the Forge test mod forge-hello in mods/, on Mojang's game test server: the launch jar's Java agent puts Forge's
//     file systems on the class path (ADR-0024), FML constructs the mod, a world is created and a GameTest passes
//  3. the same mod on the dedicated server: Forge launches the game, up to the EULA check
//  4, 5. the Quilt test mod quilt-hello alone on the game test server and on the dedicated server (below)
//  6. NeoForge mods instead (ADR-0026, N9): the NeoForge test mod neoforge-hello and the game test harness on Mojang's
//     game test server: the launch jar's Java agent opens java.lang.invoke to FML's modules, FML constructs the mods, a
//     world is created and a GameTest passes
//  7. the same mods on the dedicated server: NeoForge starts the game, up to the EULA check
// forge-hello is built for the build's version and needs Minecraft 26.2 or later (Forge 65); on another version, phases
// 2 and 3 run Forge without mods (-Dplexus.forge=always), as forgeWorldTest does. neoforge-hello is built for the build's
// version and needs 26.2 or later; on another version, phases 6 and 7 run NeoForge without it. Phase 6 also has
// neoforge-early-service, a mod nested in an early service jar as Sodium ships, which FML loads from the mods folder.
val serverJava = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
val forgeTestMod = configurations.create("serverInstallTestForgeMod") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
val neoForgeTestMods = configurations.create("serverInstallTestNeoForgeMods") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
// The public source has no test-mods/ (settings.gradle.kts); serverInstallTest then has no mods to run.
val testModsPresent = findProject(":test-mods:forge-hello") != null
if (testModsPresent) {
    dependencies {
        // Built for the Minecraft version this build runs, the one this test installs unless told otherwise.
        forgeTestMod(project(":test-mods:forge-hello"))
        neoForgeTestMods(project(":test-mods:neoforge-hello"))
        neoForgeTestMods(project(":test-mods:neoforge-gametest-harness"))
        // Laid out as Sodium's NeoForge build: an early service jar with the mod nested in it, from the mods folder.
        neoForgeTestMods(project(":test-mods:neoforge-early-service"))
    }
}
// The NeoForge release Plexus Mod Loader runs for each Minecraft version: <minecraft>=<neoforge version> <installer SHA-1>.
val neoForgeReleases = rootProject.layout.projectDirectory.file(
    "plexus-bridge-forge/src/main/resources/dev/plexusmodloader/compat/forge/neoforge-releases.properties")
// Phases 4 and 5 (Quilt full support Q8): the Quilt test mod quilt-hello alone (only quilt.mod.json; Quilt Loader's API,
// Mixin, access wideners, nested Fabric and Quilt mods) on the installed server's game test server and dedicated server.
// It runs on every supported version (its quilt.mod.json accepts 26.1.x and 26.2.x).
val quiltTestMod = configurations.create("serverInstallTestQuiltMod") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
if (testModsPresent) {
    dependencies {
        quiltTestMod(project(":test-mods:quilt-hello"))
    }
}
// The Forge release Plexus Mod Loader runs for each Minecraft version: <minecraft>=<forge version> <installer SHA-1>.
val forgeReleases = rootProject.layout.projectDirectory.file(
    "plexus-bridge-forge/src/main/resources/dev/plexusmodloader/compat/forge/forge-releases.properties")
tasks.register("serverInstallTest") {
    group = "verification"
    description = "Installs a dedicated server with the installer jar and starts it: without mods up to the EULA check," +
        " and with a Forge mod, a Quilt mod, then NeoForge mods, on the game test server and the dedicated server."
    dependsOn(installerJar)
    val jar = installerJar.flatMap { it.archiveFile }
    val java = serverJava.map { it.executablePath.asFile.absolutePath }
    val folder = layout.buildDirectory.dir("server install test/server")
    val built = dev.plexusmodloader.devtools.gradle.minecraft.MinecraftVersions.of(project).selected(project)
    val minecraft = providers.gradleProperty("serverInstallMinecraft").orNull ?: built
    if (minecraft !in supportedMinecraft) {
        throw GradleException("serverInstallMinecraft=$minecraft is not one of $supportedMinecraft")
    }
    // forge-hello needs Forge [65,) and neoforge-hello NeoForge [26.2,) (their mods.toml), which Plexus Mod Loader runs
    // from Minecraft 26.2 on; Forge on 26.1, 26.1.1 and 26.1.2 is 62 to 64. On those versions the test mods cannot load,
    // so phases 2, 3, 6 and 7 run the loaders without them even when -PplexusMinecraft names the version.
    val testModsRunHere = minecraft.split('.').map { it.toInt() }.let { it[0] > 26 || (it[0] == 26 && it[1] >= 2) }
    val withForgeMod = minecraft == built && testModsRunHere
    inputs.property("minecraft", minecraft)
    val workDirectory = projectDir
    val forgeMod = forgeTestMod.incoming.files
    inputs.files(forgeMod).withPropertyName("forgeTestMod")
    val quiltMod = quiltTestMod.incoming.files
    inputs.files(quiltMod).withPropertyName("quiltTestMod")
    inputs.file(forgeReleases).withPropertyName("forgeReleases")
    val neoForgeMods = neoForgeTestMods.incoming.files
    inputs.files(neoForgeMods).withPropertyName("neoForgeTestMods")
    inputs.file(neoForgeReleases).withPropertyName("neoForgeReleases")
    doLast {
        val server = folder.get().asFile
        server.deleteRecursively()
        // Forge and NeoForge run mods on 26.1 and later only (forge-releases.properties, neoforge-releases.properties; ADR-0021,
        // ADR-0034): a version before 26.1 has no release there, so its Forge and NeoForge phases are not applicable.
        // Quilt mods are Fabric-family mods, which Plexus Mod Loader blocks on versions before 26.1 (intermediary names,
        // ADR-0019), so its phases are not applicable there either. The phases that are applicable always run.
        val obfuscatedVersion = minecraft.substringBefore('.').toInt() < 26
        val forgeVersion = Properties()
            .apply { forgeReleases.asFile.inputStream().use { load(it) } }
            .getProperty(minecraft)?.trim()?.split(Regex("\\s+"))?.first()
        val neoForgeVersion = Properties()
            .apply { neoForgeReleases.asFile.inputStream().use { load(it) } }
            .getProperty(minecraft)?.trim()?.split(Regex("\\s+"))?.first()
        if (!obfuscatedVersion && (forgeVersion == null || neoForgeVersion == null)) {
            throw GradleException("serverInstallTest: Plexus Mod Loader has no Forge release ($forgeVersion) or NeoForge" +
                " release ($neoForgeVersion) for Minecraft $minecraft")
        }
        val skipped = mutableListOf<String>()

        /**
         * Runs [command] in [directory], streaming its output to [logFile]; kills it (and what it started) after
         * [timeoutSeconds]. Returns the output and the exit code (null when it was killed).
         */
        fun run(directory: File, logFile: File?, timeoutSeconds: Long, vararg command: String): Pair<String, Int?> {
            val process = ProcessBuilder(*command).directory(directory).redirectErrorStream(true).start()
            val output = StringBuffer()
            val reader = Thread.ofPlatform().name("server-install-test-output").start {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        output.append(line).append('\n')
                        logFile?.appendText(line + "\n")
                    }
                }
            }
            val ended = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!ended) {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(30, TimeUnit.SECONDS)
            }
            reader.join(10_000)
            return output.toString() to (if (ended) process.exitValue() else null)
        }
        fun check(phase: String, log: String, exit: Int?, logFile: File, expected: List<String>, absent: List<String>) {
            val missing = expected.filter { it !in log }
            val present = absent.filter { it in log }
            if (exit != 0 || missing.isNotEmpty() || present.isNotEmpty()) {
                throw GradleException("serverInstallTest $phase: " +
                    (if (exit == null) "killed after the timeout; " else if (exit != 0) "exit code $exit; " else "") +
                    "missing $missing, unexpected $present in the server log $logFile")
            }
        }

        /** The log says "Transformation plan: N rule(s) accepted, M rejected"; a rejected rule means the game does not match. */
        fun noRejectedRules(phase: String, log: String, logFile: File) {
            val plan = Regex("Transformation plan: (\\d+) rule\\(s\\) accepted, (\\d+) rejected").find(log)
                ?: throw GradleException("serverInstallTest $phase: no 'Transformation plan' line in the server log $logFile")
            if (plan.groupValues[1].toInt() == 0 || plan.groupValues[2].toInt() != 0) {
                throw GradleException("serverInstallTest $phase: ${plan.value} (expected rules accepted and none rejected)," +
                    " see the server log $logFile")
            }
        }

        val (installLog, installExit) = run(workDirectory, null, 600, java.get(), "-jar", jar.get().asFile.absolutePath,
            "install-server", "--dir", server.absolutePath, "--minecraft", minecraft, "--max-memory", "2G")
        if (installExit != 0) {
            throw GradleException("install-server failed:\n$installLog")
        }

        // 1. No mods: no Forge download, no Forge in the log (Forge costs nothing without Forge mods).
        val plainLog = server.resolve("phase1-plain.log")
        val (plain, plainExit) = run(server, plainLog, 300, java.get(), "-Xmx2G", "-jar", "plexusmodloader-server.jar", "nogui")
        check("phase 1 (no mods)", plain, plainExit, plainLog,
            listOf(
                "[Plexus Mod Loader] Phase BOOTSTRAP",
                "[Plexus Mod Loader] Minecraft $minecraft (DEDICATED_SERVER)",
                "You need to agree to the EULA in order to run the server",
            ),
            // Not "Forge" alone: the mod counts per loader name it ("0 Forge").
            listOf("MinecraftForge", "Forge's file systems", "PLEXUS-COMPAT-206"))
        noRejectedRules("phase 1 (no mods)", plain, plainLog)
        if (!server.resolve("eula.txt").readText().contains("eula=false")) {
            throw GradleException("serverInstallTest phase 1: eula.txt is not left at eula=false")
        }
        if (server.resolve(".plexus/forge").exists()) {
            throw GradleException("serverInstallTest phase 1: Forge's files were prepared without a Forge mod")
        }
        if (obfuscatedVersion) {
            // The server runs on the jar the installer remapped to Mojang names (ADR-0019), not on Mojang's jar.
            val remapped = server.resolve("versions/$minecraft/server-$minecraft-mojang.jar")
            if (!remapped.isFile || !server.resolve("versions/$minecraft/server-mappings-$minecraft.txt").isFile) {
                throw GradleException("serverInstallTest phase 1: the installer left no remapped server jar $remapped")
            }
        }

        if (obfuscatedVersion) {
            // 1b. Still no mods, on Mojang's game test server (net.minecraft.gametest.Main, 1.21.10 and later): the
            // remapped server creates a world and passes a GameTest. Versions before 1.21.10 have no such entry point.
            val jarFile = ZipFile(server.resolve("versions/$minecraft/server-$minecraft-mojang.jar"))
            val hasGameTestMain = jarFile.use { it.getEntry("net/minecraft/gametest/Main.class") != null }
            if (hasGameTestMain) {
                val plainGameTestLog = server.resolve("phase1b-plain-game-test.log")
                val (plainGameTest, plainGameTestExit) = run(server, plainGameTestLog, 600, java.get(), "-Xmx2G",
                    "-Dplexus.launchTarget=game_test_server", "-jar", "plexusmodloader-server.jar",
                    "--universe", "universe", "--tests", "minecraft:always_pass")
                check("phase 1b (no mods, game test server)", plainGameTest, plainGameTestExit, plainGameTestLog,
                    listOf(
                        "[Plexus Mod Loader] Minecraft $minecraft (DEDICATED_SERVER) via adapter minecraft-$minecraft, launch target game_test_server",
                        "[Plexus Mod Loader] Phase GAME_LAUNCH: net.minecraft.gametest.Main",
                        "All 1 required tests passed",
                    ),
                    listOf("MinecraftForge", "PLEXUS-COMPAT-206"))
                noRejectedRules("phase 1b (no mods, game test server)", plainGameTest, plainGameTestLog)
            } else {
                skipped.add("phase 1b (game test server): Minecraft $minecraft has no net.minecraft.gametest.Main")
            }
        }

        // 2. A Forge mod on the game test server, started the same way.
        if (forgeVersion == null) {
            skipped.add("phases 2-3 (Forge): Plexus Mod Loader runs Forge on 26.1 and later; there is no Forge release for $minecraft")
        }
        if (forgeVersion != null) {
        val forgeMods = if (withForgeMod) 1 else 0
        val forgeAlways = if (withForgeMod) listOf() else listOf("-Dplexus.forge=always")
        if (withForgeMod) {
            forgeMod.files.forEach { it.copyTo(server.resolve("mods").resolve(it.name)) }
        }
        val gameTestLog = server.resolve("phase2-forge-game-test.log")
        val (gameTest, gameTestExit) = run(server, gameTestLog, 600, java.get(), "-Xmx2G", *forgeAlways.toTypedArray(),
            "-Dplexus.launchTarget=game_test_server", "-jar", "plexusmodloader-server.jar",
            "--universe", "universe", "--tests", "minecraft:always_pass")
        check("phase 2 (Forge mod, game test server)", gameTest, gameTestExit, gameTestLog,
            listOf(
                "[Plexus Mod Loader] Added Forge's file systems to the JVM class path",
                "[Plexus Mod Loader] MinecraftForge $forgeVersion loads $forgeMods Forge mod(s) itself on Plexus Mod Loader's game class loader",
                "[Plexus Mod Loader] Launching target 'plexusmodloader_server_gametest'",
                "Forge mod loading, version ${forgeVersion.substringAfter('-')}",
                "All 1 required tests passed",
            ) + (if (withForgeMod) listOf(
                "[forge_hello] Constructed by FML in module ",
                "[forge_hello] Common setup: item forge_hello:hello_item is registered",
            ) else listOf()),
            listOf("PLEXUS-COMPAT-206"))

        // 3. The same mod on the dedicated server: Forge's launch handler starts it, up to the EULA check.
        val dedicatedLog = server.resolve("phase3-forge-server.log")
        val (dedicated, dedicatedExit) = run(server, dedicatedLog, 600, java.get(), "-Xmx2G", *forgeAlways.toTypedArray(),
            "-jar", "plexusmodloader-server.jar", "nogui")
        check("phase 3 (Forge mod, dedicated server)", dedicated, dedicatedExit, dedicatedLog,
            listOf(
                "[Plexus Mod Loader] Added Forge's file systems to the JVM class path",
                "[Plexus Mod Loader] MinecraftForge $forgeVersion loads $forgeMods Forge mod(s) itself on Plexus Mod Loader's game class loader",
                "[Plexus Mod Loader] Phase GAME_LAUNCH: MinecraftForge $forgeVersion starts the game",
                "You need to agree to the EULA in order to run the server",
            ),
            listOf("PLEXUS-COMPAT-206"))
        }
        // 4. The Quilt test mod alone (the Forge mod taken out) on the game test server: Quilt Loader's API, the mod's
        //    entrypoints, Mixin and access wideners, a world and a GameTest; Forge is not started.
        server.resolve("mods").listFiles()?.forEach { it.delete() }
        if (obfuscatedVersion) {
            skipped.add("phases 4-5 (Quilt): quilt-hello depends on Minecraft 26.1.x or 26.2.x (its quilt.mod.json), and" +
                " Fabric-family mods built for intermediary names are not remapped on versions before 26.1 (ADR-0019)")
        } else {
        quiltMod.files.forEach { it.copyTo(server.resolve("mods").resolve(it.name)) }
        val quiltLines = listOf(
            "[Plexus Mod Loader] Loaded mods per loader: 0 Plexus, 2 Fabric, 2 Quilt, 0 Forge",
            "[quilt_hello] entrypoint order: pre_launch -> preLaunch -> main",
            "[quilt_hello] MinecraftQuiltLoader.getEnvironmentType() = SERVER, QuiltLoader.getRawGameVersion() = $minecraft",
            "[quilt_hello] access wideners: OptionsAmbientOcclusionFix.updateValue public = true, Mth.SIN public = true",
            "[quilt_hello] nested quilt_nested_lib is ready",
        )
        val quiltAbsent = listOf("MinecraftForge", "PLEXUS-COMPAT-002", "[quilt_hello] client entrypoint ran")
        val quiltGameTestLog = server.resolve("phase4-quilt-game-test.log")
        val (quiltGameTest, quiltGameTestExit) = run(server, quiltGameTestLog, 600, java.get(), "-Xmx2G",
            "-Dplexus.launchTarget=game_test_server", "-jar", "plexusmodloader-server.jar",
            "--universe", "universe", "--tests", "minecraft:always_pass")
        check("phase 4 (Quilt mod, game test server)", quiltGameTest, quiltGameTestExit, quiltGameTestLog,
            quiltLines + listOf(
                "[quilt_hello] Quilt mod's Mixin: tickServer @Inject ran",
                "All 1 required tests passed",
            ),
            quiltAbsent)

        // 5. The same mod on the dedicated server, up to the EULA check (the mod's main entrypoint runs before it).
        val quiltDedicatedLog = server.resolve("phase5-quilt-server.log")
        val (quiltDedicated, quiltDedicatedExit) = run(server, quiltDedicatedLog, 600, java.get(), "-Xmx2G",
            "-jar", "plexusmodloader-server.jar", "nogui")
        check("phase 5 (Quilt mod, dedicated server)", quiltDedicated, quiltDedicatedExit, quiltDedicatedLog,
            quiltLines + listOf("You need to agree to the EULA in order to run the server"),
            quiltAbsent)
        }

        // 6. NeoForge mods instead of the Quilt mod, on the game test server, started the same way. NeoForge ticks game
        // tests only in development; the harness ticks them (neoForgeGameTest).
        server.resolve("mods").listFiles()?.forEach { it.delete() }
        if (neoForgeVersion == null) {
            skipped.add("phases 6-7 (NeoForge): Plexus Mod Loader runs NeoForge on 26.1 and later; there is no NeoForge release for $minecraft")
        } else {
        val neoForgeJars = neoForgeMods.files.filter { withForgeMod || !it.name.startsWith("neoforge-hello") }
        neoForgeJars.forEach { it.copyTo(server.resolve("mods").resolve(it.name)) }
        val neoForgeGameTestLog = server.resolve("phase6-neoforge-game-test.log")
        val (neoForgeGameTest, neoForgeGameTestExit) = run(server, neoForgeGameTestLog, 600, java.get(), "-Xmx2G",
            "-Dplexus.launchTarget=game_test_server", "-jar", "plexusmodloader-server.jar",
            "--universe", "universe", "--tests", "minecraft:always_pass")
        check("phase 6 (NeoForge mods, game test server)", neoForgeGameTest, neoForgeGameTestExit, neoForgeGameTestLog,
            listOf(
                "[Plexus Mod Loader] NeoForge $neoForgeVersion loads ${neoForgeJars.size} NeoForge mod file(s) itself on Plexus Mod Loader's game class loader",
                "[Plexus Mod Loader] Phase GAME_LAUNCH: NeoForge $neoForgeVersion starts the game",
                "[neoforge_gametest_harness] NeoForge runs in production and does not tick game tests; the harness ticks them",
                // FML loads the early service jar from the mods folder and the mod nested in it.
                "Found 1 early service jars",
                "[neoforge_early_service] Constructed by FML from the jar nested in the early service jar",
                "All 1 required tests passed",
            ) + (if (minecraft.startsWith("26.1") || minecraft == "26.2") listOf(
                // FML 11 runs graphics bootstrap plugins on a server too; FML 12 (NeoForge for 26.3) only with a window.
                "Running graphics bootstrap plugin neoforge_early_service",
            ) else listOf()) + (if (withForgeMod) listOf(
                "[neoforge_hello] Constructed by FML in module neoforge_hello (neoforge_hello)",
                "[neoforge_hello] Common setup: item neoforge_hello:hello_item is registered",
            ) else listOf()),
            listOf("PLEXUS-COMPAT-216", "PLEXUS-COMPAT-217", "MinecraftForge",
                "Attempted to select a dependency jar for JarJar which was passed in as source"))

        // 7. The same mods on the dedicated server: NeoForge starts the game, up to the EULA check.
        val neoForgeDedicatedLog = server.resolve("phase7-neoforge-server.log")
        val (neoForgeDedicated, neoForgeDedicatedExit) = run(server, neoForgeDedicatedLog, 600, java.get(), "-Xmx2G",
            "-jar", "plexusmodloader-server.jar", "nogui")
        check("phase 7 (NeoForge mods, dedicated server)", neoForgeDedicated, neoForgeDedicatedExit, neoForgeDedicatedLog,
            listOf(
                "[Plexus Mod Loader] NeoForge $neoForgeVersion loads ${neoForgeJars.size} NeoForge mod file(s) itself on Plexus Mod Loader's game class loader",
                "[Plexus Mod Loader] Phase GAME_LAUNCH: NeoForge $neoForgeVersion starts the game",
                "You need to agree to the EULA in order to run the server",
            ),
            listOf("PLEXUS-COMPAT-216", "MinecraftForge"))
        }
        if (!server.resolve("eula.txt").readText().contains("eula=false")) {
            throw GradleException("serverInstallTest: eula.txt is not left at eula=false")
        }

        val forgeRun = if (withForgeMod) "forge_hello" else "Forge without mods"
        val neoForgeRun = if (withForgeMod) "neoforge_hello" else "NeoForge with the game test harness"
        if (obfuscatedVersion) {
            println("serverInstallTest passed on Minecraft $minecraft: the installer remapped the server to Mojang names;" +
                " no mods up to the EULA check with no rejected transformation rule; eula.txt left at eula=false." +
                " Not applicable on this version: ${skipped.joinToString("; ")}")
        } else {
            println("serverInstallTest passed on Minecraft $minecraft: no mods up to the EULA check without Forge; $forgeRun" +
                " on the game test server (world, GameTest) and on the dedicated server up to the EULA check; quilt_hello" +
                " and $neoForgeRun each on the game test server and on the dedicated server up to the EULA check; eula.txt" +
                " left at eula=false")
        }
    }
}
