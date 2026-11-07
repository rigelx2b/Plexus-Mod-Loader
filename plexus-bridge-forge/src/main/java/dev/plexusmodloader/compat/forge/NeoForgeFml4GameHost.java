package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.BridgeClassTransformer;
import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.compat.bridge.GameHost;
import dev.plexusmodloader.compat.bridge.GameModuleLayers;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.reflect.Field;
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
 * <p>Not yet: running together with Fabric or Quilt mods (a shared Mixin) and FML's mod list answering for other loaders'
 * mods; {@link #create} refuses those launches.
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
    private ForeignModIndex otherMods = ForeignModIndex.EMPTY;
    private ForgeLaunch launch;

    private NeoForgeFml4GameHost(
            GameHost.Request request,
            NeoForgeInstallation.Prepared files,
            ForgeBootModules.Libraries libraries,
            List<Path> modJars,
            Path hostJar,
            List<BridgeLaunchContext.LoadedMod> hostMods,
            List<String> leftOutMods) {
        this.request = request;
        this.files = files;
        this.libraries = libraries;
        this.modJars = List.copyOf(modJars);
        this.hostJar = hostJar;
        this.hostMods = List.copyOf(hostMods);
        this.leftOutMods = List.copyOf(leftOutMods);
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
     * @throws IOException when Plexus Mod Loader has no NeoForge release for that version, Fabric mods run in the same
     *     launch, FML's file systems cannot be put on the JVM class path, or the files cannot be downloaded, checked or
     *     patched
     */
    static NeoForgeFml4GameHost create(
            GameHost.Request request, NeoForgeInstallation.Release release, List<Path> modJars) throws IOException {
        if (request.sharedMixin()) {
            throw new IOException(
                    "NeoForge " + release.neoForgeVersion() + " does not run together with Fabric or Quilt"
                            + " mods yet: " + (usesFml2(release.neoForgeVersion()) ? "FML 2" : "FML 4")
                            + " would start a second Mixin, and two Mixins cannot run in one game");
        }
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
        // Before sorting, so the libraries the class path now has are shared rather than defined as modules.
        fileSystems.install(fileSystemJars(files), request.log());
        ForgeBootModules.Libraries libraries = ForgeBootModules.sort(
                files.libraries(),
                NeoForgeFml4GameHost.class.getClassLoader(),
                true,
                flavor(files.release().neoForgeVersion()));
        ForgeModRequirements.Result requirements = runnableMods(request, files, modJars);
        List<Path> runnable = requirements.kept();
        List<String> leftOut = requirements.excluded().stream()
                .map(ForgeModRequirements.Excluded::modId)
                .toList();
        return new NeoForgeFml4GameHost(
                request,
                files,
                libraries,
                runnable,
                extractHostJar(files.directory(), usesFml2(release.neoForgeVersion())),
                NeoForgeModRequirements.hostMods(runnable, usesFml2(release.neoForgeVersion())),
                leftOut);
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
        Path loader = files.libraries().stream()
                .filter(jar -> jar.getFileName().toString().startsWith("loader-")
                        && jar.getParent().getParent().getFileName().toString().equals("loader"))
                .findFirst()
                .orElseThrow(() -> new IOException("NeoForge's libraries have no FancyModLoader loader jar"));
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

    @Override
    public List<Path> resourceJars() {
        List<Path> jars = new ArrayList<>(libraries.modFiles());
        jars.add(files.patchedGame());
        jars.addAll(modJars);
        return jars;
    }

    @Override
    public void prepare(GameModuleLayers layers, GameClassBytes classes) throws Exception {
        ModuleLayer boot = ForgeBootModules.define(
                libraries,
                hostJar,
                layers,
                NeoForgeFml4GameHost.class.getClassLoader(),
                reference -> reference,
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
        installUnionUrls(boot);
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
                            sharedLibraryRequires(),
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
     */
    private UnaryOperator<ModuleReference> sharedLibraryRequires() {
        Set<String> shared = new LinkedHashSet<>();
        for (Path library : libraries.onClassPath()) {
            ModuleFinder.of(library)
                    .findAll()
                    .forEach(module -> shared.add(module.descriptor().name()));
        }
        return reference -> {
            Set<String> kept = new LinkedHashSet<>();
            reference.descriptor().requires().forEach(requires -> kept.add(requires.name()));
            if (kept.stream().noneMatch(shared::contains)) {
                return reference;
            }
            kept.removeAll(shared);
            return ForgeBootModules.withRequires(reference, kept);
        };
    }

    /**
     * Makes {@code union:} URLs (the locations of FML's jar modules) work: securejarhandler 3 has no URL handler service.
     * NeoForge's bootstrap sets {@code ModularURLHandler} as the JVM's URL stream handler factory; so does this.
     */
    private void installUnionUrls(ModuleLayer boot) throws ReflectiveOperationException {
        Class<?> handler = Class.forName("cpw.mods.cl.ModularURLHandler", true, ClassLoader.getSystemClassLoader());
        handler.getMethod("initFrom", ModuleLayer.class).invoke(null, boot);
        Object instance = handler.getField("INSTANCE").get(null);
        if (((java.net.URLStreamHandlerFactory) instance).createURLStreamHandler("union") == null) {
            // securejarhandler 2 (FML 2) offers its handler only as a service of its module, and here the jar is on the
            // class path (FML's file systems need it there), not a module of the BOOT layer: register it by hand.
            Class<?> union = Class.forName("cpw.mods.cl.UnionURLStreamHandler", true, handler.getClassLoader());
            Class<?> provider =
                    Class.forName("cpw.mods.cl.ModularURLHandler$IURLProvider", true, handler.getClassLoader());
            Field handlers = handler.getDeclaredField("handlers");
            handlers.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> known = (java.util.Map<String, Object>) handlers.get(instance);
            Object created = union.getDeclaredConstructor().newInstance();
            known.put((String) provider.getMethod("protocol").invoke(created), created);
        }
        try {
            java.net.URL.setURLStreamHandlerFactory((java.net.URLStreamHandlerFactory) instance);
        } catch (Error alreadySet) {
            throw new IllegalStateException(
                    "The JVM's URL stream handler factory is set already, so union: URLs (FML's"
                            + " jar modules) cannot be opened: " + alreadySet,
                    alreadySet);
        }
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
