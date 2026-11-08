package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.InjectStaticCallAtHead;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Starts MinecraftForge's game event bus before a quick-play world's integrated server starts, as {@code
 * ClientModLoader.completeModLoading} would have.
 *
 * <p>Forge 1.20.1, 1.20.4 and 1.21.1 (EventBus 6) create {@code MinecraftForge.EVENT_BUS} shut down, and an event posted
 * on a shut-down bus reaches no listener. {@code ClientModLoader.completeModLoading} starts it, and {@code Minecraft}
 * calls that after {@code onResourceLoadFinished}. With {@code --quickPlaySingleplayer} (the Minecraft Launcher's quick
 * play and Plexus Mod Loader's window tasks), {@code onResourceLoadFinished} opens the world and waits for its
 * integrated server, so {@code ServerAboutToStartEvent} and {@code ServerStartingEvent} are posted on the shut-down
 * bus. Xaero's Minimap 26.6.0 and World Map 1.47.0 make their server data in {@code ServerStartingEvent} and disconnect
 * the player without it ({@code "Error loading server world map properties"}). Checked with {@code javap} on Forge
 * 47.4.28, 49.2.13 and 52.1.21: {@code ServerLifecycleHooks.handleServerStarting} posts on {@code EVENT_BUS} from
 * {@code IntegratedServer.initServer}, and {@code completeModLoading} calls {@code EVENT_BUS.start()} when no mod
 * loading error was recorded.
 *
 * <p>The rule {@link #RULE_ID} calls {@link #beforeIntegratedServer} at the start of {@code IntegratedServer.initServer},
 * which starts the bus when Forge's mod loading state is valid ({@code ModLoader.isLoadingStateValid}); {@code start}
 * does nothing on a bus already started. Forge 1.21.11 and later (EventBus 7) have no {@code IEventBus} with {@code
 * start} on {@code MinecraftForge.EVENT_BUS}, and the hook does nothing there. The rule is optional ({@link
 * TransformFailurePolicy#DISABLE_RULE}).
 *
 * <p>Thread: {@link #beforeIntegratedServer} runs on the integrated server's thread.
 */
public final class ForgeEventBusBeforeIntegratedServer {

    /** The rule that calls {@link #beforeIntegratedServer} when the integrated server initialises. */
    public static final String RULE_ID = "plexus:forge_event_bus_before_integrated_server";

    static final String INTEGRATED_SERVER = "net/minecraft/client/server/IntegratedServer";
    static final String MINECRAFT_FORGE = "net.minecraftforge.common.MinecraftForge";
    static final String EVENT_BUS_API = "net.minecraftforge.eventbus.api.IEventBus";
    static final String MOD_LOADER = "net.minecraftforge.fml.ModLoader";

    private static volatile Consumer<String> log = message -> {};
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private ForgeEventBusBeforeIntegratedServer() {}

    /** The rule for Forge's patched client of {@code gameVersion}. */
    static TransformRuleDefinition rule(String gameVersion) {
        return new TransformRuleDefinition(
                RULE_ID,
                "plexus",
                gameVersion,
                Set.of(GameSide.CLIENT),
                new MethodTarget(INTEGRATED_SERVER, "initServer", "()Z"),
                new InjectStaticCallAtHead(
                        ForgeEventBusBeforeIntegratedServer.class.getName().replace('.', '/'),
                        "beforeIntegratedServer"),
                TransformFailurePolicy.DISABLE_RULE,
                List.of());
    }

    /** Sets where the start of the bus is reported. */
    static void install(Consumer<String> reporter) {
        log = Objects.requireNonNull(reporter, "reporter");
    }

    /**
     * Starts {@code MinecraftForge.EVENT_BUS} when it is an EventBus 6 bus and Forge's mod loading has not failed.
     *
     * @param integratedServer the server about to initialise, used for its class loader
     * @throws IllegalStateException when Forge's bus or loading state cannot be called although they are there
     */
    public static void beforeIntegratedServer(Object integratedServer) {
        ClassLoader game = integratedServer.getClass().getClassLoader();
        Object bus;
        MethodHandle start;
        MethodHandle valid;
        try {
            Class<?> busApi = Class.forName(EVENT_BUS_API, true, game);
            bus = Class.forName(MINECRAFT_FORGE, true, game)
                    .getField("EVENT_BUS")
                    .get(null);
            if (!busApi.isInstance(bus)) {
                return;
            }
            start = MethodHandles.publicLookup().findVirtual(busApi, "start", MethodType.methodType(void.class));
            valid = MethodHandles.publicLookup()
                    .findStatic(
                            Class.forName(MOD_LOADER, true, game),
                            "isLoadingStateValid",
                            MethodType.methodType(boolean.class));
        } catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException e) {
            // Not EventBus 6 (Forge 1.21.11 and later): no shut-down bus to start.
            return;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-210 Forge's " + MINECRAFT_FORGE + ".EVENT_BUS is not reachable from Minecraft's"
                            + " class loader",
                    e);
        }
        try {
            if (!(boolean) valid.invoke()) {
                return;
            }
            start.invoke(bus);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        if (REPORTED.compareAndSet(false, true)) {
            log.accept("Forge's game event bus is started before the integrated server initialises (with quick play"
                    + " the world opens before ClientModLoader.completeModLoading starts it)");
        }
    }
}
