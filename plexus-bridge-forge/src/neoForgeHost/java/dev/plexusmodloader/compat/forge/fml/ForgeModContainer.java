package dev.plexusmodloader.compat.forge.fml;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.lifecycle.ParallelDispatchEvent;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Forge on NeoForge (ADR-0051, F-N2): a MinecraftForge mod as one of FML's mods. FML sees a NeoForge mod container; it
 * holds Forge's own {@code FMLModContainer} (from Forge's {@code javafmllanguage} jar among the loader libraries), which
 * loads the mod's class from its module in FML's game layer and constructs it as Forge would, with Forge's {@code
 * FMLJavaModLoadingContext} and mod bus group. When FML's mod bus has {@code FMLCommonSetupEvent}, {@code
 * FMLClientSetupEvent}, {@code FMLDedicatedServerSetupEvent} or {@code FMLLoadCompleteEvent}, the Forge mod's bus gets
 * Forge's event of the same name (F-N2, F-N3), and the first construction adds the game bus events of {@link
 * ForgeGameEvents} (F-N3).
 *
 * <p>No other Forge event or loading stage is passed on yet, and work a mod queues with {@code enqueueWork} would not
 * run; the runtime hands over only mods that use none of that ({@code ForgeOnNeoForgeScope}).
 *
 * <p>Thread: FML constructs mods and sends setup events on its worker threads, one container at a time.
 */
final class ForgeModContainer extends ModContainer {

    private final FMLModContainer forge;
    private final IEventBus eventBus;
    private final ClassLoader gameClassLoader;

    private ForgeModContainer(IModInfo info, String modClass, ModuleLayer gameLayer) {
        super(info);
        this.forge = new FMLModContainer(
                new ForgeModView(info),
                modClass,
                new net.minecraftforge.forgespi.language.ModFileScanData(),
                gameLayer);
        this.eventBus = BusBuilder.builder()
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build();
        this.gameClassLoader = gameLayer.modules().iterator().next().getClassLoader();
        eventBus.addListener(
                FMLCommonSetupEvent.class,
                event -> post(
                        net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent::new,
                        ModLoadingStage.COMMON_SETUP));
        eventBus.addListener(
                FMLClientSetupEvent.class,
                event -> post(
                        net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent::new,
                        ModLoadingStage.SIDED_SETUP));
        eventBus.addListener(
                FMLDedicatedServerSetupEvent.class,
                event -> post(
                        net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent::new,
                        ModLoadingStage.SIDED_SETUP));
        eventBus.addListener(
                FMLLoadCompleteEvent.class,
                event -> post(
                        net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent::getBus,
                        net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent::new,
                        ModLoadingStage.COMPLETE));
    }

    /** The container of the Forge mod {@code info} whose {@code @Mod} class is {@code modClass}. */
    static ModContainer create(IModInfo info, String modClass, ModuleLayer gameLayer) {
        try {
            return new ForgeModContainer(info, modClass, gameLayer);
        } catch (RuntimeException e) {
            // Forge's container fails as Forge's would (its module or class not found); FML reports it for the mod.
            throw new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmodclass")
                    .withCause(e)
                    .withAffectedMod(info));
        }
    }

    /**
     * Runs Forge's construction of the mod: its private {@code constructMod}, as Forge's loading stage would. The first
     * Forge mod constructed adds the game bus events Forge mods get ({@link ForgeGameEvents}).
     */
    @Override
    protected void constructMod() {
        try {
            ForgeGameEvents.install(gameClassLoader, PlexusFml::log);
            Method construct = FMLModContainer.class.getDeclaredMethod("constructMod");
            construct.setAccessible(true);
            construct.invoke(forge);
        } catch (InvocationTargetException e) {
            throw failed(e.getCause());
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw failed(e);
        }
    }

    private ModLoadingException failed(Throwable cause) {
        return new ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.failedtoloadmod")
                .withCause(cause)
                .withAffectedMod(modInfo));
    }

    /** Posts Forge's lifecycle event made by {@code event} for {@code stage} to the Forge mod's bus group. */
    private <E extends ParallelDispatchEvent> void post(
            Function<BusGroup, EventBus<E>> bus,
            BiFunction<net.minecraftforge.fml.ModContainer, ModLoadingStage, E> event,
            ModLoadingStage stage) {
        bus.apply(forge.getModBusGroup()).post(event.apply(forge, stage));
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }
}
