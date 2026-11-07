package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.List;
import net.minecraftforge.fml.loading.targets.CommonLaunchHandler;

/**
 * Mojang's game test server for an installed (production) Forge, as Plexus Mod Loader's launch target {@code
 * game_test_server}. Forge runs game tests only outside production: {@code ForgeGameTestHooks.isGametestEnabled()}
 * is false when {@code FMLLoader.isProduction()}, and Forge's patched server then never ticks the tests. Forge's own
 * {@code server_gametest} handlers are development ones that look for Minecraft on a development class path. This
 * handler is that launch type ({@code net.minecraft.gametest.Main}, dedicated server, not production) with the
 * production server's way of finding Minecraft: the patched jar that holds {@code MinecraftServer}.
 */
public final class GameTestLaunchHandler extends CommonLaunchHandler {

    public GameTestLaunchHandler() {
        super(SERVER_GAMETEST, "plexusmodloader_");
    }

    /**
     * System property holding the class name of the game layer's own game test entry point, set by {@link
     * PlexusModLauncher} for the game test target. Used only when the game has no {@code net.minecraft.gametest.Main}
     * (Minecraft 1.21.1) and Forge's launch type would run its own {@code GameTestMain}, which calls the dedicated
     * server's {@code Main} and does not understand the game test arguments Plexus Mod Loader passes.
     */
    static final String GAME_LAYER_MAIN = "plexus.forge.gameLayerGameTestMain";

    @Override
    protected void runTarget(String module, String mainClass, String[] args, ModuleLayer layer) throws Throwable {
        String own = System.getProperty(GAME_LAYER_MAIN);
        Module minecraft = layer.findModule("minecraft").orElse(null);
        if (own != null && minecraft != null && Class.forName(minecraft, "net.minecraft.gametest.Main") == null) {
            // Forge 52's wrapper also turns on Forge's game tests before it starts the server.
            System.setProperty("forge.enableGameTest", "true");
            ClassLoader loader = minecraft.getClassLoader();
            Class<?> main = Class.forName(own, true, loader);
            try {
                // What Forge 52's patched server Main does before it makes the server: bootstrap, then FML's mod
                // loading
                // (ServerModLoader.load). The game layer's own game test entry point does not load Forge's mods.
                call(loader, "net.minecraft.SharedConstants", "tryDetectVersion");
                call(loader, "net.minecraft.server.Bootstrap", "bootStrap");
                call(loader, "net.minecraft.server.Bootstrap", "validate");
                call(loader, "net.minecraftforge.server.loading.ServerModLoader", "load");
                main.getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
            return;
        }
        super.runTarget(module, mainClass, args, layer);
    }

    private static void call(ClassLoader loader, String type, String method) throws ReflectiveOperationException {
        Class<?> owner = Class.forName(type, true, loader);
        owner.getMethod(method).invoke(null);
    }

    @Override
    public String getNaming() {
        return "mcp";
    }

    @Override
    public List<Path> getMinecraftPaths() {
        return List.of(getPathFromResource("net/minecraft/server/MinecraftServer.class"));
    }
}
