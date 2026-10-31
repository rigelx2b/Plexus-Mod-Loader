plugins {
    id("plexus.java-conventions")
    id("plexus.minecraft-compile")
}

description = "Fabric API modules implemented on NeoForge 26.2's hooks (ADR-0031): run in place of the original modules next to NeoForge."

dependencies {
    compileOnly(libs.fabric.loader) { isTransitive = false }
    compileOnly(libs.sponge.mixin) { isTransitive = false }
    compileOnly(libs.mixinextras.common) { isTransitive = false }
    compileOnly(variantOf(libs.neoforge.universal) { classifier("universal") }) { isTransitive = false }
    compileOnly(libs.fabric.api.base) { isTransitive = false }
    compileOnly(libs.fabric.resource.loader.v1) { isTransitive = false }
    compileOnly(libs.fabric.lifecycle.events.v1) { isTransitive = false }
    compileOnly(libs.fabric.tag.api.v1) { isTransitive = false }
    compileOnly(libs.fabric.convention.tags.v2) { isTransitive = false }
    compileOnly(libs.fabric.recipe.api.v1) { isTransitive = false }
    compileOnly(libs.fabric.resource.conditions.api.v1) { isTransitive = false }
    // Indigo's Mixin configuration plugin takes ASM's tree classes.
    compileOnly(libs.asm.tree)
    compileOnly(libs.jspecify)
    compileOnly(libs.jetbrains.annotations)
    // NeoForge's event bus and FML (ModList, ModContainer) and their Dist, for the modules that use NeoForge's events.
    compileOnly(libs.neoforge.bus) { isTransitive = false }
    compileOnly(libs.neoforge.fml.loader) { isTransitive = false }
    compileOnly(variantOf(libs.neoforge.mergetool.api) { classifier("api") }) { isTransitive = false }
}

// The code calls what NeoForge's patch adds to the game (IBlockExtension's methods on Block, for example) and the
// client's classes (item tooltips): it compiles against the client jar NeoForge's own installer patched, which holds
// the common classes too, ahead of the vanilla jar. The installation is kept in Gradle's cache and reused while the
// installer is the same (network on first use).
val neoForgeRelease = libs.versions.neoforge.get()
val installNeoForgeClient =
    tasks.register<dev.plexusmodloader.devtools.gradle.minecraft.NeoForgeClientInstallTask>("installNeoForgeClient") {
        description = "Installs NeoForge $neoForgeRelease's client with its own installer, for the patched game classes."
        neoForgeVersion = neoForgeRelease
        // The installer Plexus Mod Loader itself checks for 26.2 (plexus-bridge-forge, neoforge-releases.properties).
        installerSha1 = "97205829a020fc9389f504a77f590b54157c8dde"
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        clientDirectory.set(
            layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/plexus-neoforge/$neoForgeRelease/client") }),
        )
    }
val patchedGame = files(installNeoForgeClient.map {
    it.clientDirectory.file(
        "libraries/net/neoforged/minecraft-client-patched/$neoForgeRelease/minecraft-client-patched-$neoForgeRelease.jar",
    )
}).builtBy(installNeoForgeClient)

// The game jar references JetBrains annotations that are not on the class path; javac warns about every use (classfile
// lint), which -Werror would turn into errors.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
    // The adapted Fabric API code calls game methods the game marks deprecated (some for removal), casts generics
    // unchecked, uses raw types and overloads a game method ambiguously, as Fabric API does (it builds without
    // -Werror); its code is kept as it is.
    options.compilerArgs.add("-Xlint:-deprecation")
    options.compilerArgs.add("-Xlint:-removal")
    options.compilerArgs.add("-Xlint:-unchecked")
    options.compilerArgs.add("-Xlint:-rawtypes")
    options.compilerArgs.add("-Xlint:-overloads")
    // The rendering module's ClientLevelMixin hands 'this' to a listener from a constructor injection, and the renderer
    // API's MutableQuadView falls through switch cases, as Fabric API's do.
    options.compilerArgs.add("-Xlint:-this-escape")
    options.compilerArgs.add("-Xlint:-fallthrough")
}

// Fabric API's transitive access wideners, which Loom applies to the modules that depend on that module (the rendering
// modules call DebugScreenEntries.register, which it makes accessible); at run time the original module applies them.
val transitiveAccessWideners = configurations.create("transitiveAccessWideners") { isTransitive = false }
dependencies { transitiveAccessWideners(libs.fabric.transitive.access.wideners.v1) }
val transitiveAccessWidenerTweaker = tasks.register<Sync>("transitiveAccessWidenerTweaker") {
    description = "Extracts the class tweaker of Fabric API's transitive access wideners, for compiling against it."
    from({ zipTree(transitiveAccessWideners.singleFile) }) { include("*.classtweaker", "*.accesswidener") }
    into(layout.buildDirectory.dir("transitive-access-wideners"))
}

// What Plexus Mod Loader's Fabric bridge carries (HostModules in plexus-runtime-impl): each jar under the host's key, and an
// index entry <host key>.<mod id> for it. The host key is HostModules.hostKey of the host's name ("NeoForge 26.2.0.93").
val hostKey = "neoforge-$neoForgeRelease"

/**
 * One host module: its mod ID, the source set (src/<sourceSet>), the version its jar carries, whether the Fabric bridge
 * carries it (a module that is not shipped is only compiled), whether it replaces the original even when the original
 * fits NeoForge's patch (one that loads there but does not do what it does under Fabric Loader), the source sets of the
 * host modules it depends on (listed before it), and whether it compiles with Fabric API's transitive access wideners.
 */
data class HostModule(
    val modId: String,
    val sourceSet: String,
    val version: String,
    val shipped: Boolean = true,
    val always: Boolean = false,
    val dependsOn: List<String> = emptyList(),
    val transitiveAccessWideners: Boolean = false,
)

// Fabric API 0.161.0+26.2's module versions these jars stand in for (ADR-0031: same public API as that version).
val hostModules = listOf(
    HostModule("fabric-block-api-v1", "fabricBlockApiV1", "3.1.0+plexus.neoforge.26.2"),
    HostModule("fabric-item-api-v1", "fabricItemApiV1", "14.5.0+plexus.neoforge.26.2"),
    HostModule("fabric-registry-sync-v0", "fabricRegistrySyncV0", "7.1.1+plexus.neoforge.26.2"),
    // The original loads next to NeoForge, but its attribute modifications run before Fabric mods add them, into a map
    // NeoForge's own attributes hide (fabric-object-builder test mod: zombie max health 20.0, not 42.0).
    HostModule(
        "fabric-object-builder-api-v1",
        "fabricObjectBuilderApiV1",
        "24.1.1+plexus.neoforge.26.2",
        always = true,
        dependsOn = listOf("fabricRegistrySyncV0"),
    ),
    HostModule("fabric-content-registries-v0", "fabricContentRegistriesV0", "11.3.2+plexus.neoforge.26.2"),
    HostModule(
        "fabric-data-generation-api-v1",
        "fabricDataGenerationApiV1",
        "25.6.0+plexus.neoforge.26.2",
        dependsOn = listOf("fabricRegistrySyncV0"),
    ),
    // NeoForge's ServerPlayer.openMenu(MenuProvider) hands the vanilla body to openMenu(MenuProvider, Consumer), where
    // Fabric API's mixins do not fit; Forgified Fabric API opens extended menus through NeoForge's menu data instead.
    HostModule("fabric-menu-api-v1", "fabricMenuApiV1", "2.0.17+plexus.neoforge.26.2"),
    // Client modules: NeoForge draws screens through ClientHooks.extractScreen and its ScreenEvent, loads sound streams
    // through SoundInstance.getStream, and replaces FluidRenderer's HalfTransparentBlock check with
    // BlockState.shouldDisplayFluidOverlay; Forgified Fabric API's modules run on those.
    HostModule("fabric-screen-api-v1", "fabricScreenApiV1", "5.2.1+plexus.neoforge.26.2"),
    HostModule("fabric-sound-api-v1", "fabricSoundApiV1", "2.0.5+plexus.neoforge.26.2"),
    HostModule("fabric-rendering-fluids-v1", "fabricRenderingFluidsV1", "6.0.4+plexus.neoforge.26.2"),
    // The client's rendering modules: NeoForge draws the HUD through its GUI layers, registers picture-in-picture
    // renderers and block models through its own events and extensions, and gives the vanilla methods Fabric API's
    // mixins wrap (BlockStateModel.collectParts, hasMaterialFlag, SectionCompiler.compile) other parameters.
    HostModule("fabric-rendering-v1", "fabricRenderingV1", "25.3.3+plexus.neoforge.26.2", transitiveAccessWideners = true),
    HostModule(
        "fabric-renderer-api-v1",
        "fabricRendererApiV1",
        "14.1.4+plexus.neoforge.26.2",
        dependsOn = listOf("fabricRenderingV1"),
        transitiveAccessWideners = true,
    ),
    // The original is left out only because it needs the renderer API (PLEXUS-COMPAT-114) and loads once that is
    // replaced, but it renders every block of the level (SectionCompilerMixin) without NeoForge's level-aware face
    // culling, light emission, dynamic tints and baked vertex colours, which Forgified Fabric API's build keeps.
    HostModule(
        "fabric-renderer-indigo",
        "fabricRendererIndigo",
        "9.1.4+plexus.neoforge.26.2",
        always = true,
        dependsOn = listOf("fabricRenderingV1", "fabricRendererApiV1"),
    ),
    HostModule(
        "fabric-model-loading-api-v1",
        "fabricModelLoadingApiV1",
        "8.0.17+plexus.neoforge.26.2",
        dependsOn = listOf("fabricRenderingV1", "fabricRendererApiV1"),
    ),
)

val hostModuleJars = hostModules.map { module ->
    val sourceSet = sourceSets.create(module.sourceSet)
    configurations.named(sourceSet.compileOnlyConfigurationName) { extendsFrom(configurations.compileOnly.get()) }
    // The transitive lines of the class tweakers of the host modules it depends on, which Loom applies to a dependent
    // too (the renderer API's code calls the interfaces the rendering module injects).
    val dependencyTweakers = files(module.dependsOn.map { fileTree("src/$it/resources") { include("*.classtweaker") } })
    val transitiveTweaker = tasks.register("${module.sourceSet}TransitiveClassTweaker") {
        description = "The transitive class tweaker lines of the host modules ${module.modId} depends on."
        val output = layout.buildDirectory.file("transitive-class-tweakers/${module.sourceSet}.classtweaker")
        inputs.files(dependencyTweakers)
        outputs.file(output)
        doLast {
            val lines = dependencyTweakers.files.sorted().flatMap { it.readLines() }
                .filter { it.trimStart().startsWith("transitive-") }
            output.get().asFile.writeText((listOf("classTweaker\tv1\tofficial") + lines).joinToString("\n", postfix = "\n"))
        }
    }
    // The patched game with the module's class tweaker applied, as Fabric's build tools compile a mod (Loom).
    val tweaked = tasks.register<dev.plexusmodloader.devtools.gradle.minecraft.ClassTweakedJarTask>(
        "${module.sourceSet}CompileGame",
    ) {
        description = "The patched game with ${module.modId}'s class tweaker applied, for compiling it."
        inputJar.fileProvider(provider { patchedGame.singleFile })
        dependsOn(installNeoForgeClient)
        classTweakers.from(fileTree("src/${module.sourceSet}/resources") { include("*.classtweaker") })
        if (module.dependsOn.isNotEmpty()) {
            classTweakers.from(transitiveTweaker)
        }
        if (module.transitiveAccessWideners) {
            classTweakers.from(
                fileTree(layout.buildDirectory.dir("transitive-access-wideners")).builtBy(transitiveAccessWidenerTweaker),
            )
        }
        outputJar = layout.buildDirectory.file("compile-game/${module.sourceSet}.jar")
    }
    sourceSet.compileClasspath = files(tweaked.flatMap { it.outputJar }) + sourceSet.compileClasspath
    for (dependency in module.dependsOn) {
        sourceSet.compileClasspath += sourceSets.getByName(dependency).output
    }
    module to tasks.register<Jar>("${module.sourceSet}Jar") {
        description = "Packs Plexus Mod Loader's ${module.modId} for NeoForge $neoForgeRelease."
        archiveBaseName = module.modId
        archiveVersion = module.version
        from(sourceSet.output)
    }
}

val hostModuleIndex = tasks.register<WriteProperties>("hostModuleIndex") {
    destinationFile = layout.buildDirectory.file("host-module-index/META-INF/plexusmodloader/host-modules.properties")
    for ((module, jar) in hostModuleJars.filter { it.first.shipped }) {
        property(
            "$hostKey.${module.modId}",
            jar.flatMap { it.archiveFileName.map { name -> "META-INF/plexusmodloader/host-modules/$hostKey/$name" } },
        )
        if (module.always) {
            property("$hostKey.${module.modId}.always", "true")
        }
    }
}
val hostModuleResources = tasks.register<Sync>("hostModuleResources") {
    into(layout.buildDirectory.dir("host-module-resources"))
    for ((module, jar) in hostModuleJars) {
        if (module.shipped) {
            from(jar) { into("META-INF/plexusmodloader/host-modules/$hostKey") }
        } else {
            dependsOn(jar)
        }
    }
    from(hostModuleIndex.flatMap { it.destinationFile }) { into("META-INF/plexusmodloader") }
}
configurations.consumable("hostModuleElements") {
    outgoing.artifact(hostModuleResources)
}
tasks.assemble {
    dependsOn(hostModuleResources)
}
