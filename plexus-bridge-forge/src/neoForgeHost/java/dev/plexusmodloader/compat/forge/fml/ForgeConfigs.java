package dev.plexusmodloader.compat.forge.fml;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/**
 * Forge on NeoForge, F-N6 (ADR-0051): Forge mods' configs, kept by Forge's own {@code ConfigTracker} (Forge's {@code
 * fmlcore} jar among the loader libraries), loaded when Forge's loading would load them. Forge mods register their
 * configs with Forge's {@code ModConfig} while they are constructed; Forge's loading then loads the {@code CLIENT} (on a
 * client) and {@code COMMON} configs from the config folder in its {@code CONFIG_LOAD} stage, after every mod is
 * constructed and before common setup, and the {@code SERVER} configs from the world's {@code serverconfig} folder when a
 * server is about to start ({@code ServerLifecycleHooks.handleServerAboutToStart}), unloading them when it has stopped.
 * The host does the same: {@link #loadStartup} before the first Forge mod gets common setup, and {@link #installServer}
 * on NeoForge's server events. Forge's tracker reads and writes the files and posts {@code ModConfigEvent.Loading} to
 * each mod's bus group through Forge's own mod container.
 *
 * <p>What is not done: Forge's dedicated server and client send each other the server configs (Forge's network), and a
 * client loads the server configs' defaults when it joins a server ({@code ConfigTracker.loadDefaultServerConfigs}); a
 * Forge mod on a client connected to another server reads its server config's defaults. Forge's {@code forceUnload} at
 * the game's exit is not called; night-config's file watcher threads are daemon threads, so they do not keep the game
 * running.
 *
 * <p>Thread: {@link #loadStartup} on an FML worker thread (FML sends common setup to the mods in parallel; the first
 * Forge mod loads every Forge mod's config while the others wait); the server listeners on the server thread.
 */
final class ForgeConfigs {

    static final String ABOUT_TO_START = "net.neoforged.neoforge.event.server.ServerAboutToStartEvent";
    static final String STOPPED = "net.neoforged.neoforge.event.server.ServerStoppedEvent";
    static final String SERVER_CONFIG_FOLDER = "serverconfig";

    private static boolean startupLoaded;
    private static boolean serverInstalled;

    private ForgeConfigs() {}

    /**
     * Loads the {@code CLIENT} configs (when {@code client}) and then the {@code COMMON} configs of all Forge mods from
     * Forge's config folder ({@code FMLPaths.CONFIGDIR}, which the host sets to the game's), once.
     */
    static synchronized void loadStartup(boolean client, Consumer<String> log) {
        if (startupLoaded) {
            return;
        }
        startupLoaded = true;
        Path folder = FMLPaths.CONFIGDIR.get();
        if (client) {
            ConfigTracker.loadConfigs(ModConfig.Type.CLIENT, folder);
        }
        ConfigTracker.loadConfigs(ModConfig.Type.COMMON, folder);
        log.accept("Forge on NeoForge: Forge's config tracker loaded " + count(ModConfig.Type.COMMON) + " COMMON"
                + (client ? " and " + count(ModConfig.Type.CLIENT) + " CLIENT" : "")
                + " config(s) of Forge mods (experimental, F-N6)");
    }

    /**
     * Adds listeners to NeoForge's {@code NeoForge.EVENT_BUS} that load the {@code SERVER} configs of all Forge mods from
     * the world's {@code serverconfig} folder when a server is about to start and unload them when it has stopped, once.
     *
     * @throws IllegalStateException when NeoForge's server events or the members used are not in this release
     */
    @SuppressWarnings("unchecked")
    static synchronized void installServer(ClassLoader game, Consumer<String> log) {
        if (serverInstalled) {
            return;
        }
        IEventBus neoForgeBus;
        Class<? extends Event> aboutToStart;
        Class<? extends Event> stopped;
        MethodHandle folder;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> holder = Class.forName("net.neoforged.neoforge.common.NeoForge", true, game);
            neoForgeBus = (IEventBus) lookup.findStaticGetter(holder, "EVENT_BUS", IEventBus.class)
                    .invoke();
            aboutToStart = Class.forName(ABOUT_TO_START, false, game).asSubclass(Event.class);
            stopped = Class.forName(STOPPED, false, game).asSubclass(Event.class);
            folder = serverConfigFolder(aboutToStart, lookup);
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "Cannot load Forge mods' server configs on NeoForge's server events: " + e, e);
        }
        Path[] loaded = new Path[1];
        // Forge loads the server configs before it posts its ServerAboutToStartEvent, and unloads them after its
        // ServerStoppedEvent; both Forge events are passed on from NeoForge's at normal priority (ForgeGameEvents).
        neoForgeBus.addListener(EventPriority.HIGH, false, (Class<Event>) aboutToStart, event -> {
            Path path = invoke(folder, event);
            loaded[0] = path;
            ConfigTracker.loadConfigs(ModConfig.Type.SERVER, path);
            log.accept("Forge on NeoForge: Forge's config tracker loaded " + count(ModConfig.Type.SERVER)
                    + " SERVER config(s) of Forge mods from the world's " + SERVER_CONFIG_FOLDER
                    + " folder (experimental, F-N6)");
        });
        neoForgeBus.addListener(EventPriority.LOW, false, (Class<Event>) stopped, event -> {
            if (loaded[0] != null) {
                ConfigTracker.unloadConfigs(ModConfig.Type.SERVER, loaded[0]);
                loaded[0] = null;
            }
        });
        serverInstalled = true;
    }

    /**
     * A handle from NeoForge's {@code ServerAboutToStartEvent} to Forge's server config folder, made: the folder {@code
     * serverconfig} in the world's folder ({@code MinecraftServer.getWorldPath(LevelResource.ROOT)}), where Forge's
     * {@code ServerLifecycleHooks.getServerConfigPath} has it ({@code getWorldPath(new LevelResource("serverconfig"))}).
     */
    private static MethodHandle serverConfigFolder(Class<?> aboutToStart, MethodHandles.Lookup lookup)
            throws ReflectiveOperationException {
        Method getServer = aboutToStart.getMethod("getServer");
        Method getWorldPath = null;
        for (Method method : getServer.getReturnType().getMethods()) {
            if (method.getName().equals("getWorldPath")
                    && method.getParameterCount() == 1
                    && method.getReturnType() == Path.class) {
                getWorldPath = method;
            }
        }
        if (getWorldPath == null) {
            throw new NoSuchMethodException(getServer.getReturnType().getName() + ".getWorldPath(LevelResource)");
        }
        Object root = getWorldPath.getParameterTypes()[0].getField("ROOT").get(null);
        MethodHandle worldPath = MethodHandles.insertArguments(lookup.unreflect(getWorldPath), 1, root);
        MethodHandle folder = MethodHandles.lookup()
                .findStatic(ForgeConfigs.class, "serverConfigFolder", MethodType.methodType(Path.class, Path.class));
        return MethodHandles.filterReturnValue(
                        MethodHandles.filterReturnValue(lookup.unreflect(getServer), worldPath), folder)
                .asType(MethodType.methodType(Path.class, Object.class));
    }

    private static Path serverConfigFolder(Path world) {
        Path path = world.resolve(SERVER_CONFIG_FOLDER).normalize();
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create Forge mods' server config folder " + path + ": " + e, e);
        }
        return path;
    }

    private static Path invoke(MethodHandle folder, Event event) {
        try {
            return (Path) folder.invokeExact((Object) event);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    private static int count(ModConfig.Type type) {
        Set<ModConfig> configs = ConfigTracker.configSets().get(type);
        return configs == null ? 0 : configs.size();
    }
}
