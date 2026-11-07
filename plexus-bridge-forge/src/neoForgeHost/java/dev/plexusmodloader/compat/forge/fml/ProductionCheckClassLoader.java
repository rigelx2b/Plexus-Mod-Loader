package dev.plexusmodloader.compat.forge.fml;

import java.net.URL;

/**
 * The class loader FML 10 gets as its parent, so that FML decides it runs in production, as it does in an installed
 * NeoForge (ADR-0038).
 *
 * <p>FML 10 ({@code loader} 10.0.36, NeoForge 21.11) takes the game for a development setup when its parent class
 * loader can find {@code net/minecraft/DetectedVersion.class} ({@code FMLLoader.detectProduction}). Plexus Mod Loader's
 * game class loader can (it shows the game jar's resources), but the game is not on the class path of an installed
 * NeoForge: FML finds it in the library folder and defines it in its own game layer. In development mode NeoForge ticks
 * game tests itself, cleans the distribution and looks for mods in development folders, which is not what a player's
 * game does. FML 11 and later decide from the bytes of {@code SharedConstants} and need none of this.
 *
 * <p>Everything but that one resource goes to the parent unchanged. Thread: safe; class loading takes a lock per class
 * name, as the parent's does.
 */
final class ProductionCheckClassLoader extends ClassLoader {

    /** The resource FML 10's {@code detectProduction} asks for. */
    static final String DETECTED_VERSION = "net/minecraft/DetectedVersion.class";

    static {
        registerAsParallelCapable();
    }

    ProductionCheckClassLoader(ClassLoader parent) {
        super("plexus-fml-parent", parent);
    }

    /** The parent's resource, except the one FML tests for the game with. */
    @Override
    public URL getResource(String name) {
        return DETECTED_VERSION.equals(name) ? null : super.getResource(name);
    }

    /**
     * Whether an FML with {@code implementationVersion} (its {@code loader} jar's) asks the parent class loader for the
     * game to tell production from development: FML 10 and older.
     *
     * @param implementationVersion for example {@code 10.0} or {@code 11.0}; null or unparsable means a newer FML
     */
    static boolean needed(String implementationVersion) {
        if (implementationVersion == null) {
            return false;
        }
        int dot = implementationVersion.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? implementationVersion : implementationVersion.substring(0, dot)) < 11;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
