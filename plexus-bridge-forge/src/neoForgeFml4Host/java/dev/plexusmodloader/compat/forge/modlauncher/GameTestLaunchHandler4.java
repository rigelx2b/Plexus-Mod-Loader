package dev.plexusmodloader.compat.forge.modlauncher;

import java.lang.reflect.InvocationTargetException;
import net.neoforged.fml.loading.targets.NeoForgeServerLaunchHandler;

/**
 * Mojang's game test server for an installed (production) NeoForge 21.1 / 20.4, as Plexus Mod Loader's launch target {@code
 * game_test_server} (ADR-0043). NeoForge's own handlers for game tests are development ones that look for Minecraft on a
 * development class path. This one is NeoForge's production server handler ({@code forgeserver}: Minecraft and NeoForge from the
 * libraries folder) that starts Plexus Mod Loader's game test entry point of the game layer instead of the dedicated server's
 * {@code Main}, after what NeoForge's patched {@code Main} does before it makes the server: bootstrap, then FML's mod loading
 * ({@code ServerModLoader.load}). NeoForge's patched server then ticks no game tests of its own ({@code GameTestHooks} needs a
 * development run), as for Forge ({@code GameTestLaunchHandler}).
 */
public final class GameTestLaunchHandler4 extends NeoForgeServerLaunchHandler {

    /**
     * System property holding the class name of the game layer's own game test entry point, set by {@code
     * PlexusModLauncher} for the game test target.
     */
    static final String GAME_LAYER_MAIN = "plexus.forge.gameLayerGameTestMain";

    /** Called by the service loader. */
    public GameTestLaunchHandler4() {}

    @Override
    public String name() {
        return "plexusmodloader_server_gametest";
    }

    /**
     * Not FML's {@code preLaunch}: it replaces Log4j's configuration with FML's own whenever the launch is not production,
     * whose plugins (LoggerNamePatternSelector, TerminalConsole) the game class loader does not find; the configuration
     * Plexus Mod Loader's run was started with stays.
     */
    @Override
    protected String[] preLaunch(String[] arguments, ModuleLayer layer) {
        return arguments;
    }

    @Override
    protected void runService(String[] args, ModuleLayer layer) throws Throwable {
        String own = System.getProperty(GAME_LAYER_MAIN);
        Module minecraft = layer.findModule("minecraft").orElseThrow();
        if (own == null) {
            runTarget("net.minecraft.gametest.Main", args, layer);
            return;
        }
        ClassLoader loader = minecraft.getClassLoader();
        try {
            call(loader, "net.minecraft.SharedConstants", "tryDetectVersion");
            call(loader, "net.minecraft.server.Bootstrap", "bootStrap");
            call(loader, "net.minecraft.server.Bootstrap", "validate");
            call(loader, "net.neoforged.neoforge.server.loading.ServerModLoader", "load");
            Class.forName(own, true, loader).getMethod("main", String[].class).invoke(null, (Object)
                    FmlArguments.withoutFml(args));
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void call(ClassLoader loader, String type, String method) throws ReflectiveOperationException {
        Class.forName(type, true, loader).getMethod(method).invoke(null);
    }
}
