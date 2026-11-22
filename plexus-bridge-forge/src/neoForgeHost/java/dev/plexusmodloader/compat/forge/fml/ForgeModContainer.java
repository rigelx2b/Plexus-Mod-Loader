package dev.plexusmodloader.compat.forge.fml;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.lifecycle.ParallelDispatchEvent;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Forge on NeoForge (ADR-0051, F-N2): a MinecraftForge mod as one of FML's mods. FML sees a NeoForge mod container; it
 * holds Forge's own {@code FMLModContainer} (from Forge's {@code javafmllanguage} jar among the loader libraries), which
 * loads the mod's class from its module in FML's game layer and constructs it as Forge would, with Forge's {@code
 * FMLJavaModLoadingContext} and mod bus group. When FML's mod bus has {@code FMLConstructModEvent}, {@code
 * FMLCommonSetupEvent}, {@code FMLClientSetupEvent}, {@code FMLDedicatedServerSetupEvent} or {@code
 * FMLLoadCompleteEvent}, the Forge mod's bus gets Forge's event of the same name (F-N2, F-N3, F-N6), and the first
 * construction adds the game bus events of {@link
 * ForgeGameEvents} (F-N3). NeoForge's {@code RegisterEvent} for each registry is passed on as Forge's to the mod's bus
 * group ({@link ForgeRegistration}, F-N4). Forge mods' configs are loaded by Forge's own config tracker when Forge's
 * loading would load them ({@link ForgeConfigs}, F-N6).
 *
 * <p>Work a Forge mod queues with {@code enqueueWork} on a lifecycle event runs on the main thread after the stage, as
 * in Forge's loading, through NeoForge's queue of the same stage (F-N6). No other Forge event or loading stage is passed
 * on yet; the runtime hands over only mods that use none of that ({@code ForgeOnNeoForgeScope}).
 *
 * <p>Thread: FML constructs mods and sends setup events on its worker threads, one container at a time.
 */
final class ForgeModContainer extends ModContainer {

    /** Forge's loading stages whose queued work runs through NeoForge's: once each, for all Forge mods. */
    private static final Set<ModLoadingStage> QUEUED_STAGES = ConcurrentHashMap.newKeySet();

    private final FMLModContainer forge;
    private final IEventBus eventBus;
    private final ClassLoader gameClassLoader;

    private ForgeModContainer(
            IModInfo info,
            String modClass,
            ModuleLayer gameLayer,
            net.minecraftforge.forgespi.language.ModFileScanData scanData) {
        super(info);
        this.forge = new FMLModContainer(new ForgeModView(info), modClass, scanData, gameLayer);
        this.eventBus = BusBuilder.builder()
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build();
        this.gameClassLoader = gameLayer.modules().iterator().next().getClassLoader();
        ForgeModList.add(forge);
        // FML posts it to each container right after constructing that mod, as Forge's CONSTRUCT stage does (F-N6).
        eventBus.addListener(
                FMLConstructModEvent.class,
                event -> post(
                        event,
                        net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent::new,
                        ModLoadingStage.CONSTRUCT));
        eventBus.addListener(FMLCommonSetupEvent.class, event -> {
            // Forge's CONFIG_LOAD stage: every Forge mod is constructed, none has had common setup yet (F-N6).
            ForgeConfigs.loadStartup(PlexusFml.isClient(), PlexusFml::log);
            post(
                    event,
                    net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent::getBus,
                    net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent::new,
                    ModLoadingStage.COMMON_SETUP);
        });
        eventBus.addListener(
                FMLClientSetupEvent.class,
                event -> post(
                        event,
                        net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent::new,
                        ModLoadingStage.SIDED_SETUP));
        eventBus.addListener(
                FMLDedicatedServerSetupEvent.class,
                event -> post(
                        event,
                        net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent::new,
                        ModLoadingStage.SIDED_SETUP));
        eventBus.addListener(
                FMLLoadCompleteEvent.class,
                event -> post(
                        event,
                        net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent::new,
                        ModLoadingStage.COMPLETE));
    }

    /**
     * The container of the Forge mod {@code info} whose {@code @Mod} class is {@code modClass}, with Forge's {@code
     * scanData} of its file ({@link ForgeEventSubscribers}).
     */
    static ModContainer create(
            IModInfo info,
            String modClass,
            ModuleLayer gameLayer,
            net.minecraftforge.forgespi.language.ModFileScanData scanData) {
        try {
            return new ForgeModContainer(info, modClass, gameLayer, scanData);
        } catch (RuntimeException e) {
            // Forge's container fails as Forge's would (its module or class not found); FML reports it for the mod.
            throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmodclass")
                    .withCause(e)
                    .withAffectedMod(info));
        }
    }

    /**
     * Runs Forge's construction of the mod: its private {@code constructMod}, as Forge's loading stage would, with the
     * mod's container active in Forge's {@code ModLoadingContext} ({@link ForgeModList#runAs}). The first Forge mod
     * constructed sets Forge's paths ({@link ForgePaths}), Forge's {@code ModList} to all Forge mods' containers ({@link
     * ForgeModList#publish}), and adds the game bus events Forge mods get ({@link ForgeGameEvents}); before construction,
     * the
     * mod's bus gets NeoForge's registry events passed on as Forge's ({@link ForgeRegistration}, F-N4).
     */
    @Override
    protected void constructMod() {
        try {
            ForgePaths.setToGameDirectory();
            ForgeGameEvents.install(gameClassLoader, PlexusFml.isClient(), PlexusFml::log);
            ForgeReloadListeners.install(gameClassLoader, PlexusFml::log);
            ForgeConfigs.installServer(gameClassLoader, PlexusFml::log);
            if (PlexusFml.isClient()) {
                ForgeKeyMappings.listen(eventBus, gameClassLoader, PlexusFml::log);
            }
            ForgeRegistration.of(gameClassLoader).listen(eventBus, forge.getModBusGroup());
            // F-N6 networking: Forge channels as NeoForge payloads, Forge's work queues (ADR-0051 22).
            ForgeNetworking.of(gameClassLoader).listen(eventBus, gameClassLoader, PlexusFml.isClient(), PlexusFml::log);
            ForgeNetworking.installWorkQueues(gameClassLoader, PlexusFml.isClient());
            ForgeNetworking.installChannels(gameClassLoader);
            ForgeModList.publish();
            Method construct = FMLModContainer.class.getDeclaredMethod("constructMod");
            construct.setAccessible(true);
            ForgeModList.runAs(forge, () -> construct.invoke(forge));
        } catch (InvocationTargetException e) {
            throw failed(e.getCause());
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    private ModLoadingException failed(Throwable cause) {
        // Forge's ModLoadingException formats its message (getMessage) through Forge's FML bindings, which need Forge's
        // FMLLoader started (its jar is loaded for FMLPaths, but it never starts here): FML reports what it wraps
        // instead.
        while (cause instanceof net.minecraftforge.fml.ModLoadingException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmod")
                .withCause(cause)
                .withAffectedMod(modInfo));
    }

    /**
     * Posts Forge's lifecycle event made by {@code event} for {@code stage} to the Forge mod's bus group, as FML's {@code
     * neoForge} event of the stage came. The first Forge mod to get a stage also queues the work Forge mods queue in the
     * stage with Forge's {@code enqueueWork} on NeoForge's event (F-N6): Forge's loading runs a stage's queue ({@code
     * DeferredWorkQueue.runTasks()}, which does not empty it) once, on the main thread, after every mod has had the
     * stage's event, which is when NeoForge runs the work queued on its event.
     */
    private <E extends ParallelDispatchEvent> void post(
            net.neoforged.fml.event.lifecycle.ParallelDispatchEvent neoForge,
            Function<BusGroup, EventBus<E>> bus,
            BiFunction<net.minecraftforge.fml.ModContainer, ModLoadingStage, E> event,
            ModLoadingStage stage) {
        if (QUEUED_STAGES.add(stage)) {
            neoForge.enqueueWork(() -> stage.getDeferredWorkQueue().runTasks());
        }
        try {
            ForgeModList.runAs(forge, () -> bus.apply(forge.getModBusGroup()).post(event.apply(forge, stage)));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw failed(e);
        }
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }

    /**
     * NeoForge's extension point {@code point} of this mod; NeoForge's {@code IConfigScreenFactory}, when the mod has none
     * of NeoForge's, is the config screen factory the Forge mod registered on its Forge container, when it has one ({@link
     * ForgeConfigScreens}, F-N6).
     */
    @Override
    public <T extends IExtensionPoint> Optional<T> getCustomExtension(Class<T> point) {
        Optional<T> own = super.getCustomExtension(point);
        if (own.isPresent() || !ForgeConfigScreens.isConfigScreenFactory(point)) {
            return own;
        }
        return ForgeConfigScreens.of(forge, point).map(point::cast);
    }
}
