package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.BridgeClassTransformer;
import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.compat.bridge.GameHost;
import dev.plexusmodloader.compat.bridge.GameModuleLayers;
import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import dev.plexusmodloader.compat.bridge.SharedMixinConfig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * NeoForge 21.1 and 20.4 on Plexus Mod Loader (ADR-0043): NeoForge's FML 4 still runs on ModLauncher, so this host does what
 * {@link ForgeGameHost} does for MinecraftForge: prepares NeoForge's files ({@link NeoForgeInstallation}), defines FML's
 * BOOT layer in the game class loader ({@link ForgeBootModules}, flavor {@link ForgeBootModules.Flavor#NEOFORGE_FML4}),
 * and hands over to Plexus Mod Loader's implementation of ModLauncher's launch ({@code PlexusModLauncher}, inside that
 * layer), which runs FML's own discovery and NeoForge's own launch handler. FML sees exactly the NeoForge mods Plexus Mod
 * Loader found ({@code PlexusModsLocator4}). The versions from 21.11 on have no ModLauncher and use {@link
 * NeoForgeGameHost}.
 *
 * <p>With Fabric or Quilt mods in the same game, Fabric's Mixin serves NeoForge's mods too (ADR-0043 point 10): FML's own
 * Mixin is left out of the BOOT layer, FML hands its mods' configurations to Plexus Mod Loader ({@link
 * Fml4SharedMixinPatch}), and the other loaders' mods initialize with NeoForge's registries open ({@link
 * NeoForgeRegistryOpening}). Not yet: FML's mod list answering for other loaders' mods.
 *
 * <p>Thread: created and used by the launching thread only.
 */
final class NeoForgeFml4GameHost implements GameHost {

    private static final String HOST_JAR = "/META-INF/plexusmodloader/neoforge-fml4-host.jar";
    private static final String HOST_JAR_FML2 = "/META-INF/plexusmodloader/neoforge-fml2-host.jar";
    /** NeoForge versions before 21.2 run FML 4 on ModLauncher ({@code 20.4.x}, {@code 21.0.x}, {@code 21.1.x}). */
    private static final Set<String> FML4_LINES = Set.of("20.4", "20.5", "20.6", "21.0", "21.1");
    /** NeoForge 20.4 runs FML 2.0.x on ModLauncher 10 (the shape of Forge 47's), not FML 4. */
    private static final Set<String> FML2_LINES = Set.of("20.4");

    private final GameHost.Request request;
    private final NeoForgeInstallation.Prepared files;
    private final ForgeBootModules.Libraries libraries;
    private final List<Path> modJars;
    private final Path hostJar;
    private final List<BridgeLaunchContext.LoadedMod> hostMods;
    private final List<String> leftOutMods;
    /** Copies of the nested jars whose Mixin configurations a shared Mixin applies ({@link ForgeMixinConfigs}). */
    private final List<Path> nestedMixinJars;

    private final List<Path> declined;
    private ForeignModIndex otherMods = ForeignModIndex.EMPTY;
    private ForgeLaunch launch;

    private NeoForgeFml4GameHost(
            GameHost.Request request,
            NeoForgeInstallation.Prepared files,
            ForgeBootModules.Libraries libraries,
            List<Path> modJars,
            Path hostJar,
            List<BridgeLaunchContext.LoadedMod> hostMods,
            List<String> leftOutMods,
            List<Path> nestedMixinJars,
            List<Path> declined) {
        this.request = request;
        this.files = files;
        this.libraries = libraries;
        this.modJars = List.copyOf(modJars);
        this.hostJar = hostJar;
        this.hostMods = List.copyOf(hostMods);
        this.leftOutMods = List.copyOf(leftOutMods);
        this.nestedMixinJars = List.copyOf(nestedMixinJars);
        this.declined = List.copyOf(declined);
    }

    /** Whether the NeoForge release {@code neoForgeVersion} ({@code 20.4.253}) runs FML 2 rather than FML 4. */
    static boolean usesFml2(String neoForgeVersion) {
        String[] parts = neoForgeVersion.split("\\.");
        return parts.length >= 2 && FML2_LINES.contains(parts[0] + "." + parts[1]);
    }

    /** Whether the NeoForge release {@code neoForgeVersion} ({@code 21.1.265}) runs on FML 4 or 2, that is on ModLauncher. */
    static boolean runsOnModLauncher(String neoForgeVersion) {
        String[] parts = neoForgeVersion.split("\\.");
        return parts.length >= 2 && FML4_LINES.contains(parts[0] + "." + parts[1]);
    }

    /**
     * Prepares NeoForge's files for {@code request}'s Minecraft version, to run {@code modJars} (the NeoForge mods Plexus
     * Mod Loader found).
     *
     * @throws IOException when Plexus Mod Loader has no NeoForge release for that version, FML's file systems cannot be
     *     put on the JVM class path, the files cannot be downloaded, checked or patched, or, with Fabric or Quilt mods in
     *     the same launch, FML's hand-over of Mixin configurations is not the code Plexus Mod Loader checked
     */
    static NeoForgeFml4GameHost create(
            GameHost.Request request, NeoForgeInstallation.Release release, List<Path> modJars) throws IOException {
        if (request.jdkModuleAccess().isEmpty()) {
            throw new IOException(
                    "NeoForge's " + (usesFml2(release.neoForgeVersion()) ? "FML 2" : "FML 4")
                            + " needs java.lang.invoke opened to it, as NeoForge's launcher profile does"
                            + " with --add-opens; that takes Plexus Mod Loader's Java agent, and this launch was started without it");
        }
        ForgeFileSystems fileSystems = ForgeFileSystems.of(request.systemClassPath());
        fileSystems.checkAvailable();
        NeoForgeInstallation.Prepared files = NeoForgeInstallation.prepare(
                release,
                request.cacheDirectory().resolve("neoforge"),
                request.side(),
                request.vanillaGameJar(),
                ForgeInstallation.httpDownloader(),
                request.log());
        if (request.sharedMixin()) {
            checkSharedMixin(files);
        }
        // Before sorting, so the libraries the class path now has are shared rather than defined as modules.
        fileSystems.install(fileSystemJars(files), request.log());
        // With Fabric or Quilt mods, FML's Mixin is left out: the Mixin on Plexus Mod Loader's class path serves
        // NeoForge's mods too, as with MinecraftForge (ADR-0021 G8).
        ForgeBootModules.Libraries libraries = ForgeBootModules.sort(
                files.libraries(),
                NeoForgeFml4GameHost.class.getClassLoader(),
                !request.sharedMixin(),
                flavor(files.release().neoForgeVersion()));
        ForgeModRequirements.Result requirements = runnableMods(request, files, modJars);
        List<Path> runnable = requirements.kept();
        List<String> leftOut = requirements.excluded().stream()
                .map(ForgeModRequirements.Excluded::modId)
                .toList();
        List<Path> nestedMixinJars = request.sharedMixin()
                ? ForgeMixinConfigs.extractNested(runnable, files.directory().resolve("nested-mixin-jars"))
                : List.of();
        // With Fabric's Mixin shared, a jar built for several loaders that FML would refuse may run by its other
        // metadata instead (declinedJars), as on NeoForge 26.x.
        List<Path> declined = request.sharedMixin()
                ? modJars.stream().filter(jar -> !runnable.contains(jar)).toList()
                : List.of();
        return new NeoForgeFml4GameHost(
                request,
                files,
                libraries,
                runnable,
                extractHostJar(files.directory(), usesFml2(release.neoForgeVersion())),
                NeoForgeModRequirements.hostMods(runnable, usesFml2(release.neoForgeVersion())),
                leftOut,
                nestedMixinJars,
                declined);
    }

    /**
     * With Fabric or Quilt mods in the launch, whether FML's {@code LoadingModList} hands its mods' Mixin configurations
     * over as Plexus Mod Loader checked ({@link Fml4SharedMixinPatch}), before NeoForge starts: otherwise FML would reach
     * for a Mixin of its own, so NeoForge's mods are left out ({@code PLEXUS-COMPAT-216}, by the runtime) and the other
     * mods run.
     */
    private static void checkSharedMixin(NeoForgeInstallation.Prepared files) throws IOException {
        Path loader = fmlLoaderJar(files);
        List<String> missing;
        try (JarFile jar = new JarFile(loader.toFile())) {
            var entry = jar.getEntry(Fml4SharedMixinPatch.CLASS_FILE);
            if (entry == null) {
                throw new IOException(loader.getFileName() + " has no " + Fml4SharedMixinPatch.CLASS_FILE);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                missing = Fml4SharedMixinPatch.missing(input.readAllBytes());
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("NeoForge mods do not run together with Fabric or Quilt mods on "
                    + loader.getFileName() + ": FML's Mixin hand-over is not the code Plexus Mod Loader checked"
                    + " (missing: " + String.join("; ", missing) + "), and two Mixins cannot run in one game");
        }
    }

    /** FML's {@code loader} jar among NeoForge's libraries ({@code net/neoforged/fancymodloader/loader/}). */
    private static Path fmlLoaderJar(NeoForgeInstallation.Prepared files) throws IOException {
        return files.libraries().stream()
                .filter(jar -> jar.getFileName().toString().startsWith("loader-")
                        && jar.getParent().getParent().getFileName().toString().equals("loader"))
                .findFirst()
                .orElseThrow(() -> new IOException("NeoForge's libraries have no FancyModLoader loader jar"));
    }

    private boolean fml2() {
        return usesFml2(files.release().neoForgeVersion());
    }

    private static ForgeBootModules.Flavor flavor(String neoForgeVersion) {
        return usesFml2(neoForgeVersion)
                ? ForgeBootModules.Flavor.NEOFORGE_FML2
                : ForgeBootModules.Flavor.NEOFORGE_FML4;
    }

    /** The jars of FML's file systems among NeoForge's libraries: securejarhandler ({@code union}) and JarJarFileSystems. */
    static List<Path> fileSystemJars(NeoForgeInstallation.Prepared files) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (String prefix : List.of("securejarhandler-", "JarJarFileSystems-")) {
            jars.add(files.libraries().stream()
                    .filter(jar -> jar.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new IOException("NeoForge's libraries have no " + prefix
                            + "* jar, whose file system FML needs on the JVM" + " class path")));
        }
        return List.copyOf(jars);
    }

    /** As {@link NeoForgeGameHost}: the mods failing their {@code neoforge.mods.toml} requirements are left out first. */
    private static ForgeModRequirements.Result runnableMods(
            GameHost.Request request, NeoForgeInstallation.Prepared files, List<Path> modJars) throws IOException {
        Path mavenArtifact = files.libraries().stream()
                .filter(jar -> jar.getFileName().toString().startsWith("maven-artifact-"))
                .findFirst()
                .orElseThrow(() -> new IOException("NeoForge's libraries have no maven-artifact* jar"));
        Path loader = fmlLoaderJar(files);
        String javaLanguage = NeoForgeGameHost.javaLanguageVersion(loader);
        String neoForgeVersion = files.release().neoForgeVersion();
        try (URLClassLoader maven = new URLClassLoader(
                new URL[] {mavenArtifact.toUri().toURL()}, NeoForgeFml4GameHost.class.getClassLoader())) {
            ForgeModRequirements.Result result = NeoForgeModRequirements.check(
                    modJars,
                    request.gameVersion(),
                    neoForgeVersion,
                    javaLanguage,
                    request.side(),
                    ForgeModRequirements.maven(maven));
            for (ForgeModRequirements.Excluded excluded : result.excluded()) {
                request.log()
                        .accept("PLEXUS-COMPAT-217 NeoForge mod " + excluded.modId() + " ("
                                + excluded.jar().getFileName() + ") is NOT loaded: "
                                + String.join("; ", excluded.reasons()));
            }
            if (!result.excluded().isEmpty()) {
                request.log()
                        .accept("NeoForge " + neoForgeVersion + ": "
                                + result.kept().size() + " of " + modJars.size()
                                + " NeoForge mod file(s) meet their requirements; "
                                + result.excluded().size()
                                + " left out (PLEXUS-COMPAT-217)");
            }
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IOException("NeoForge's maven-artifact cannot check version ranges: " + e, e);
        }
    }

    @Override
    public String name() {
        return "NeoForge " + files.release().neoForgeVersion();
    }

    @Override
    public Path gameJar() {
        return files.patchedGame();
    }

    /**
     * FML's BOOT modules and NeoForge's universal jar, which own their packages in the game class loader: the runtime's
     * link check reads them, so that a Fabric mod with its own copy of FML's classes built against another release
     * (Forge Config API Port's Fabric build: {@code ConfigTracker.unloadConfigs}) is left out ({@code PLEXUS-COMPAT-141}).
     */
    @Override
    public List<Path> loaderJars() {
        List<Path> jars = new ArrayList<>(libraries.modules());
        jars.add(files.universal());
        return jars;
    }

    @Override
    public List<Path> resourceJars() {
        List<Path> jars = new ArrayList<>(libraries.modFiles());
        jars.add(files.patchedGame());
        jars.addAll(modJars);
        jars.addAll(nestedMixinJars);
        return jars;
    }

    @Override
    public void prepare(GameModuleLayers layers, GameClassBytes classes) throws Exception {
        // FML's LoadingModList is read through Fml4SharedMixinPatch while FML discovers its mods, inside start().
        try (URLClassLoader hierarchyJars =
                new URLClassLoader(urls(libraries.modules()), NeoForgeFml4GameHost.class.getClassLoader())) {
            ClassHierarchyResolver hierarchy = ClassHierarchyResolver.ofResourceParsing(hierarchyJars)
                    .orElse(ClassHierarchyResolver.defaultResolver());
            start(layers, classes, hierarchy);
        }
    }

    private void start(GameModuleLayers layers, GameClassBytes classes, ClassHierarchyResolver hierarchy)
            throws Exception {
        UnaryOperator<ModuleReference> library = request.sharedMixin()
                ? reference -> Fml4SharedMixinPatch.patching(reference, hierarchy, result -> {
                    if (!result.missing().isEmpty()) {
                        throw new IllegalStateException("PLEXUS-COMPAT-215 FML's Mixin hand-over is not the code Plexus"
                                + " Mod Loader checked (" + name() + "); missing: " + result.missing());
                    }
                    if (!result.changed().isEmpty()) {
                        request.log().accept("FML's Mixin: " + result.changed() + " (ADR-0043)");
                    }
                })
                : UnaryOperator.identity();
        ModuleLayer boot = ForgeBootModules.define(
                libraries,
                hostJar,
                layers,
                NeoForgeFml4GameHost.class.getClassLoader(),
                library,
                flavor(files.release().neoForgeVersion()));
        request.log()
                .accept(name() + ": " + boot.modules().size() + " boot module(s), "
                        + libraries.modFiles().size()
                        + " mod file(s) for FML, " + libraries.onClassPath().size()
                        + " librar(y/ies) shared with the game");
        // FML 4 (and securejarhandler on the JVM class path, which its file systems need there) reaches into
        // java.lang.invoke; NeoForge's launcher profile opens it with --add-opens.
        Set<Module> reaching = new LinkedHashSet<>(boot.modules());
        reaching.add(ClassLoader.getSystemClassLoader().getUnnamedModule());
        reaching.add(NeoForgeFml4GameHost.class.getClassLoader().getUnnamedModule());
        request.jdkModuleAccess().orElseThrow().open(Object.class.getModule(), "java.lang.invoke", reaching);
        UnionUrls.install(boot);
        Module modLauncher = boot.findModule(ForgeBootModules.MODLAUNCHER).orElseThrow();
        Class<?> host = Class.forName(modLauncher, ForgeBootModules.HOST_PACKAGE + ".PlexusModLauncher");
        if (host == null) {
            throw new IllegalStateException("neoforge-fml4-host.jar has no PlexusModLauncher");
        }
        // FML 4 finds Minecraft, NeoForge and its libraries below this folder (LibraryFinder), as in NeoForge's
        // installation.
        System.setProperty("libraryDirectory", files.libraryDirectory().toString());
        if (fml2()) {
            // FML 2's launch handlers read these from the JVM arguments of NeoForge's launcher profile (version.json):
            // the class path they look for FML's own libraries in, and the libraries FML puts into its plugin and game
            // layers by name (none: Plexus Mod Loader defines FML's layers itself).
            System.setProperty(
                    "legacyClassPath",
                    files.libraries().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
            System.setProperty("fml.pluginLayerLibraries", "");
            System.setProperty("fml.gameLayerLibraries", "");
        }
        // Read by PlexusModsLocator4, the FML service that gives FML the mods Plexus Mod Loader found.
        System.setProperty(
                "plexus.forge.mods",
                modJars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        Method start = host.getMethod("start", ForgeLaunch.Request.class);
        try {
            launch = (ForgeLaunch) start.invoke(
                    null,
                    new ForgeLaunch.Request(
                            launchTarget(request.launchTarget()),
                            request.gameVersion(),
                            request.gameDirectory(),
                            launchArguments(),
                            modJars,
                            otherMods,
                            boot,
                            layers,
                            classes,
                            layerModules(),
                            request.sharedMixin(),
                            request.log()));
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * The modules of the game layer without their requires of the libraries Plexus Mod Loader's class path provides: those
     * are not modules of the BOOT layer here ({@link ForgeBootModules.Libraries#onClassPath}), and a mod's {@code module-info}
     * that requires one ({@code org.slf4j}, as Yumi's libraries do) would not resolve. The classes come from the class path.
     * With Fabric or Quilt mods in the launch, also without the packages of Fabric Loader's API ({@link
     * FabricApiPackagesInModules}).
     */
    private UnaryOperator<ModuleReference> layerModules() {
        Set<String> shared = new LinkedHashSet<>();
        for (Path library : libraries.onClassPath()) {
            ModuleFinder.of(library)
                    .findAll()
                    .forEach(module -> shared.add(module.descriptor().name()));
        }
        UnaryOperator<ModuleReference> requires = reference -> {
            Set<String> kept = new LinkedHashSet<>();
            reference.descriptor().requires().forEach(require -> kept.add(require.name()));
            if (kept.stream().noneMatch(shared::contains)) {
                return reference;
            }
            kept.removeAll(shared);
            return ForgeBootModules.withRequires(reference, kept);
        };
        if (!request.sharedMixin()) {
            return requires;
        }
        // With Fabric or Quilt mods, a NeoForge mod's copy of Fabric Loader's API classes does not own their packages.
        return reference -> FabricApiPackagesInModules.without(
                requires.apply(reference),
                dropped -> request.log()
                        .accept("FML's module " + reference.descriptor().name() + " leaves " + dropped
                                + " to Plexus Mod Loader's Fabric Loader API (ADR-0043)"));
    }

    /**
     * The game's arguments, then the {@code --fml.*} options of NeoForge's launcher profile (its {@code version.json}):
     * FML 4 reads the NeoForge, FML, Minecraft and NeoForm versions from them. The profile's {@code --launchTarget} is left
     * out; Plexus Mod Loader's launch target is given separately.
     */
    List<String> launchArguments() {
        List<String> arguments = new ArrayList<>(request.gameArguments());
        List<String> profile = files.gameArguments();
        for (int i = 0; i < profile.size(); i++) {
            String argument = profile.get(i);
            if (argument.equals("--launchTarget")) {
                i++;
                continue;
            }
            if (argument.startsWith("--fml.") && arguments.contains(argument)) {
                i++;
                continue;
            }
            arguments.add(argument);
        }
        return arguments;
    }

    /**
     * NeoForge's launch handler for Plexus Mod Loader's launch target: the client's and the dedicated server's own, and
     * Plexus Mod Loader's {@code GameTestLaunchHandler4} for the game test server, which NeoForge runs only outside
     * production.
     */
    static String launchTarget(String plexusTarget) {
        return switch (plexusTarget) {
            case "client" -> "plexusmodloader_client";
            case "game_test_server" -> "plexusmodloader_server_gametest";
            default -> "plexusmodloader_server";
        };
    }

    private static URL[] urls(List<Path> jars) throws IOException {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = jars.get(i).toUri().toURL();
        }
        return urls;
    }

    /**
     * With Fabric or Quilt mods in the launch, the jars FML would refuse ({@code PLEXUS-COMPAT-217}); the runtime runs one
     * built for another loader too by that loader's metadata (ADR-0026 35).
     */
    @Override
    public List<Path> declinedJars() {
        return declined;
    }

    /** With Fabric or Quilt mods in the launch, Fabric's Mixin serves NeoForge's mods too (ADR-0043 point 10). */
    @Override
    public boolean sharesMixin() {
        return request.sharedMixin();
    }

    /** The configurations FML found for its mods once it started ({@link ForgeLaunch#sharedMixinConfigs()}). */
    @Override
    public List<SharedMixinConfig> sharedMixinConfigs() {
        return launch == null ? List.of() : launch.sharedMixinConfigs();
    }

    /** Where NeoForge's patch moved vanilla calls that Fabric mods inject at ({@link NeoForgeInjectionPoints}). */
    @Override
    public List<InjectionPointReplacement> injectionPointReplacements() {
        return NeoForgeInjectionPoints.all();
    }

    /**
     * Other loaders' mods initialize with NeoForge's registries open, as NeoForge's mods register during its {@code
     * RegisterEvent} ({@link NeoForgeRegistryOpening}).
     */
    @Override
    public void runForeignModInitialization(Runnable initialization) {
        if (launch == null) {
            throw new IllegalStateException("NeoForge was not prepared");
        }
        ClassLoader game = launch.gameLayer().modules().iterator().next().getClassLoader();
        try {
            NeoForgeRegistryOpening.run(game, initialization);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "NeoForge's registries could not be opened for other loaders' mods (" + e + ")", e);
        }
    }

    @Override
    public List<String> leftOutMods() {
        return leftOutMods;
    }

    @Override
    public Set<String> modIds() {
        Set<String> ids = new LinkedHashSet<>();
        hostMods.forEach(mod -> ids.add(mod.modId()));
        return Set.copyOf(ids);
    }

    @Override
    public List<BridgeLaunchContext.LoadedMod> mods() {
        return hostMods;
    }

    @Override
    public void otherMods(List<BridgeLaunchContext.LoadedMod> mods) {
        otherMods = ForeignModIndex.of(mods, modIds());
    }

    @Override
    public Optional<BridgeClassTransformer> classTransformer() {
        return Optional.ofNullable(launch).map(ForgeLaunch::classTransformer);
    }

    @Override
    public void launch() throws Throwable {
        if (launch == null) {
            throw new IllegalStateException("NeoForge was not prepared");
        }
        System.setProperty("fml.earlyprogresswindow", "false");
        launch.launch();
    }

    /**
     * Copies Plexus Mod Loader's {@code neoforge-fml4-host.jar} (or the FML 2 one for NeoForge 20.4) next to NeoForge's
     * files, replacing an older copy.
     */
    private static Path extractHostJar(Path directory, boolean fml2) throws IOException {
        String resource = fml2 ? HOST_JAR_FML2 : HOST_JAR;
        String fileName = fml2 ? "plexusmodloader-fml2-host.jar" : "plexusmodloader-fml4-host.jar";
        byte[] bytes;
        try (InputStream in = NeoForgeFml4GameHost.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Plexus Mod Loader's NeoForge bridge has no " + resource);
            }
            bytes = in.readAllBytes();
        }
        Path target = directory.resolve(fileName);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
            return target;
        }
        Path partial = directory.resolve(fileName + ".partial");
        Files.write(partial, bytes);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }
}
