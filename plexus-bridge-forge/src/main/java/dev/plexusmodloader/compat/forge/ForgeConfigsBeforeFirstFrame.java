package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.RedirectCall;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * MinecraftForge's client draws its first loading frame after its mods' client and common configs are loaded.
 * Forge loads them in the state {@code CONFIG_LOAD} of its {@code LOAD} phase ({@code ModStateProvider}), which runs on
 * a mod loading worker once the first resource reload starts, while {@code Minecraft}'s constructor goes on to its
 * first {@code renderFrame}; a mod that reads a client config value in every frame then fails if the frame comes
 * first. Shoulder Surfing Reloaded 26.3-5.2.0 does in {@code GameRenderer.extractOptions}, and on a new installation,
 * where Forge writes each config file for the first time, the frame came first in two of four launches ({@code
 * IllegalStateException: Cannot get config value before config is loaded}). Checked with {@code javap} on Forge
 * 26.2-65.1.5 and 26.3-66.1.0: the constructor calls {@code ImmediateWindowHandler.loadingOverlay(Supplier, Supplier,
 * Consumer, boolean)} once, after creating the reload and right before its first {@code renderFrame}, and {@code
 * CONFIG_LOAD} is in the {@code LOAD} phase.
 *
 * <p>The rule {@link #RULE_ID} redirects that call to {@link #loadingOverlay}, which waits until Forge has completed
 * {@code CONFIG_LOAD} ({@code ModLoader.hasCompletedState}), its mod loading has failed ({@code
 * ModLoader.isLoadingStateValid()}), or {@link #LIMIT_SECONDS} have passed, and then makes Forge's call. Plexus Mod
 * Loader's early window stays open meanwhile, as it closes after the game's first loading frame. The rule is optional
 * ({@link TransformFailurePolicy#DISABLE_RULE}): without it the game starts as Forge alone starts it.
 *
 * <p>Thread: {@link #loadingOverlay} runs on the render thread, in {@code Minecraft}'s constructor.
 */
public final class ForgeConfigsBeforeFirstFrame {

    /** The rule that redirects Forge's loading overlay call in {@code Minecraft}'s constructor. */
    public static final String RULE_ID = "plexus:forge_configs_before_first_frame";

    /** The version adapter's rule on the same constructor, which comes first. */
    static final String MOD_INITIALIZATION_RULE_ID = "plexus:mod_initialization_client";

    static final String MINECRAFT = "net/minecraft/client/Minecraft";
    static final String IMMEDIATE_WINDOW_HANDLER = "net/minecraftforge/fml/loading/ImmediateWindowHandler";
    static final String MOD_LOADER = "net.minecraftforge.fml.ModLoader";
    static final String MOD_STATE_PROVIDER = "net.minecraftforge.fml.core.ModStateProvider";
    static final String MOD_LOADING_STATE = "net.minecraftforge.fml.IModLoadingState";

    /** How long the first frame waits for Forge's configs at most. */
    static final long LIMIT_SECONDS = 20;

    private static final MethodType LOADING_OVERLAY =
            MethodType.methodType(Supplier.class, Supplier.class, Supplier.class, Consumer.class, boolean.class);

    private static volatile Consumer<String> log = message -> {};

    private ForgeConfigsBeforeFirstFrame() {}

    /** The rule for Forge's patched client of {@code gameVersion}. */
    static TransformRuleDefinition rule(String gameVersion) {
        return new TransformRuleDefinition(
                RULE_ID,
                "plexus",
                gameVersion,
                Set.of(GameSide.CLIENT),
                new MethodTarget(MINECRAFT, "<init>", "(Lnet/minecraft/client/main/GameConfig;)V"),
                new RedirectCall(
                        new MethodTarget(
                                IMMEDIATE_WINDOW_HANDLER, "loadingOverlay", LOADING_OVERLAY.toMethodDescriptorString()),
                        0,
                        ForgeConfigsBeforeFirstFrame.class.getName().replace('.', '/'),
                        "loadingOverlay"),
                TransformFailurePolicy.DISABLE_RULE,
                List.of(MOD_INITIALIZATION_RULE_ID));
    }

    /** Sets where the wait is reported. */
    static void install(Consumer<String> reporter) {
        log = Objects.requireNonNull(reporter, "reporter");
    }

    /**
     * Replaces {@code Minecraft}'s call to Forge's {@code ImmediateWindowHandler.loadingOverlay}: waits for Forge's
     * configs, then makes the call with the same arguments.
     *
     * @throws IllegalStateException when Forge's classes cannot be found from the game's class loader, which the
     *     unchanged call would have failed on as well
     */
    public static Supplier<?> loadingOverlay(
            Supplier<?> minecraft, Supplier<?> reload, Consumer<Optional<Throwable>> onFinish, boolean fadeIn) {
        ClassLoader game = minecraft.getClass().getClassLoader();
        awaitConfigs(game);
        try {
            MethodHandle forge = MethodHandles.publicLookup()
                    .findStatic(
                            Class.forName(IMMEDIATE_WINDOW_HANDLER.replace('/', '.'), true, game),
                            "loadingOverlay",
                            LOADING_OVERLAY);
            return (Supplier<?>) forge.invokeExact(minecraft, reload, onFinish, fadeIn);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "PLEXUS-COMPAT-210 Forge's " + IMMEDIATE_WINDOW_HANDLER.replace('/', '.') + ".loadingOverlay"
                            + LOADING_OVERLAY + " is not reachable from Minecraft's class loader",
                    e);
        }
    }

    /** Waits until Forge completed {@code CONFIG_LOAD} or its loading failed, at most {@link #LIMIT_SECONDS}. */
    private static void awaitConfigs(ClassLoader game) {
        BooleanSupplier loaded;
        BooleanSupplier valid;
        try {
            Class<?> modLoader = Class.forName(MOD_LOADER, true, game);
            Object configLoad = Class.forName(MOD_STATE_PROVIDER, true, game)
                    .getField("CONFIG_LOAD")
                    .get(null);
            MethodHandle completed = MethodHandles.publicLookup()
                    .findStatic(
                            modLoader,
                            "hasCompletedState",
                            MethodType.methodType(boolean.class, Class.forName(MOD_LOADING_STATE, true, game)));
            MethodHandle validState = MethodHandles.publicLookup()
                    .findStatic(modLoader, "isLoadingStateValid", MethodType.methodType(boolean.class));
            loaded = () -> call(completed, configLoad);
            valid = () -> call(validState, null);
        } catch (ReflectiveOperationException e) {
            log.accept("Forge's loading states are not the shape Plexus Mod Loader waits on (" + e
                    + "); the game draws its first frame without waiting for Forge's configs");
            return;
        }
        log.accept(await(loaded, valid, System::nanoTime, TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)));
    }

    /**
     * Polls {@code loaded} until it is true, {@code valid} is false or {@code limitNanos} have passed on {@code clock};
     * returns the line that reports how it ended.
     */
    static String await(BooleanSupplier loaded, BooleanSupplier valid, LongSupplier clock, long limitNanos) {
        long start = clock.getAsLong();
        while (!loaded.getAsBoolean()) {
            long waited = clock.getAsLong() - start;
            if (!valid.getAsBoolean()) {
                return "Forge's mod loading failed before its configs were loaded; the game draws its first frame after "
                        + TimeUnit.NANOSECONDS.toMillis(waited) + " ms";
            }
            if (waited >= limitNanos) {
                return "Forge's configs were not loaded after " + TimeUnit.NANOSECONDS.toSeconds(limitNanos)
                        + " s; the game draws its first frame without them";
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        return "The game's first frame waited " + TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - start)
                + " ms for Forge's client and common configs (CONFIG_LOAD)";
    }

    private static boolean call(MethodHandle query, Object state) {
        try {
            return state == null ? (boolean) query.invoke() : (boolean) query.invoke(state);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
