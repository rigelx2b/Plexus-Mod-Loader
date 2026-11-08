plugins {
    id("plexus.java-conventions")
}

description = "NeoForge and MinecraftForge bridges: mod metadata analysis and diagnosis; MinecraftForge through FML."

dependencies {
    implementation(project(":plexus-bridge-api"))
    implementation(libs.tomlj)
    implementation(libs.xz)
    implementation(libs.gson)
    // SrgTranslator renames Forge 1.20.x's SRG members to Mojang's (ADR-0038).
    implementation(libs.asm)
    implementation(libs.asm.commons)
    // At run time ForgeModRequirements uses the copy in Forge's own libraries.
    testImplementation(libs.maven.artifact)
    testImplementation(libs.night.config.core)
    // ForgeTitleBrandingTest plans its rule against Forge's patched client classes.
    testImplementation(project(":plexus-transform-engine"))
    testImplementation(libs.asm)
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.util)
}

// Plexus Mod Loader's implementation of ModLauncher's launch for FML (ADR-0021). These classes run inside the BOOT layer's
// cpw.mods.modlauncher module, defined by the game class loader, so they are compiled separately against ModLauncher's
// API and packed as META-INF/plexusmodloader/forge-host.jar, never loaded from this jar's own class path.
val forgeHost = sourceSets.create("forgeHost")
dependencies {
    "forgeHostCompileOnly"(sourceSets.main.get().output)
    "forgeHostCompileOnly"(project(":plexus-bridge-api"))
    "forgeHostCompileOnly"(libs.forge.modlauncher) { isTransitive = false }
    "forgeHostCompileOnly"(libs.forge.securemodules) { isTransitive = false }
    "forgeHostCompileOnly"(libs.forge.fmlloader) { isTransitive = false }
    "forgeHostCompileOnly"(libs.forge.forgespi) { isTransitive = false }
    "forgeHostCompileOnly"(libs.jopt.simple)
    "forgeHostCompileOnly"(libs.asm)
    "forgeHostCompileOnly"(libs.asm.tree)
    "forgeHostCompileOnly"(libs.maven.artifact)
    // ForeignModFileInfoTest checks the forgespi views of other loaders' mods (ADR-0025) against forgespi itself.
    testImplementation(forgeHost.output)
    // ForgeClassTransformerTest asks transformers for their labels, as ModLauncher's ITransformer has them.
    testImplementation(libs.forge.modlauncher) { isTransitive = false }
    testImplementation(libs.forge.forgespi) { isTransitive = false }
    testImplementation(libs.forge.securemodules) { isTransitive = false }
}
// Forge 47 (Minecraft 1.20.1) launches through ModLauncher 10.0.9 and FML 47, whose launch handlers (CommonLaunchHandler) have a
// different shape from the 26.x ones above. The game test launch handler for it is the one class compiled against them; it is
// packed into the same forge-host.jar and registered instead of the other one when the ModLauncher is 10.0.x (ForgeBootModules).
val forgeHost47 = sourceSets.create("forgeHost47")
dependencies {
    "forgeHost47CompileOnly"(libs.forge47.fmlloader) { isTransitive = false }
    "forgeHost47CompileOnly"(libs.forge47.modlauncher) { isTransitive = false }
    "forgeHost47CompileOnly"(libs.forge47.securejarhandler) { isTransitive = false }
    "forgeHost47CompileOnly"(libs.forge47.forgespi) { isTransitive = false }
    "forgeHost47CompileOnly"(variantOf(libs.forge47.mergetool.api) { classifier("api") }) { isTransitive = false }
    "forgeHost47CompileOnly"(libs.slf4j.api)
    "forgeHost47CompileOnly"(libs.jopt.simple)
    "forgeHost47CompileOnly"(libs.asm)
    "forgeHost47CompileOnly"(libs.asm.tree)
    // Paths47Test checks the paths and arguments the Forge 47 launch handlers hand to FML 47, against its own LocatedPaths.
    testImplementation(forgeHost47.output)
    testImplementation(libs.forge47.fmlloader) { isTransitive = false }
}
val forgeHostJar = tasks.register<Jar>("forgeHostJar") {
    description = "Packs Plexus Mod Loader's ModLauncher implementation for FML."
    archiveFileName = "forge-host.jar"
    destinationDirectory = layout.buildDirectory.dir("forge-host")
    from(forgeHost.output)
    from(forgeHost47.output)
}
// Plexus Mod Loader's host for NeoForge's FML 11 (ADR-0026). Its classes run inside the layer of NeoForge's loader
// libraries, defined by the game class loader, so they are compiled separately against FML's loader and packed as
// META-INF/plexusmodloader/neoforge-host.jar, never loaded from this jar's own class path.
val neoForgeHost = sourceSets.create("neoForgeHost")
dependencies {
    "neoForgeHostCompileOnly"(sourceSets.main.get().output)
    "neoForgeHostCompileOnly"(project(":plexus-bridge-api"))
    "neoForgeHostCompileOnly"(libs.neoforge.fml.loader) { isTransitive = false }
    "neoForgeHostCompileOnly"(variantOf(libs.neoforge.mergetool.api) { classifier("api") }) { isTransitive = false }
    "neoForgeHostCompileOnly"(libs.maven.artifact)
    // NeoForgeLoaderPatchTest checks the patch against FML's own FMLLoader, and the patched class against the verifier.
    testImplementation(libs.neoforge.fml.loader) { isTransitive = false }
    testImplementation(variantOf(libs.neoforge.mergetool.api) { classifier("api") }) { isTransitive = false }
    // NeoForgeSharedMixinPatchTest verifies FML's patched MixinFacade, whose other methods use Mixin's types.
    testImplementation(libs.sponge.mixin) { isTransitive = false }
    // FmlSharedMixinPlaceholderTest sorts the host's placeholder processors with FML's own topological sort (Guava).
    testImplementation(neoForgeHost.output)
    testImplementation(libs.guava)
}
// NeoForgeSharedMixinPatchTest also checks the patch against FML 12 (NeoForge 26.3), whose classes cannot share the test
// class path with FML 11's; the test reads them from this jar (plexus.fml12Loader).
val neoForgeFml12 = configurations.create("neoForgeFml12") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    neoForgeFml12(libs.neoforge.fml12.loader)
}
tasks.test {
    val fml12 = neoForgeFml12.incoming.files
    inputs.files(fml12).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("neoForgeFml12")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dplexus.fml12Loader=" + fml12.singleFile.absolutePath)
    })
}
// Fml4SharedMixinPatchTest checks the patch against FML 4 (NeoForge 21.1) and FML 2 (NeoForge 20.4), whose classes share
// package names with FML 11's on the test class path; the test reads them from these jars (plexus.fml4Loader, fml2Loader).
val neoForgeFml4Loader = configurations.create("neoForgeFml4Loader") {
    isCanBeConsumed = false
    isTransitive = false
}
val neoForgeFml2Loader = configurations.create("neoForgeFml2Loader") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    neoForgeFml4Loader(libs.neoforge.fml4.loader)
    neoForgeFml2Loader(libs.neoforge.fml2.loader)
}
tasks.test {
    val fml4 = neoForgeFml4Loader.incoming.files
    val fml2 = neoForgeFml2Loader.incoming.files
    inputs.files(fml4).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("neoForgeFml4Loader")
    inputs.files(fml2).withPathSensitivity(PathSensitivity.NAME_ONLY).withPropertyName("neoForgeFml2Loader")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dplexus.fml4Loader=" + fml4.singleFile.absolutePath,
            "-Dplexus.fml2Loader=" + fml2.singleFile.absolutePath,
        )
    })
}
// NeoForge 21.1 and 20.4 (FML 4, ADR-0043) still run on ModLauncher (11.0.5), with FML's types in net.neoforged. They use the
// ModLauncher implementation of forge-host.jar (forgeHost) and add their own service providers and game test launch handler,
// compiled against FML 4 here and packed with it as META-INF/plexusmodloader/neoforge-fml4-host.jar.
val neoForgeFml4Host = sourceSets.create("neoForgeFml4Host")
dependencies {
    "neoForgeFml4HostCompileOnly"(libs.neoforge.fml4.loader) { isTransitive = false }
    "neoForgeFml4HostCompileOnly"(libs.neoforge.modlauncher11) { isTransitive = false }
    "neoForgeFml4HostCompileOnly"(libs.neoforge.securejarhandler3) { isTransitive = false }
    "neoForgeFml4HostCompileOnly"(variantOf(libs.neoforge.mergetool.api) { classifier("api") }) { isTransitive = false }
    "neoForgeFml4HostCompileOnly"(libs.slf4j.api)
    "neoForgeFml4HostCompileOnly"(libs.asm)
    // FmlArguments and ModPathList, which have no FML types, are in the ModLauncher implementation (forgeHost).
    "neoForgeFml4HostCompileOnly"(forgeHost.output)
    // ModPathListTest: the one class of the host that has no FML types.
    testImplementation(neoForgeFml4Host.output)
}
val neoForgeFml4HostJar = tasks.register<Jar>("neoForgeFml4HostJar") {
    description = "Packs Plexus Mod Loader's ModLauncher implementation and service providers for NeoForge's FML 4."
    archiveFileName = "neoforge-fml4-host.jar"
    destinationDirectory = layout.buildDirectory.dir("neoforge-fml4-host")
    from(forgeHost.output)
    from(neoForgeFml4Host.output)
}
// NeoForge 20.4 (FML 2.0.17, ADR-0043) runs on ModLauncher 10.0.9 like Forge 47, with FML's types in net.neoforged: its launch
// handlers are compiled against FML 2 and packed with the ModLauncher implementation as neoforge-fml2-host.jar.
val neoForgeFml2Host = sourceSets.create("neoForgeFml2Host")
dependencies {
    "neoForgeFml2HostCompileOnly"(libs.neoforge.fml2.loader) { isTransitive = false }
    "neoForgeFml2HostCompileOnly"(libs.forge47.modlauncher) { isTransitive = false }
    "neoForgeFml2HostCompileOnly"(libs.neoforge.securejarhandler2) { isTransitive = false }
    "neoForgeFml2HostCompileOnly"(variantOf(libs.neoforge.mergetool.api) { classifier("api") }) { isTransitive = false }
    "neoForgeFml2HostCompileOnly"(libs.slf4j.api)
    "neoForgeFml2HostCompileOnly"(libs.asm)
    "neoForgeFml2HostCompileOnly"(forgeHost.output)
}
val neoForgeFml2HostJar = tasks.register<Jar>("neoForgeFml2HostJar") {
    description = "Packs Plexus Mod Loader's ModLauncher implementation and launch handlers for NeoForge's FML 2."
    archiveFileName = "neoforge-fml2-host.jar"
    destinationDirectory = layout.buildDirectory.dir("neoforge-fml2-host")
    from(forgeHost.output)
    from(neoForgeFml2Host.output)
}
val neoForgeHostJar = tasks.register<Jar>("neoForgeHostJar") {
    description = "Packs Plexus Mod Loader's host for NeoForge's FML."
    archiveFileName = "neoforge-host.jar"
    destinationDirectory = layout.buildDirectory.dir("neoforge-host")
    from(neoForgeHost.output)
}
tasks.jar {
    from(forgeHostJar) { into("META-INF/plexusmodloader") }
    from(neoForgeHostJar) { into("META-INF/plexusmodloader") }
    from(neoForgeFml4HostJar) { into("META-INF/plexusmodloader") }
    from(neoForgeFml2HostJar) { into("META-INF/plexusmodloader") }
}

// ForgePatchSetTest compares Plexus Mod Loader's patching with Forge's installer output when given the installer jar, the
// vanilla server classes (server-<mc>-unpacked.jar) and Forge's patched server jar (forge-<version>-server.jar).
// NeoForgePatchBundleTest does the same with NeoForge's installer, the vanilla client jar or server classes and the jar
// its installer made (minecraft-client-patched-<version>.jar, minecraft-server-patched-<version>.jar; neoForgeSide).
// ForgeTitleBrandingTest checks its rule against Forge's patched client (forge-<version>-client.jar from a Forge client
// run's .plexus/forge/, or forgeClientInstaller + forgeVanillaClient to patch it) and Forge's universal jar.
// NeoForgeTitleBrandingTest does the same with the client jar NeoForge's installer patched (neoForgePatchedClient) and
// NeoForge's universal jar (neoForgeUniversal).
tasks.test {
    for (name in listOf(
        "forgeInstaller", "forgeVanillaServer", "forgePatchedServer",
        "forgePatchedClient", "forgeClientInstaller", "forgeVanillaClient", "forgeUniversal",
        "neoForgeInstaller", "neoForgeVanillaJar", "neoForgePatchedJar", "neoForgeSide",
        "neoForgePatchedClient", "neoForgeUniversal",
    )) {
        providers.gradleProperty(name).orNull?.let { systemProperty("plexus.$name", it) }
    }
}
