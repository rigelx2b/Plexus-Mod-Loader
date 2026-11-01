plugins {
    id("plexus.java-conventions")
    id("plexus.minecraft-compile")
}

description = "Fabric API modules implemented on MinecraftForge 26.2's hooks (ADR-0031): run in place of the original modules next to Forge."

dependencies {
    compileOnly(libs.fabric.loader) { isTransitive = false }
    compileOnly(libs.sponge.mixin) { isTransitive = false }
    compileOnly(libs.mixinextras.common) { isTransitive = false }
    compileOnly(libs.asm)
    compileOnly(variantOf(libs.forge.universal) { classifier("universal") }) { isTransitive = false }
    // Forge's event bus, for the LivingBreatheEvent listener of the content registries (its API is the same in 7.0.6).
    compileOnly(libs.forge.eventbus) { isTransitive = false }
    compileOnly(libs.forge.mergetool.api) { isTransitive = false }
    compileOnly(libs.fabric.api.base) { isTransitive = false }
    compileOnly(libs.fabric.resource.loader.v1) { isTransitive = false }
    compileOnly(libs.fabric.lifecycle.events.v1) { isTransitive = false }
    compileOnly(libs.jspecify)
    compileOnly(libs.jetbrains.annotations)
}

// The code calls what Forge's patch adds to the game (ChunkGenerator.refreshFeaturesPerStep, Forge's fluid types) and
// the client's classes (content registries' client mixins): it compiles against the client jar Forge's own installer
// patched, which holds the common classes too, ahead of the vanilla jar. The installation is kept in Gradle's cache and
// reused while the installer is the same (network on first use).
val forgeRelease = libs.versions.forgeFml.get()
val installForgeClient =
    tasks.register<dev.plexusmodloader.devtools.gradle.minecraft.ForgeClientInstallTask>("installForgeClient") {
        description = "Installs MinecraftForge $forgeRelease's client with its own installer, for the patched game classes."
        forgeVersion = forgeRelease
        // The installer Plexus Mod Loader itself checks for 26.2 (plexus-bridge-forge, forge-releases.properties).
        installerSha1 = "0746b1dc6d93abb4b84f3f924009591b6c85eb80"
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        clientDirectory.set(
            layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/plexus-forge/$forgeRelease/client") }),
        )
    }
val patchedGame = files(installForgeClient.map {
    it.clientDirectory.file("libraries/net/minecraftforge/forge/$forgeRelease/forge-$forgeRelease-client.jar")
}).builtBy(installForgeClient)

// The game jar references JetBrains annotations that are not on the class path; javac warns about every use (classfile
// lint), which -Werror would turn into errors.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
    // The Fabric API code calls game methods the game marks deprecated, casts generics unchecked and passes generic
    // varargs on, as Fabric API does (it builds without -Werror); its code is kept as it is.
    options.compilerArgs.add("-Xlint:-deprecation")
    options.compilerArgs.add("-Xlint:-removal")
    options.compilerArgs.add("-Xlint:-unchecked")
    options.compilerArgs.add("-Xlint:-varargs")
    options.compilerArgs.add("-Xlint:-rawtypes")
}

// What Plexus Mod Loader's Fabric bridge carries (HostModules in plexus-runtime-impl): each jar under the host's key, and an
// index entry <host key>.<mod id> for it. The host key is HostModules.hostKey of the host's name ("MinecraftForge
// 26.2-65.1.5").
val hostKey = "minecraftforge-$forgeRelease"

/**
 * One host module: its mod ID, the source set (src/<sourceSet>), the version its jar carries, and whether it replaces the
 * original even when the original fits Forge's patch (one that does not work there as it does under Fabric Loader).
 */
data class HostModule(val modId: String, val sourceSet: String, val version: String, val always: Boolean = false)

// Fabric API 0.161.0+26.2's module versions these jars stand in for (ADR-0031: same public API as that version).
val hostModules = listOf(
    // The original's mixins fit, but its code writes ChunkGenerator.featuresPerStep as a Supplier, which Forge made a
    // ClearableLazy (the link check leaves it out, PLEXUS-COMPAT-112), and its climate changes miss the biome info
    // Forge syncs to clients.
    HostModule("fabric-biome-api-v1", "fabricBiomeApiV1", "18.0.6+plexus.forge.26.2", always = true),
    // Forge moves drowning and fluid jumping into its FluidType hooks (PLEXUS-COMPAT-224).
    HostModule("fabric-content-registries-v0", "fabricContentRegistriesV0", "11.3.2+plexus.forge.26.2"),
    // Forge turned the client's fluid overlay check (instanceof HalfTransparentBlock or LeavesBlock) into
    // BlockState.shouldDisplayFluidOverlay; the original's expression mixin finds nothing there and the game stopped
    // when it first drew a chunk (PLEXUS-COMPAT-225), which the check before the launch does not see. Forgified Fabric
    // API's build of the mixin modifies Forge's call instead.
    HostModule("fabric-rendering-fluids-v1", "fabricRenderingFluidsV1", "6.0.4+plexus.forge.26.2", always = true),
)

val hostModuleJars = hostModules.map { module ->
    val sourceSet = sourceSets.create(module.sourceSet)
    configurations.named(sourceSet.compileOnlyConfigurationName) { extendsFrom(configurations.compileOnly.get()) }
    // The patched game with the module's class tweaker applied, as Fabric's build tools compile a mod (Loom).
    val tweaked = tasks.register<dev.plexusmodloader.devtools.gradle.minecraft.ClassTweakedJarTask>(
        "${module.sourceSet}CompileGame",
    ) {
        description = "The patched game with ${module.modId}'s class tweaker applied, for compiling it."
        inputJar.fileProvider(provider { patchedGame.singleFile })
        dependsOn(installForgeClient)
        classTweakers.from(fileTree("src/${module.sourceSet}/resources") { include("*.classtweaker") })
        outputJar = layout.buildDirectory.file("compile-game/${module.sourceSet}.jar")
    }
    sourceSet.compileClasspath = files(tweaked.flatMap { it.outputJar }) + sourceSet.compileClasspath
    module to tasks.register<Jar>("${module.sourceSet}Jar") {
        description = "Packs Plexus Mod Loader's ${module.modId} for MinecraftForge $forgeRelease."
        archiveBaseName = module.modId
        archiveVersion = module.version
        from(sourceSet.output)
    }
}

val hostModuleIndex = tasks.register<WriteProperties>("hostModuleIndex") {
    destinationFile = layout.buildDirectory.file("host-module-index/META-INF/plexusmodloader/host-modules.properties")
    for ((module, jar) in hostModuleJars) {
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
    for ((_, jar) in hostModuleJars) {
        from(jar) { into("META-INF/plexusmodloader/host-modules/$hostKey") }
    }
    from(hostModuleIndex.flatMap { it.destinationFile }) { into("META-INF/plexusmodloader") }
}
configurations.consumable("hostModuleElements") {
    outgoing.artifact(hostModuleResources)
}
tasks.assemble {
    dependsOn(hostModuleResources)
}
