package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.GameModuleLayers;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * FML's BOOT layer on Plexus Mod Loader (ADR-0021): Forge's loader libraries as modules of the game class loader, the way
 * Forge's {@code ForgeBootstrap} makes them modules, with these differences:
 *
 * <ul>
 *   <li>Libraries Plexus Mod Loader's class path already has (Minecraft's libraries, ASM) are not duplicated; modules that
 *       require them read Plexus Mod Loader's class path instead, and what they export only to such a module is exported to
 *       the class path. Forge's Mixin is the exception: it stays a module, ahead of the Fabric fork on the class path.
 *       As in Forge's own BOOT layer (securemodules), a jar's {@code module-info} becomes an open module.
 *   <li>{@code cpw.mods.modlauncher} keeps ModLauncher's API packages but takes {@code Launcher}, {@code Environment}
 *       and the launch itself ({@code dev.plexusmodloader.compat.forge.modlauncher}) from Plexus Mod Loader's {@code
 *       forge-host.jar}; ModLauncher's own implementation classes are left out.
 *   <li>Mod files (a {@code META-INF/mods.toml}, or an {@code FMLModType} in the manifest) are not modules here; FML
 *       finds them as resources and puts them into the PLUGIN and GAME layers itself.
 *   <li>FML does not scan the mods folder ({@code ModsFolderLocator} is not provided). Plexus Mod Loader found the Forge mods
 *       there already and hands FML those as resources; FML would otherwise stop at every Fabric and Plexus mod in the
 *       folder as "not a Forge mod".
 *   <li>night-config's {@code ConfigFormat} closes the file it opens for an empty config ({@link
 *       NightConfigEmptyFilePatch}); left open, Windows refuses to replace that file on the next save.
 * </ul>
 */
final class ForgeBootModules {

    static final String MODLAUNCHER = "cpw.mods.modlauncher";
    static final String HOST_PACKAGE = "dev.plexusmodloader.compat.forge.modlauncher";
    private static final String FMLLOADER = "net.minecraftforge.fmlloader";
    /**
     * Further BOOT modules Plexus Mod Loader's part of {@code cpw.mods.modlauncher} reads when they are modules (a library
     * on Plexus Mod Loader's class path is read through it anyway): forgespi and maven-artifact (ADR-0025).
     */
    private static final List<String> HOST_READS = List.of("net.minecraftforge.forgespi", "maven.artifact");

    private static final String IMPLEMENTATION_PREFIX = "cpw/mods/modlauncher/";
    private static final String MIXIN_BOOTSTRAP = "org/spongepowered/asm/launch/MixinBootstrap.class";
    /** Forge's MixinExtras library ({@code mixinextras-forge}), found by its configuration plugin. */
    private static final String MIXINEXTRAS_FORGE =
            "com/llamalad7/mixinextras/platform/forge/MixinExtrasConfigPlugin.class";
    /** Service implementations the BOOT layer leaves out; see the class comment. */
    private static final Set<String> LEFT_OUT_PROVIDERS =
            Set.of("net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator");

    private static final String LAUNCH_HANDLER_SERVICE = "cpw.mods.modlauncher.api.ILaunchHandlerService";

    /**
     * What differs between the FML families this BOOT layer is made for: MinecraftForge's FML (47 and later) and NeoForge's
     * FML 4 (21.1, 20.4; ADR-0043), which runs on ModLauncher too with its types in {@code net.neoforged}.
     *
     * @param fmlLoaderModule the module name of FML's loader jar, which Plexus Mod Loader's launch handlers extend
     * @param hostReads further BOOT modules Plexus Mod Loader's part of {@code cpw.mods.modlauncher} reads when present
     * @param leftOutProviders service implementations the layer leaves out (FML's mods folder scan)
     * @param manifestMarksModFiles whether a jar with an {@code FMLModType} manifest entry is a mod file FML finds itself
     *     (Forge's fmlcore and language providers) rather than a library
     * @param hostUses services Plexus Mod Loader's part of {@code cpw.mods.modlauncher} loads that its jar does not declare
     * @param hostProvides the services Plexus Mod Loader's part of {@code cpw.mods.modlauncher} provides, from the
     *     ModLauncher jar: launch handlers and, for NeoForge, the locator of the mods Plexus Mod Loader found
     */
    record Flavor(
            String fmlLoaderModule,
            List<String> hostReads,
            Set<String> leftOutProviders,
            Function<Path, Map<String, List<String>>> hostProvides,
            boolean manifestMarksModFiles,
            List<String> hostUses) {

        /** MinecraftForge's FML: 47 for Minecraft 1.20.1, 49 and later after it. */
        static final Flavor FORGE = new Flavor(
                FMLLOADER,
                HOST_READS,
                LEFT_OUT_PROVIDERS,
                jar -> Map.of(LAUNCH_HANDLER_SERVICE, launchHandlers(jar)),
                true,
                List.of());

        /** NeoForge's FML 4 on ModLauncher 11. */
        static final Flavor NEOFORGE_FML4 = new Flavor(
                "fml_loader",
                // NeoForgeForeignModList's mod files: neoforgespi (in fml_loader) and Maven versions (ADR-0025).
                List.of("maven.artifact"),
                Set.of("net.neoforged.fml.loading.moddiscovery.locators.ModsFolderLocator"),
                jar -> Map.of(
                        LAUNCH_HANDLER_SERVICE,
                        List.of(
                                HOST_PACKAGE + ".GameTestLaunchHandler4",
                                HOST_PACKAGE + ".ServerLaunchHandler4",
                                HOST_PACKAGE + ".ClientLaunchHandler4"),
                        "net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
                        List.of(HOST_PACKAGE + ".PlexusModsLocator4")),
                // FML 4's own libraries (loader, earlydisplay) carry FMLModType: LIBRARY and are BOOT modules.
                false,
                // ModLauncher 11 dropped the naming service Plexus Mod Loader's implementation still asks for.
                List.of("cpw.mods.modlauncher.api.INameMappingService"));

        /** NeoForge's FML 2 (NeoForge 20.4) on ModLauncher 10.0.9, with its types in {@code net.neoforged} (ADR-0043). */
        static final Flavor NEOFORGE_FML2 = new Flavor(
                "fml_loader",
                List.of("fml_spi", "maven.artifact"),
                Set.of("net.neoforged.fml.loading.moddiscovery.ModsFolderLocator"),
                jar -> Map.of(
                        LAUNCH_HANDLER_SERVICE,
                        List.of(
                                HOST_PACKAGE + ".GameTestLaunchHandler2",
                                HOST_PACKAGE + ".ServerLaunchHandler2",
                                HOST_PACKAGE + ".ClientLaunchHandler2")),
                // FML 2's own libraries (loader, spi, earlydisplay) carry FMLModType: LIBRARY and are BOOT modules.
                false,
                List.of());
    }

    /**
     * Forge's libraries sorted by where they go.
     *
     * @param modules jars that become BOOT layer modules
     * @param modLauncher ModLauncher's jar, whose API packages {@code cpw.mods.modlauncher} keeps
     * @param modFiles jars FML finds itself (Forge's {@code universal.jar}, {@code fmlcore}, language providers)
     * @param onClassPath jars left out because Plexus Mod Loader's class path has those classes (with a shared Mixin, Forge's
     *     Mixin too)
     */
    record Libraries(List<Path> modules, Path modLauncher, List<Path> modFiles, List<Path> onClassPath) {

        /**
         * These libraries with Forge's MixinExtras ({@code mixinextras-forge}) replaced by {@link MixinExtrasStub}'s copy
         * in {@code directory}, for a shared Mixin.
         */
        Libraries withSharedMixinExtras(Path directory) throws IOException {
            List<Path> files = new ArrayList<>();
            for (Path file : modFiles) {
                boolean mixinExtras;
                try (JarFile jar = new JarFile(file.toFile())) {
                    mixinExtras = contains(jar, MIXINEXTRAS_FORGE);
                }
                files.add(mixinExtras ? MixinExtrasStub.write(file, directory) : file);
            }
            return new Libraries(modules, modLauncher, List.copyOf(files), onClassPath);
        }
    }

    private ForgeBootModules() {}

    /**
     * Sorts {@code libraries}; {@code classPath} is Plexus Mod Loader's own class loader. With {@code ownMixin} false (Fabric
     * mods run in the same launch, ADR-0021 G8), Forge's Mixin is left out: the Mixin on Plexus Mod Loader's class path serves
     * Forge mods too, because the game class loader can hold only one (for MixinExtras see {@link
     * Libraries#withSharedMixinExtras}).
     */
    static Libraries sort(List<Path> libraries, ClassLoader classPath, boolean ownMixin) throws IOException {
        return sort(libraries, classPath, ownMixin, Flavor.FORGE);
    }

    /** {@link #sort(List, ClassLoader, boolean)} for the FML family {@code flavor}. */
    static Libraries sort(List<Path> libraries, ClassLoader classPath, boolean ownMixin, Flavor flavor)
            throws IOException {
        List<Path> modules = new ArrayList<>();
        List<Path> modFiles = new ArrayList<>();
        List<Path> onClassPath = new ArrayList<>();
        Path modLauncher = null;
        for (Path library : libraries) {
            try (JarFile jar = new JarFile(library.toFile())) {
                if (!ownMixin && contains(jar, MIXIN_BOOTSTRAP)) {
                    onClassPath.add(library);
                } else if (isModFile(jar, flavor.manifestMarksModFiles())) {
                    modFiles.add(library);
                } else if (contains(jar, "net/minecraftforge/bootstrap/ForgeBootstrap.class")) {
                    // Forge's own entry point, which Plexus Mod Loader replaces.
                    continue;
                } else if (contains(jar, IMPLEMENTATION_PREFIX + "Launcher.class")) {
                    modLauncher = library;
                } else if (contains(jar, MIXIN_BOOTSTRAP)) {
                    // Forge's Mixin (the original, with its ModLauncher services) stays a module although
                    // Plexus Mod Loader's class path has Fabric's fork of the same packages: the game class loader
                    // resolves
                    // a module's packages before its parent, so FML and Forge mods get Forge's Mixin.
                    modules.add(library);
                } else if (onClassPath(jar, classPath)) {
                    onClassPath.add(library);
                } else {
                    modules.add(library);
                }
            }
        }
        if (modLauncher == null) {
            throw new IOException("Forge's libraries have no ModLauncher jar");
        }
        return new Libraries(List.copyOf(modules), modLauncher, List.copyOf(modFiles), List.copyOf(onClassPath));
    }

    /**
     * Defines the BOOT layer in the game class loader. Every module reads Plexus Mod Loader's class path ({@code classPath}'s
     * unnamed module), where the libraries it shares with Minecraft are. {@code library} adjusts each library module as
     * it is read (Plexus Mod Loader's art in Forge's early window, {@link EarlyScreenArt}).
     */
    static ModuleLayer define(
            Libraries libraries,
            Path hostJar,
            GameModuleLayers layers,
            ClassLoader classPath,
            UnaryOperator<ModuleReference> library) {
        return define(libraries, hostJar, layers, classPath, library, Flavor.FORGE);
    }

    /** {@link #define(Libraries, Path, GameModuleLayers, ClassLoader, UnaryOperator)} for the FML family {@code flavor}. */
    static ModuleLayer define(
            Libraries libraries,
            Path hostJar,
            GameModuleLayers layers,
            ClassLoader classPath,
            UnaryOperator<ModuleReference> library,
            Flavor flavor) {
        Map<String, ModuleReference> references = new HashMap<>();
        for (ModuleReference reference :
                ModuleFinder.of(libraries.modules().toArray(Path[]::new)).findAll()) {
            references.put(reference.descriptor().name(), library.apply(reference));
        }
        references.computeIfPresent(
                NightConfigEmptyFilePatch.MODULE, (name, reference) -> NightConfigEmptyFilePatch.patching(reference));
        ModuleReference modLauncher = modLauncher(libraries.modLauncher(), hostJar, references.keySet(), flavor);
        references.put(MODLAUNCHER, modLauncher);
        Set<String> available = new HashSet<>(references.keySet());
        ModuleFinder.ofSystem()
                .findAll()
                .forEach(system -> available.add(system.descriptor().name()));
        Map<String, ModuleReference> rewritten = new HashMap<>();
        for (Map.Entry<String, ModuleReference> entry : references.entrySet()) {
            rewritten.put(entry.getKey(), withRequires(entry.getValue(), available, flavor.leftOutProviders()));
        }
        ModuleFinder finder = new ModuleFinder() {
            @Override
            public Optional<ModuleReference> find(String name) {
                return Optional.ofNullable(rewritten.get(name));
            }

            @Override
            public Set<ModuleReference> findAll() {
                return Set.copyOf(rewritten.values());
            }
        };
        Configuration configuration =
                ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), rewritten.keySet());
        ModuleLayer.Controller controller = layers.define(configuration, List.of(ModuleLayer.boot()), false);
        Module classPathModule = classPath.getUnnamedModule();
        for (Module module : controller.layer().modules()) {
            // With mods of another loader, the game class loader defines the class path's libraries itself (so that
            // Mixin can change them); its own unnamed module stands in for the class path then.
            List<Module> standIns =
                    List.of(classPathModule, module.getClassLoader().getUnnamedModule());
            for (Module standIn : standIns) {
                controller.addReads(module, standIn);
                // A package exported only to modules that Plexus Mod Loader's class path stands in for is exported to
                // the
                // class path instead.
                for (ModuleDescriptor.Exports exports : module.getDescriptor().exports()) {
                    if (exports.isQualified() && standsIn(exports.targets(), controller.layer())) {
                        controller.addExports(module, exports.source(), standIn);
                    }
                }
            }
        }
        return controller.layer();
    }

    /**
     * A module of the BOOT layer without the requires Plexus Mod Loader's class path answers instead, and without the
     * {@linkplain #LEFT_OUT_PROVIDERS left-out} service implementations.
     */
    static ModuleReference withRequires(ModuleReference reference, Set<String> available) {
        return withRequires(reference, available, LEFT_OUT_PROVIDERS);
    }

    private static ModuleReference withRequires(
            ModuleReference reference, Set<String> available, Set<String> leftOutProviders) {
        ModuleDescriptor descriptor = reference.descriptor();
        if (descriptor.isAutomatic()) {
            return withoutLeftOutProviders(reference, leftOutProviders);
        }
        // An open module, as securemodules reads a module-info for Forge's own BOOT layer: MixinExtras, a GAME layer
        // module, reflects into Mixin's internals.
        ModuleDescriptor.Builder builder = ModuleDescriptor.newOpenModule(descriptor.name());
        descriptor.version().ifPresent(builder::version);
        for (ModuleDescriptor.Requires requires : descriptor.requires()) {
            if (available.contains(requires.name())) {
                builder.requires(requires);
            }
        }
        descriptor.exports().forEach(builder::exports);
        descriptor.uses().forEach(builder::uses);
        provideKept(descriptor, builder, leftOutProviders);
        builder.packages(descriptor.packages());
        descriptor.mainClass().ifPresent(builder::mainClass);
        return delegate(builder.build(), reference);
    }

    /** An automatic module (FML's loader) as it is, but without the left-out service implementations. */
    private static ModuleReference withoutLeftOutProviders(ModuleReference reference, Set<String> leftOutProviders) {
        ModuleDescriptor descriptor = reference.descriptor();
        if (descriptor.provides().stream()
                .flatMap(provides -> provides.providers().stream())
                .noneMatch(leftOutProviders::contains)) {
            return reference;
        }
        ModuleDescriptor.Builder builder = ModuleDescriptor.newAutomaticModule(descriptor.name());
        descriptor.version().ifPresent(builder::version);
        provideKept(descriptor, builder, leftOutProviders);
        builder.packages(descriptor.packages());
        descriptor.mainClass().ifPresent(builder::mainClass);
        return delegate(builder.build(), reference);
    }

    private static void provideKept(
            ModuleDescriptor descriptor, ModuleDescriptor.Builder builder, Set<String> leftOutProviders) {
        for (ModuleDescriptor.Provides provides : descriptor.provides()) {
            List<String> kept = provides.providers().stream()
                    .filter(provider -> !leftOutProviders.contains(provider))
                    .toList();
            if (!kept.isEmpty()) {
                builder.provides(provides.service(), kept);
            }
        }
    }

    /**
     * {@code cpw.mods.modlauncher}: ModLauncher's API from its jar, and from {@code hostJar} Plexus Mod Loader's {@code
     * Launcher}, {@code Environment} and launch. ModLauncher's own implementation classes stay out, and so do the
     * services it provides with them.
     */
    private static ModuleReference modLauncher(
            Path modLauncherJar, Path hostJar, Set<String> available, Flavor flavor) {
        ModuleReference real =
                ModuleFinder.of(modLauncherJar).findAll().iterator().next();
        ModuleReference host = ModuleFinder.of(hostJar).findAll().iterator().next();
        ModuleDescriptor descriptor = real.descriptor();
        ModuleDescriptor.Builder builder = ModuleDescriptor.newModule(MODLAUNCHER, descriptor.modifiers());
        descriptor.version().ifPresent(builder::version);
        descriptor.requires().forEach(builder::requires);
        descriptor.exports().forEach(builder::exports);
        builder.exports(HOST_PACKAGE);
        descriptor.opens().forEach(builder::opens);
        descriptor.uses().forEach(builder::uses);
        // Plexus Mod Loader's game test launch handler extends FML's CommonLaunchHandler.
        builder.requires(flavor.fmlLoaderModule());
        // ForeignModList shows other mods as forgespi mod files, with Maven versions (ADR-0025).
        for (String module : flavor.hostReads()) {
            if (available.contains(module)
                    && descriptor.requires().stream().noneMatch(r -> r.name().equals(module))) {
                builder.requires(module);
            }
        }
        flavor.hostProvides().apply(modLauncherJar).forEach(builder::provides);
        flavor.hostUses().stream()
                .filter(use -> !descriptor.uses().contains(use))
                .forEach(builder::uses);
        Set<String> packages = new HashSet<>(descriptor.packages());
        packages.add(HOST_PACKAGE);
        builder.packages(packages);
        ModuleDescriptor overlay = builder.build();
        return new ModuleReference(overlay, real.location().orElse(null)) {
            @Override
            public ModuleReader open() throws IOException {
                return new OverlayReader(host.open(), real.open());
            }
        };
    }

    /**
     * The launch handler classes Plexus Mod Loader adds for this ModLauncher: Forge 47 (Minecraft 1.20.1) runs ModLauncher 10.0.x, whose FML
     * launch handlers have another shape ({@code GameTestLaunchHandler47}); later Forge builds run 10.1 and later (their own handlers serve the dedicated server and the client).
     */
    static List<String> launchHandlers(Path modLauncherJar) {
        String name = modLauncherJar.getFileName().toString();
        return name.startsWith("modlauncher-10.0.")
                ? List.of(
                        HOST_PACKAGE + ".GameTestLaunchHandler47",
                        HOST_PACKAGE + ".ServerLaunchHandler47",
                        HOST_PACKAGE + ".ClientLaunchHandler47")
                : List.of(HOST_PACKAGE + ".GameTestLaunchHandler");
    }

    private static ModuleReference delegate(ModuleDescriptor descriptor, ModuleReference original) {
        return new ModuleReference(descriptor, original.location().orElse(null)) {
            @Override
            public ModuleReader open() throws IOException {
                return original.open();
            }
        };
    }

    /**
     * Plexus Mod Loader's classes first, except {@code META-INF/} (ModLauncher's manifest carries its API packages' versions,
     * which FML checks); ModLauncher's implementation classes ({@code cpw/mods/modlauncher/*.class}) never.
     */
    private static final class OverlayReader implements ModuleReader {

        private final ModuleReader host;
        private final ModuleReader real;

        OverlayReader(ModuleReader host, ModuleReader real) {
            this.host = host;
            this.real = real;
        }

        @Override
        public Optional<URI> find(String name) throws IOException {
            if (name.startsWith("META-INF/")) {
                return real.find(name);
            }
            Optional<URI> own = host.find(name);
            return own.isPresent() || isImplementation(name) ? own : real.find(name);
        }

        @Override
        public Optional<InputStream> open(String name) throws IOException {
            if (name.startsWith("META-INF/")) {
                return real.open(name);
            }
            Optional<InputStream> own = host.open(name);
            return own.isPresent() || isImplementation(name) ? own : real.open(name);
        }

        @Override
        public Stream<String> list() throws IOException {
            return Stream.concat(host.list(), real.list().filter(name -> !isImplementation(name)))
                    .distinct();
        }

        @Override
        public void close() throws IOException {
            try {
                host.close();
            } finally {
                real.close();
            }
        }

        private static boolean isImplementation(String name) {
            return name.startsWith(IMPLEMENTATION_PREFIX)
                    && name.indexOf('/', IMPLEMENTATION_PREFIX.length()) < 0
                    && name.endsWith(".class");
        }
    }

    private static boolean isModFile(JarFile jar, boolean manifestMarks) throws IOException {
        if (jar.getEntry("META-INF/mods.toml") != null || jar.getEntry("META-INF/neoforge.mods.toml") != null) {
            return true;
        }
        var manifest = jar.getManifest();
        return manifestMarks
                && manifest != null
                && manifest.getMainAttributes().getValue(new Attributes.Name("FMLModType")) != null;
    }

    /** Whether none of {@code targets} is a module of {@code layer} or its parents, so the class path answers them. */
    private static boolean standsIn(Set<String> targets, ModuleLayer layer) {
        return targets.stream().noneMatch(target -> layer.findModule(target).isPresent());
    }

    private static boolean contains(JarFile jar, String entry) {
        return jar.getEntry(entry) != null;
    }

    /** Whether Plexus Mod Loader's class path already has this jar's classes (checked with its first class). */
    private static boolean onClassPath(JarFile jar, ClassLoader classPath) {
        Enumeration<? extends ZipEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class")) {
                if (classPath.getResource(name) == null) {
                    return false;
                }
                return !versionSensitive(jar) || sameBytes(jar, entry, classPath, name);
            }
        }
        return false;
    }

    /**
     * Libraries whose generated code only runs on the very release it was generated for: ANTLR's serialized ATN has a
     * version (Forge 49's access transformers, generated with 4.9.1, stop on Plexus Mod Loader's 4.11.1: "Could not
     * deserialize ATN with version 3 (expected 4)"). These are shared with the game only when the class path has the
     * same bytes, otherwise Forge gets its own copy as a module (ADR-0038).
     */
    private static boolean versionSensitive(JarFile jar) {
        String file = java.nio.file.Path.of(jar.getName()).getFileName().toString();
        return file.startsWith("antlr4-runtime-");
    }

    private static boolean sameBytes(JarFile jar, ZipEntry entry, ClassLoader classPath, String name) {
        try (java.io.InputStream mine = jar.getInputStream(entry);
                java.io.InputStream theirs = classPath.getResourceAsStream(name)) {
            return theirs != null && java.util.Arrays.equals(mine.readAllBytes(), theirs.readAllBytes());
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
