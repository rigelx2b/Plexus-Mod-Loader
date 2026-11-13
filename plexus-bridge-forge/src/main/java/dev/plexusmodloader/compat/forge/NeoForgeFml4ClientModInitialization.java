package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.internal.transform.InvokeHookAtCall;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.internal.transform.TransformFailurePolicy;
import dev.plexusmodloader.internal.transform.TransformRuleDefinition;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plexus Mod Loader's mod initialization on NeoForge 21.1's client (FML 4 on ModLauncher, ADR-0043), after NeoForge has
 * gathered its mods and posted its {@code RegisterEvent}s, as on NeoForge's servers ({@link
 * NeoForgeServerModInitialization}, ADR-0026 31).
 *
 * <p>NeoForge 26.x builds FML's {@code ModList} before {@code Minecraft}'s constructor; NeoForge 21.1 builds it inside
 * the constructor, in {@code ClientModLoader.begin(Minecraft, PackRepository, ReloadableResourceManager)}, after
 * Minecraft's mod initialization point (where Fabric Loader initialises mods, before {@code new Options}). Other loaders'
 * mods initialised at that point find no {@code ModList}: a Fabric mod that registers a key mapping through the corpus's
 * NeoForge build of Architectury 13.0.11 (which stands in for the Fabric one, {@code 139}) stops the client with {@code
 * NullPointerException} in {@code EventBusesHooks.getModEventBus} (Ren's Timber 1.0.0, corpusQuiltNeoForgeClient
 * 2026-11-13). The rule {@link #RULE_ID} runs the initialization right after that method's call to {@code
 * begin(Runnable, boolean)}, which gathers NeoForge's mods and registers their content, and before it adds NeoForge's
 * mod packs; Sinytra Connector likewise runs Fabric mods during NeoForge's mod loading. Checked with {@code javap} on
 * NeoForge 21.1.265's universal jar and patched client: {@code ClientModLoader.begin(Minecraft, PackRepository,
 * ReloadableResourceManager)V} calls {@code ClientModLoader.begin(Runnable, Z)V} once; {@code Minecraft.<init>} calls the
 * former after {@code new Options}.
 *
 * <p>The rule stops the launch when it does not apply ({@link TransformFailurePolicy#ABORT_STARTUP}): without it, other
 * loaders' mods would not initialise at all.
 *
 * <p>Thread: the game's main thread (the render thread).
 */
public final class NeoForgeFml4ClientModInitialization {

    /** The rule that runs the deferred initialization after NeoForge's registration. */
    public static final String RULE_ID = "plexus:neoforge_fml4_client_mod_initialization";

    static final String CLIENT_MOD_LOADER = "net/neoforged/neoforge/client/loading/ClientModLoader";

    private static final AtomicReference<Runnable> PENDING = new AtomicReference<>();

    private NeoForgeFml4ClientModInitialization() {}

    /** The rule for NeoForge's clients of {@code gameVersion}: the hook after {@code ClientModLoader.begin}'s registration. */
    static TransformRuleDefinition rule(String gameVersion) {
        return new TransformRuleDefinition(
                RULE_ID,
                "plexus",
                gameVersion,
                Set.of(GameSide.CLIENT),
                new MethodTarget(
                        CLIENT_MOD_LOADER,
                        "begin",
                        "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/repository/PackRepository;"
                                + "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V"),
                new InvokeHookAtCall(
                        new MethodTarget(CLIENT_MOD_LOADER, "begin", "(Ljava/lang/Runnable;Z)V"),
                        0,
                        InvokeHookAtCall.Timing.AFTER,
                        NeoForgeFml4ClientModInitialization.class.getName().replace('.', '/'),
                        "afterRegistration"),
                TransformFailurePolicy.ABORT_STARTUP);
    }

    /** Keeps {@code modInitialization} for {@link #afterRegistration}. */
    static void defer(Runnable modInitialization) {
        Objects.requireNonNull(modInitialization, "modInitialization");
        if (!PENDING.compareAndSet(null, modInitialization)) {
            throw new IllegalStateException("Plexus Mod Loader's mod initialization was already deferred");
        }
    }

    /**
     * Called right after {@code ClientModLoader.begin(periodicTask, ...)}: runs the deferred mod initialization, once.
     *
     * @param periodicTask NeoForge's task between loading steps, unused
     * @param flag {@code begin}'s second argument, unused
     */
    public static void afterRegistration(Runnable periodicTask, boolean flag) {
        Runnable modInitialization = PENDING.getAndSet(null);
        if (modInitialization != null) {
            modInitialization.run();
        }
    }
}
