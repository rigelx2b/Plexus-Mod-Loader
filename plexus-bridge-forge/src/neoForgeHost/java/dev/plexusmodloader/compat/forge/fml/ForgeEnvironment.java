package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Forge on NeoForge (ADR-0051): the side and game directory Forge mods read ({@code FMLEnvironment.dist}, {@code
 * FMLLoader.getDist()}, {@code FMLLoader.getGamePath()}). MinecraftForge's launch sets them in private static fields of
 * Forge's {@code FMLLoader}, in Forge's {@code fmlloader} jar among the loader libraries; that launch does not run here,
 * so the host sets the same two fields from NeoForge's side and game directory before FML starts, and before anything
 * reads Forge's {@code FMLEnvironment}, which copies the side once. It also sets {@code production} to {@code true}, as a
 * player's Forge launch does (F-N6; {@code FMLEnvironment.production}, which Forge's config values read, copies it once).
 * The fields were checked before launch ({@code
 * ForgeEnvironmentCheck}). It then prepares Forge's {@code ModList} ({@link ForgeModList}, checked by {@code
 * ForgeModListCheck}); every other part of Forge's launch state stays unset.
 *
 * <p>Each field is set through a static setter handle, which resolves that field only: reflection's {@code
 * getDeclaredField} would load the types of all of {@code FMLLoader}'s fields, among them Forge's access transformer
 * service, which is not among the loader libraries.
 *
 * <p>Thread: the launching thread, before FML starts; set once.
 */
final class ForgeEnvironment {

    private static boolean set;

    private ForgeEnvironment() {}

    /**
     * Sets Forge's side to {@code dist} (NeoForge's side by name, {@code CLIENT} or {@code DEDICATED_SERVER}) and Forge's
     * game directory to {@code gameDirectory}, once.
     *
     * <p>A failure stops the whole launch ({@code PLEXUS-COMPAT-230}, ADR-0051): the runtime has already reported these
     * Forge mods as running, so they can no longer be left out ({@code PLEXUS-COMPAT-229}) here. {@link
     * VirtualMachineError}s such as {@link OutOfMemoryError} pass through unchanged.
     *
     * @throws IllegalStateException when Forge's {@code FMLLoader} cannot be linked or does not have the fields checked
     *     before launch
     */
    static synchronized void set(String dist, Path gameDirectory) {
        if (set) {
            return;
        }
        try {
            // The class literals resolve here: a Forge release whose FMLLoader or Dist does not link fails at them.
            set(FMLLoader.class, Dist.class, dist, gameDirectory);
        } catch (LinkageError e) {
            throw doesNotLoad(e);
        }
        prepareModList();
        set = true;
    }

    /**
     * Prepares Forge's {@code ModList} ({@link ForgeModList#prepare}), before anything (a Forge mod's mixin included)
     * initialises it: a failed initialisation would be permanent.
     *
     * @throws IllegalStateException with {@code PLEXUS-COMPAT-230} when Forge's {@code ModList} or loading mod list does
     *     not link or initialise, or is not what was checked before launch ({@code ForgeModListCheck})
     */
    private static void prepareModList() {
        try {
            ForgeModList.prepare();
        } catch (LinkageError e) {
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-230 Forge's ModList or loading mod list classes failed to load, link, or initialize"
                            + " beside NeoForge's FML; stopping launch: " + e,
                    e);
        } catch (RuntimeException | Error e) {
            // VirtualMachineError (OutOfMemoryError, StackOverflowError) and anything not about the classes' shape.
            throw e;
        } catch (Throwable e) {
            // ReflectiveOperationException: a class, field, constructor or method the pre-launch check saw is not
            // there.
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-230 Forge's LoadingModListImpl.temp, ModSorter$State, or ModList.setLoadedMods does"
                            + " not match what the pre-launch check saw; stopping launch: " + e,
                    e);
        }
    }

    /**
     * Sets {@code loader}'s private static fields {@code dist} (to {@code distType}'s constant named {@code dist}) and
     * {@code gamePath} (to {@code gameDirectory}, absolute and normalized), as {@link #set(String, Path)} does for
     * Forge's {@code FMLLoader}.
     *
     * @throws IllegalStateException with {@code PLEXUS-COMPAT-230} when {@code loader} does not link or initialise, lacks
     *     either field of those types, or {@code distType} has no such constant
     */
    static <E extends Enum<E>> void set(Class<?> loader, Class<E> distType, String dist, Path gameDirectory) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(loader, MethodHandles.lookup());
            E side = Enum.valueOf(distType, dist);
            lookup.findStaticSetter(loader, "dist", distType).invoke(side);
            lookup.findStaticSetter(loader, "gamePath", Path.class)
                    .invoke(gameDirectory.toAbsolutePath().normalize());
            // F-N6: a player's Forge runs in production (Forge's own launch sets it unless it is Forge's userdev); in
            // production a Forge config value read before its config loads gives its default instead of throwing.
            lookup.findStaticSetter(loader, "production", boolean.class).invoke(true);
        } catch (LinkageError e) {
            // ExceptionInInitializerError and NoClassDefFoundError among them: FMLLoader does not link or initialise.
            throw doesNotLoad(e);
        } catch (NoSuchFieldException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-230 Forge's FMLLoader lacks private static fields dist, gamePath and production of the expected"
                            + " types, or Forge's Dist has no constant for NeoForge's side; stopping launch: " + e,
                    e);
        } catch (RuntimeException | Error e) {
            // VirtualMachineError (OutOfMemoryError, StackOverflowError) and anything not about FMLLoader's shape.
            throw e;
        } catch (Throwable e) {
            // IllegalAccessException (the package is not open to the host), or a checked throwable MethodHandle.invoke
            // declares and a static field setter does not throw.
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-230 Unexpected failure setting Forge's FMLLoader dist, gamePath and production fields; stopping"
                            + " launch: " + e,
                    e);
        }
    }

    private static IllegalStateException doesNotLoad(LinkageError e) {
        return new IllegalStateException(
                "PLEXUS-COMPAT-230 Forge's FMLLoader failed to load, link, or initialize beside NeoForge's FML; stopping"
                        + " launch: " + e,
                e);
    }
}
