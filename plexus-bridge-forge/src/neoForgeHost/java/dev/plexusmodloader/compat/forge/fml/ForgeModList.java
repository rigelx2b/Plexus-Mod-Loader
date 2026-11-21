package dev.plexusmodloader.compat.forge.fml;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Forge on NeoForge (ADR-0051): Forge's {@code ModList} and {@code ModLoadingContext} for the Forge mods the host runs.
 * Forge's launch, which fills its loading mod list and its {@code ModList}, does not run on NeoForge. Before FML starts,
 * the host gives Forge's loading mod list an empty state ({@code LoadingModListImpl.temp}), so that {@code ModList} can
 * be initialised (a mixin may touch it before any mod is constructed), and an empty list of loaded mods; when FML
 * constructs the first Forge mod, all Forge mods' containers exist (FML builds every container first), and {@code
 * ModList} gets Forge's own {@code FMLModContainer} of each ({@code setLoadedMods}). Forge's {@code ModList} so lists
 * the Forge mods the host runs: not NeoForge's mods, not other loaders' mods, and not the {@code minecraft} and {@code
 * forge} entries Forge's own launch adds. While a Forge mod is constructed and while it gets a lifecycle event, Forge's
 * {@code ModLoadingContext} of that thread has its container as the active one, as under Forge.
 *
 * <p>Only {@code ModList} members that read the loaded mods run ({@code ForgeOnNeoForgeScope}); they were checked before
 * launch ({@code ForgeModListCheck}).
 *
 * <p>Thread: {@link #prepare} on the launching thread before FML starts; the rest on FML's worker threads.
 */
final class ForgeModList {

    private static final List<ModContainer> CONTAINERS = new ArrayList<>();
    private static boolean published;
    private static MethodHandle setActiveContainer;

    private ForgeModList() {}

    /**
     * Gives Forge's loading mod list an empty state and Forge's {@code ModList} no loaded mods yet.
     *
     * @throws ReflectiveOperationException when Forge's classes are not the ones checked before launch
     * @throws LinkageError when Forge's {@code ModList} does not link or initialise
     */
    static void prepare() throws Throwable {
        ClassLoader loader = ModList.class.getClassLoader();
        Class<?> state = Class.forName("net.minecraftforge.fml.loading.ModSorter$State", false, loader);
        Class<?> impl = Class.forName("net.minecraftforge.fml.loading.LoadingModListImpl", false, loader);
        Object empty = MethodHandles.privateLookupIn(state, MethodHandles.lookup())
                .findConstructor(state, MethodType.methodType(void.class, List.class, List.class))
                .invoke(List.of(), List.of());
        MethodHandles.privateLookupIn(impl, MethodHandles.lookup())
                .findStaticSetter(impl, "temp", state)
                .invoke(empty);
        setLoadedMods(List.of());
    }

    /** Adds {@code container}, Forge's container of a Forge mod FML has built. */
    static synchronized void add(ModContainer container) {
        if (published) {
            throw new IllegalStateException(
                    "Forge mod container built after Forge's ModList was populated: " + container.getModId());
        }
        CONTAINERS.add(container);
    }

    /** Sets Forge's {@code ModList} to the containers added, once; FML has built all of them by its first construct. */
    static synchronized void publish() throws Throwable {
        if (!published) {
            setLoadedMods(List.copyOf(CONTAINERS));
            published = true;
        }
    }

    /** Runs {@code action} with {@code container} as the active container of this thread's Forge {@code ModLoadingContext}. */
    // Forge marks ModLoadingContext.get() for removal, but Forge's loader and Forge mods still use it.
    @SuppressWarnings("removal")
    static void runAs(ModContainer container, Action action) throws Throwable {
        MethodHandle setActive = setActiveContainer();
        ModLoadingContext context = ModLoadingContext.get();
        setActive.invoke(context, container);
        try {
            action.run();
        } finally {
            setActive.invoke(context, (ModContainer) null);
        }
    }

    /** What {@link #runAs} runs. */
    @FunctionalInterface
    interface Action {
        void run() throws Throwable;
    }

    private static void setLoadedMods(List<ModContainer> containers) throws Throwable {
        MethodHandles.privateLookupIn(ModList.class, MethodHandles.lookup())
                .findStatic(ModList.class, "setLoadedMods", MethodType.methodType(void.class, List.class))
                .invoke(containers);
    }

    private static synchronized MethodHandle setActiveContainer() throws ReflectiveOperationException {
        if (setActiveContainer == null) {
            setActiveContainer = MethodHandles.privateLookupIn(ModLoadingContext.class, MethodHandles.lookup())
                    .findVirtual(
                            ModLoadingContext.class,
                            "setActiveContainer",
                            MethodType.methodType(void.class, ModContainer.class));
        }
        return setActiveContainer;
    }
}
