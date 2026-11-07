package dev.plexusmodloader.compat.forge.modlauncher;

import java.nio.file.Path;
import java.util.List;
import net.minecraftforge.fml.loading.targets.CommonServerLaunchHandler;

/**
 * What Plexus Mod Loader's two launch handlers for Forge 47 (Minecraft 1.20.1) share. Forge 47's own handlers have no launch
 * types and look for Minecraft in a Maven layout of FML's library directory that Plexus Mod Loader does not have; these take
 * the patched game jar that Plexus Mod Loader prepared as the Minecraft mod. Compiled against FML 47 (the {@code forgeHost47}
 * source set) and registered instead of {@code GameTestLaunchHandler} when ModLauncher is 10.0.x.
 *
 * <p>The game jar's path comes from the system property {@code plexus.forge.patchedGame}, which {@code ForgeGameHost} sets before the
 * launch; Forge's own handlers look for the Minecraft jars in a Maven layout of FML's library directory that Plexus Mod
 * Loader does not have.
 */
abstract class PlexusLaunchHandler47 extends CommonServerLaunchHandler {

    /** System property with the jars FML runs as mods and language providers, separated as a class path is. */
    static final String MOD_FILES = "plexus.forge.modFiles";

    /** System property with the path of the patched game jar. */
    static final String GAME_JAR = "plexus.forge.patchedGame";

    /** As {@code GameTestLaunchHandler.GAME_LAYER_MAIN}: the game layer's own game test entry point, when needed. */
    static final String GAME_LAYER_MAIN = "plexus.forge.gameLayerGameTestMain";

    @Override
    public String getNaming() {
        return "mcp";
    }

    @Override
    public LocatedPaths getMinecraftPaths() {
        return Paths47.locatedPaths();
    }

    @Override
    protected java.util.function.BiPredicate<String, String> processMCStream(
            net.minecraftforge.fml.loading.VersionInfo versionInfo,
            java.util.stream.Stream.Builder<Path> mc,
            java.util.function.BiPredicate<String, String> filter,
            java.util.stream.Stream.Builder<List<Path>> mods) {
        // Not used: getMinecraftPaths() does not look for Minecraft in FML's library directory.
        return filter;
    }

    @Override
    protected void runTarget(String mainClass, String[] allArgs, ModuleLayer layer) throws Throwable {
        String[] args = withoutFmlOptions(allArgs);
        String own = System.getProperty(GAME_LAYER_MAIN);
        Module minecraft = layer.findModule("minecraft").orElse(null);
        if (own != null && minecraft != null && Class.forName(minecraft, "net.minecraft.gametest.Main") == null) {
            System.setProperty("forge.enableGameTest", "true");
            ClassLoader loader = minecraft.getClassLoader();
            Class<?> main = Class.forName(own, true, loader);
            try {
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
        super.runTarget(mainClass, args, layer);
    }

    /**
     * The arguments without FML's own options ({@code --fml.forgeVersion 47.4.28} ...), which the launcher gives FML and
     * Minecraft's game test entry point does not know.
     */
    static String[] withoutFmlOptions(String[] args) {
        return Paths47.withoutFmlOptions(args);
    }

    private static void call(ClassLoader loader, String type, String method) throws ReflectiveOperationException {
        Class.forName(type, true, loader).getMethod(method).invoke(null);
    }
}
