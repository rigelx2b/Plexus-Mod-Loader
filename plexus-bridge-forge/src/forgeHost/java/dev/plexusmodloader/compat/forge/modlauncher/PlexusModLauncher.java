package dev.plexusmodloader.compat.forge.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.Environment;
import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ILaunchHandlerService;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.INameMappingService;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.api.ServiceRunner;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import cpw.mods.modlauncher.serviceapi.ITransformerDiscoveryService;
import dev.plexusmodloader.compat.bridge.BridgeClassTransformer;
import dev.plexusmodloader.compat.bridge.SharedMixinConfig;
import dev.plexusmodloader.compat.forge.ForgeLaunch;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.fml.loading.ModDirTransformerDiscoverer;

/**
 * Plexus Mod Loader's implementation of ModLauncher's launch (ADR-0021 G3b): finds FML's services in the boot layer and runs
 * them in the order ModLauncher's {@code Launcher.run} does, but defines every module layer in Plexus Mod Loader's game class
 * loader instead of ModLauncher's class loaders:
 *
 * <ol>
 *   <li>launch plugins, launch handlers and naming services from the BOOT layer;
 *   <li>transformer discovery services, then the SERVICE layer with the jars they name;
 *   <li>transformation services from the SERVICE layer: {@code onLoad}, arguments, {@code initialize}, then {@code
 *       beginScanning};
 *   <li>the PLUGIN layer from the scan, then {@code completeScan}, then the GAME layer from it (transformed);
 *   <li>the services' transformers, the GAME jars offered to the launch plugins;
 *   <li>{@code initializeLaunch} for every plugin, at the end of the setup (before the game layer loads Minecraft classes);
 *   <li>on {@link #launch()}: the launch handler.
 * </ol>
 *
 * Written for Plexus Mod Loader from ModLauncher's public API and its documented order; not ModLauncher's code.
 */
public final class PlexusModLauncher implements ForgeLaunch, IModuleLayerManager {

    /** The ModLauncher version whose API this implements; FML logs it and reads it as the launcher version. */
    private static final String MODLAUNCHER_VERSION = "10.2.6";
    /** {@link GameTestLaunchHandler}'s name. */
    private static final String GAME_TEST_TARGET = "plexusmodloader_server_gametest";

    private final Request request;
    private final Map<Layer, ModuleLayer> layers = new EnumMap<>(Layer.class);
    /** Holds the launch plugins; a mod may replace its map through reflection ({@link LaunchPluginHandler}). */
    private final LaunchPluginHandler launchPlugins = new LaunchPluginHandler();

    private final Map<String, ILaunchHandlerService> handlers = new HashMap<>();
    private final Map<String, INameMappingService> namingServices = new HashMap<>();
    private final Map<String, INameMappingService> activeNaming = new HashMap<>();
    private final Map<String, ITransformationService> services = new LinkedHashMap<>();
    private final List<Map<String, String>> modList = new ArrayList<>();
    private final Environment environment;
    private final ForgeClassTransformer transformer;
    private ILaunchHandlerService handler;
    private List<SharedMixinConfig> sharedMixinConfigs = List.of();

    private PlexusModLauncher(Request request) {
        this.request = request;
        this.environment = new Environment(
                name -> Optional.ofNullable(plugins().get(name)),
                name -> Optional.ofNullable(handlers.get(name)),
                () -> Optional.of(this),
                this::nameMapping);
        this.transformer = new ForgeClassTransformer(() -> plugins().values(), request.classes());
    }

    private Map<String, ILaunchPluginService> plugins() {
        return launchPlugins.plugins();
    }

    /**
     * Sets up FML up to the GAME layer; called by Plexus Mod Loader's Forge bridge through reflection, because this class lives
     * in the boot layer's {@code cpw.mods.modlauncher} module.
     */
    public static ForgeLaunch start(Request request) throws Exception {
        PlexusModLauncher launcher = new PlexusModLauncher(request);
        launcher.run();
        return launcher;
    }

    private void run() throws Exception {
        // Before FML's ModList exists: its lookups ask ForeignModList when FML has no answer (ADR-0025).
        try {
            ForeignModList.install(request.otherMods());
        } catch (NoClassDefFoundError e) {
            // NeoForge's FML 2 and 4 (ADR-0043) have no forgespi, and their ModList is not patched to ask for other
            // mods: NeoForge mods do not see Plexus Mods through it (ForgeModListPatch logs PLEXUS-COMPAT-211); the
            // other mods still run.
        }
        if (request.launchTarget().equals(GAME_TEST_TARGET)) {
            // The game test launch is not production, so that Forge runs game tests (ForgeGameTestHooks reads
            // FMLLoader.isProduction()). Forge's own "production" property makes everything else production, as on a
            // player's server (FMLEnvironment.production): RuntimeDistCleaner then checks Minecraft's classes only, not
            // the mods'.
            System.getProperties().putIfAbsent("production", "true");
            System.setProperty(
                    GameTestLaunchHandler.GAME_LAYER_MAIN,
                    "dev.plexusmodloader.game.layer.v"
                            + request.minecraftVersion().replaceAll("[.-]", "_") + ".gametest.GameTestMain");
        }
        // As ModLauncher received them: its launch target, then the game's arguments.
        List<String> launchArguments = new ArrayList<>(List.of("--launchTarget", request.launchTarget()));
        launchArguments.addAll(request.gameArguments());
        Launcher.install(environment, launchArguments.toArray(String[]::new), launchPlugins);
        environment.computePropertyIfAbsent(IEnvironment.Keys.MLSPEC_VERSION.get(), k -> MODLAUNCHER_VERSION);
        environment.computePropertyIfAbsent(IEnvironment.Keys.MLIMPL_VERSION.get(), k -> MODLAUNCHER_VERSION);
        environment.computePropertyIfAbsent(IEnvironment.Keys.MODLIST.get(), k -> modList);
        try {
            environment.computePropertyIfAbsent(IEnvironment.Keys.SECURED_JARS_ENABLED.get(), k -> false);
        } catch (NoSuchFieldError e) {
            // ModLauncher 11 (NeoForge's FML 4, ADR-0043) has no such key; nothing reads it there.
        }

        ModuleLayer boot = request.bootLayer();
        layers.put(Layer.BOOT, boot);
        for (ILaunchPluginService plugin : load(boot, ILaunchPluginService.class)) {
            plugins().put(plugin.name(), plugin);
            modList.add(Map.of("name", plugin.name(), "type", "PLUGINSERVICE", "file", fileOf(plugin)));
        }
        for (ILaunchHandlerService launchHandler : load(boot, ILaunchHandlerService.class)) {
            handlers.put(launchHandler.name(), launchHandler);
        }
        for (INameMappingService naming : load(boot, INameMappingService.class)) {
            namingServices.put(naming.mappingName(), naming);
        }
        request.log()
                .accept("ModLauncher services: launch plugins " + plugins().keySet() + ", launch handlers "
                        + handlers.keySet());

        // SERVICE layer: the Forge mods Plexus Mod Loader found that provide a transformation service, a mod locator or
        // a
        // dependency locator, which Forge's ModDirTransformerDiscoverer would take from mods/ (and FML's
        // ClasspathLocator then leaves out). Its own scan of mods/ would also take jars Plexus Mod Loader left out or
        // gave to
        // another loader, and misses mods Plexus Mod Loader reads from elsewhere; it still runs, as it loads FML's
        // paths
        // and
        // config.
        List<ITransformerDiscoveryService> discovery = load(boot, ITransformerDiscoveryService.class);
        for (ITransformerDiscoveryService service : discovery) {
            service.candidates(request.gameDirectory(), request.launchTarget());
        }
        List<SecureJar> serviceJars = new ArrayList<>();
        for (Path jar : request.modJars()) {
            if (isServiceProvider(jar)) {
                serviceJars.add(SecureJar.from(jar));
                ServiceJars.record(jar);
            }
        }
        ModuleLayer serviceLayer = define(Layer.SERVICE, serviceJars, false);
        String[] arguments = request.gameArguments().toArray(String[]::new);
        for (ITransformerDiscoveryService service : discovery) {
            service.earlyInitialization(request.launchTarget(), arguments);
        }

        for (ITransformationService service : load(serviceLayer, ITransformationService.class)) {
            services.put(service.name(), service);
            modList.add(Map.of("name", service.name(), "type", "TRANSFORMATIONSERVICE", "file", fileOf(service)));
        }
        List<String> failed = new ArrayList<>();
        for (ITransformationService service : services.values()) {
            try {
                service.onLoad(environment, services.keySet());
            } catch (IncompatibleEnvironmentException e) {
                failed.add(service.name() + ": " + e.getMessage());
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("ModLauncher transformation services refused this environment: " + failed);
        }
        offerArguments(arguments);
        for (ITransformationService service : services.values()) {
            service.initialize(environment);
        }
        try {
            environment.computePropertyIfAbsent(IEnvironment.Keys.NAMING.get(), k -> "mojang");
            String naming =
                    environment.getProperty(IEnvironment.Keys.NAMING.get()).orElse("mojang");
            for (INameMappingService service : namingServices.values()) {
                if (naming.equals(service.understanding().getValue())) {
                    activeNaming.put(service.understanding().getKey(), service);
                }
            }
        } catch (NoSuchFieldError e) {
            // ModLauncher 11 (NeoForge's FML 4, ADR-0043) has no naming; the game is in Mojang's names already.
        }

        Map<Layer, List<SecureJar>> scanned = new EnumMap<>(Layer.class);
        for (ITransformationService service : services.values()) {
            collect(scanned, service.beginScanning(environment));
        }
        define(Layer.PLUGIN, scanned.getOrDefault(Layer.PLUGIN, List.of()), false);
        for (ITransformationService service : services.values()) {
            collect(scanned, service.completeScan(this));
        }
        List<SecureJar> gameJars = scanned.getOrDefault(Layer.GAME, List.of());
        if (request.sharedMixin()) {
            // ForgeLaunch comes from Plexus Mod Loader's class path, as the shared Mixin and MixinExtras do.
            gameJars = SharedMixinExtras.withoutMixinExtras(
                    gameJars,
                    ForgeLaunch.class.getClassLoader().getResource(SharedMixinExtras.MIXIN_EXTRAS) != null,
                    request.log());
            if (fml4()) {
                gameJars = SharedMixinExtras.withoutMixinSyntheticPackage(gameJars, request.log());
            }
        }
        define(Layer.GAME, gameJars, true);
        request.log().accept("FML's game layer: " + gameJars.size() + " module(s)");
        if (request.sharedMixin() && fml4()) {
            // NeoForge's FML 4 and FML 2 (ADR-0043 point 10): FML's own Mixin is not in the BOOT layer, and FML gave
            // its
            // mods' configurations to NeoForgeMixinConfigs (Fml4SharedMixinPatch) during its discovery.
            sharedMixinConfigs = NeoForgeMixinConfigs.configurations(gameJars);
            request.log()
                    .accept("FML found " + sharedMixinConfigs.size() + " Mixin configuration(s) of NeoForge's mods and"
                            + " libraries for the Mixin Fabric mods share; FML's own Mixin does not start (ADR-0043)");
        }

        for (ITransformationService service : services.values()) {
            transformer.addTransformers(service);
        }
        for (ILaunchPluginService plugin : plugins().values()) {
            plugin.addResources(gameJars);
        }
        handler = handlers.get(request.launchTarget());
        if (handler == null) {
            throw new IllegalStateException(
                    "Forge has no launch handler " + request.launchTarget() + "; it has " + handlers.keySet());
        }
        initializeLaunch();
    }

    private boolean launchInitialized;

    /**
     * {@code initializeLaunch} for every plugin, once. ModLauncher does it right before the launch handler, when nothing of
     * the game has been loaded. Plexus Mod Loader starts its game layer between FML's setup and the launch, and that loads
     * Minecraft classes; Mixin records every class a plugin is offered as loaded and refuses a configuration that targets
     * one ("was loaded too early"), so it is done at the end of the setup, before the game layer starts (ModernFix on
     * Forge 1.20.1 and NeoForge 21.1 failed that way).
     */
    @SuppressWarnings("removal")
    private void initializeLaunch() throws Exception {
        if (launchInitialized) {
            return;
        }
        launchInitialized = true;
        NamedPath[] paths = handler.getPaths();
        for (Map.Entry<String, ILaunchPluginService> plugin : plugins().entrySet()) {
            // As ModLauncher: the class with the other plugins and transformers applied, under the plugin's name.
            plugin.getValue()
                    .initializeLaunch(
                            className -> transformer.transformFor(
                                    plugin.getKey(),
                                    className,
                                    request.classes()
                                            .classBytes(className.replace('.', '/'))
                                            .orElseThrow(() -> new ClassNotFoundException(className))),
                            paths);
        }
    }

    /**
     * Whether this is NeoForge's FML 4 or FML 2 (types in {@code net.neoforged.fml}) rather than MinecraftForge's FML.
     */
    private static boolean fml4() {
        try {
            Class.forName("net.neoforged.fml.loading.FMLLoader", false, PlexusModLauncher.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public ModuleLayer gameLayer() {
        return layers.get(Layer.GAME);
    }

    @Override
    public List<SharedMixinConfig> sharedMixinConfigs() {
        return sharedMixinConfigs;
    }

    @Override
    public BridgeClassTransformer classTransformer() {
        return transformer;
    }

    @Override
    @SuppressWarnings("removal")
    public void launch() throws Throwable {
        initializeLaunch();
        String[] arguments = request.gameArguments().toArray(String[]::new);
        request.log().accept("Launching target '" + request.launchTarget() + "'");
        ServiceRunner runner = handler.launchService(arguments, gameLayer());
        runner.run();
    }

    @Override
    public Optional<ModuleLayer> getLayer(Layer layer) {
        return Optional.ofNullable(layers.get(layer));
    }

    /** Defines one layer in the game class loader, with ModLauncher's parent layers for it. */
    @SuppressWarnings("removal") // getParent(): the one both ModLauncher 10.0 (Forge 47) and 10.2 have
    private ModuleLayer define(Layer layer, List<SecureJar> jars, boolean transformed) {
        List<ModuleLayer> parents = new ArrayList<>();
        List<Configuration> parentConfigurations = new ArrayList<>();
        // getParent() exists in every ModLauncher 10 (getParents() only from 10.1).
        for (Layer parent : layer.getParent()) {
            ModuleLayer parentLayer = layers.get(parent);
            if (parentLayer == null) {
                throw new IllegalStateException("Layer " + layer + " needs " + parent + " first");
            }
            parents.add(parentLayer);
            parentConfigurations.add(parentLayer.configuration());
        }
        Configuration configuration = Configuration.resolveAndBind(
                new PatchingFinder(moduleFinder(jars), request.layerModules()),
                parentConfigurations,
                ModuleFinder.of(),
                jars.stream().map(SecureJar::name).toList());
        ModuleLayer.Controller controller = request.layers().define(configuration, parents, transformed);
        ModuleLayer defined = controller.layer();
        if (layer == Layer.GAME && fml4()) {
            // NeoForge's FML 4: a mod's module-info does not require the libraries Plexus Mod Loader's class path
            // provides
            // (the host drops those requires), so its module reads the class path instead (ASM, SLF4J, ...).
            Module appClassPath = ForgeLaunch.class.getClassLoader().getUnnamedModule();
            for (Module module : defined.modules()) {
                controller.addReads(module, appClassPath);
                controller.addReads(module, module.getClassLoader().getUnnamedModule());
            }
        }
        layers.put(layer, defined);
        return defined;
    }

    /** The services after which FML 47 (Minecraft 1.20.1) takes a jar of the mods folder as a transformer jar. */
    private static final List<String> FML47_SERVICES = List.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "net.minecraftforge.forgespi.locating.IModLocator",
            "net.minecraftforge.forgespi.locating.IDependencyLocator",
            "net.minecraftforge.fml.loading.ImmediateWindowProvider");

    /**
     * Whether FML's scan of the mods folder would take {@code jar} as a ModLauncher service jar: FML's own check, which
     * Forge 47 does not have as a method (its list of services is the same four names).
     */
    private static boolean isServiceProvider(Path jar) {
        try {
            return ModDirTransformerDiscoverer.isServiceProvider(jar);
        } catch (NoClassDefFoundError e) {
            // NeoForge's FML 4 (21.1, 20.4; ADR-0043): its package is net.neoforged, and the check is this one.
            return neoForgeShouldLoadInServiceLayer(jar);
        } catch (NoSuchMethodError e) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                return FML47_SERVICES.stream().anyMatch(name -> zip.getEntry("META-INF/services/" + name) != null);
            } catch (java.io.IOException io) {
                return false;
            }
        }
    }

    /**
     * {@code net.neoforged.fml.loading.TransformerDiscovererConstants.shouldLoadInServiceLayer(Path)} (FML 4.0.45); FML 2
     * (NeoForge 20.4) has only its list of service names ({@code SERVICES}), as Forge 47 does.
     */
    private static boolean neoForgeShouldLoadInServiceLayer(Path jar) {
        try {
            Class<?> constants = Class.forName(
                    "net.neoforged.fml.loading.TransformerDiscovererConstants",
                    false,
                    PlexusModLauncher.class.getClassLoader());
            try {
                return (boolean) constants
                        .getMethod("shouldLoadInServiceLayer", Path.class)
                        .invoke(null, jar);
            } catch (NoSuchMethodException fml2) {
                @SuppressWarnings("unchecked")
                java.util.Set<String> services =
                        (java.util.Set<String>) constants.getField("SERVICES").get(null);
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                    return services.stream().anyMatch(name -> zip.getEntry("META-INF/services/" + name) != null);
                } catch (java.io.IOException io) {
                    return false;
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NeoForge's FML has no check for service layer jars: " + e, e);
        }
    }

    /**
     * The module finder over {@code jars}: Forge's {@code securemodules} ({@code SecureModuleFinder}, Forge 49 and later) or
     * cpw's {@code securejarhandler} ({@code JarModuleFinder}, Forge 47 for Minecraft 1.20.1). Both have {@code
     * of(SecureJar...)}; a release has one of them.
     */
    private static ModuleFinder moduleFinder(List<SecureJar> jars) {
        ClassLoader loader = PlexusModLauncher.class.getClassLoader();
        for (String name :
                List.of("net.minecraftforge.securemodules.SecureModuleFinder", "cpw.mods.cl.JarModuleFinder")) {
            try {
                Class<?> finder = Class.forName(name, false, loader);
                return (ModuleFinder)
                        finder.getMethod("of", SecureJar[].class).invoke(null, (Object) jars.toArray(SecureJar[]::new));
            } catch (ClassNotFoundException e) {
                // the other release family
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(name + " cannot make a module finder: " + e, e);
            }
        }
        throw new IllegalStateException("Forge's libraries have neither securemodules nor securejarhandler");
    }

    /** ModLauncher's argument handling: each service's options are {@code <service>.<option>}; others pass through. */
    private void offerArguments(String[] arguments) {
        OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();
        OptionSpec<String> version = parser.accepts("version").withRequiredArg();
        OptionSpec<String> assetsDirectory = parser.accepts("assetsDir").withRequiredArg();
        OptionSpec<String> uuid = parser.accepts("uuid").withRequiredArg();
        for (ITransformationService service : services.values()) {
            service.arguments((name, description) -> parser.accepts(service.name() + "." + name, description));
        }
        OptionSet options = parser.parse(arguments);
        environment.computePropertyIfAbsent(
                IEnvironment.Keys.VERSION.get(),
                k -> options.has(version) ? options.valueOf(version) : request.minecraftVersion());
        environment.computePropertyIfAbsent(IEnvironment.Keys.GAMEDIR.get(), k -> request.gameDirectory());
        if (options.has(assetsDirectory)) {
            environment.computePropertyIfAbsent(
                    IEnvironment.Keys.ASSETSDIR.get(), k -> Path.of(options.valueOf(assetsDirectory)));
        }
        environment.computePropertyIfAbsent(IEnvironment.Keys.LAUNCHTARGET.get(), k -> request.launchTarget());
        if (options.has(uuid)) {
            environment.computePropertyIfAbsent(IEnvironment.Keys.UUID.get(), k -> options.valueOf(uuid));
        }
        for (ITransformationService service : services.values()) {
            service.argumentValues(new ITransformationService.OptionResult() {
                @Override
                public <V> V value(OptionSpec<V> option) {
                    return options.valueOf(option);
                }

                @Override
                public <V> List<V> values(OptionSpec<V> option) {
                    return options.valuesOf(option);
                }
            });
        }
    }

    private Optional<BiFunction<INameMappingService.Domain, String, String>> nameMapping(String targetMapping) {
        return Optional.ofNullable(activeNaming.get(targetMapping)).map(INameMappingService::namingFunction);
    }

    private static void collect(Map<Layer, List<SecureJar>> scanned, List<ITransformationService.Resource> resources) {
        for (ITransformationService.Resource resource : resources) {
            scanned.computeIfAbsent(resource.target(), k -> new ArrayList<>()).addAll(resource.resources());
        }
    }

    private <T> List<T> load(ModuleLayer layer, Class<T> service) {
        List<T> found = new ArrayList<>();
        for (var iterator = ServiceLoader.load(layer, service).iterator(); iterator.hasNext(); ) {
            try {
                found.add(iterator.next());
            } catch (ServiceConfigurationError e) {
                request.log().accept("A " + service.getSimpleName() + " could not be loaded: " + e.getMessage());
            }
        }
        return found;
    }

    /**
     * A layer's modules as {@code finder} finds them, each passed once through Plexus Mod Loader's {@code layerModules}
     * (checked patches of FML's own classes, ADR-0025); the same reference for a name every time.
     */
    private static final class PatchingFinder implements ModuleFinder {

        private final ModuleFinder finder;
        private final UnaryOperator<ModuleReference> patch;
        private final Map<String, ModuleReference> patched = new HashMap<>();

        PatchingFinder(ModuleFinder finder, UnaryOperator<ModuleReference> patch) {
            this.finder = finder;
            this.patch = patch;
        }

        @Override
        public Optional<ModuleReference> find(String name) {
            return finder.find(name).map(this::patched);
        }

        @Override
        public Set<ModuleReference> findAll() {
            Set<ModuleReference> all = new LinkedHashSet<>();
            finder.findAll().forEach(reference -> all.add(patched(reference)));
            return all;
        }

        private synchronized ModuleReference patched(ModuleReference reference) {
            return patched.computeIfAbsent(reference.descriptor().name(), name -> patch.apply(reference));
        }
    }

    private static String fileOf(Object service) {
        var source = service.getClass().getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return service.getClass().getModule().getName();
        }
        String location = source.getLocation().getPath();
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
