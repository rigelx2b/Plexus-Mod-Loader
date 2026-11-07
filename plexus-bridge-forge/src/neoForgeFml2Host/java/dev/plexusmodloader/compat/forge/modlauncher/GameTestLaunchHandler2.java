package dev.plexusmodloader.compat.forge.modlauncher;

import java.lang.reflect.InvocationTargetException;
import net.neoforged.fml.loading.targets.ForgeServerLaunchHandler;

/**
 * Mojang's game test server for an installed (production) NeoForge 20.4, as Plexus Mod Loader's launch target {@code
 * game_test_server} (ADR-0043). As {@code GameTestLaunchHandler4} for FML 4: NeoForge's production server handler that starts
 * Plexus Mod Loader's game test entry point of the game layer instead of the dedicated server's {@code Main}, after what
 * NeoForge's patched {@code Main} does before it makes the server: bootstrap, then FML's mod loading ({@code
 * ServerModLoader.load}).
 */
public final class GameTestLaunchHandler2 extends ForgeServerLaunchHandler {

    /** System property with the class name of the game layer's own game test entry point, set by {@code PlexusModLauncher}. */
    static final String GAME_LAYER_MAIN = "plexus.forge.gameLayerGameTestMain";

    /** Called by the service loader. */
    public GameTestLaunchHandler2() {}

    @Override
    public String name() {
        return "plexusmodloader_server_gametest";
    }

    @Override
    public LocatedPaths getMinecraftPaths() {
        return ModPaths2.withMods(super.getMinecraftPaths());
    }

    /** As {@link ServerLaunchHandler2#preLaunch}. */
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
