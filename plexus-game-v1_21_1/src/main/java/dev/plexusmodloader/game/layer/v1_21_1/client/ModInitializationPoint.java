package dev.plexusmodloader.game.layer.v1_21_1.client;

import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;

/**
 * The client's state at Minecraft's mod initialization point, for an initialization a host runs later.
 *
 * <p>Minecraft's mod initialization point (where Fabric Loader initialises mods) is right before {@code this.gameThread =
 * Thread.currentThread()} in {@code Minecraft}'s constructor, so there {@code Minecraft.execute(Runnable)} queues the
 * task for the first frame instead of running it ({@code isSameThread()} is false), and mods use it to wait for parts of
 * the client made later in the constructor. NeoForge 21.1 runs the initialization after its own mod loading, later in
 * the constructor, where the game thread is set (ADR-0043): Capes 1.21-1.21.1's client entrypoint queues a texture
 * registration that would run at once there, before {@code textureManager} exists ({@code NullPointerException},
 * corpusQuiltNeoForgeClient 2026-11-13). Checked with {@code javap} on NeoForge 21.1.265's patched client: the field
 * {@code gameThread} is written at the start of line 445 of {@code Minecraft.<init>} and {@code textureManager} after
 * {@code ClientModLoader.begin}.
 *
 * <p>Thread: the game's main thread.
 */
public final class ModInitializationPoint {

    private ModInitializationPoint() {}

    /** Runs {@code modInitialization} with {@code Minecraft}'s game thread unset, then sets it back. */
    public static void runWithoutGameThread(Runnable modInitialization) {
        Minecraft minecraft = Minecraft.getInstance();
        Field gameThread;
        Object thread;
        try {
            gameThread = Minecraft.class.getDeclaredField("gameThread");
            gameThread.setAccessible(true);
            thread = minecraft == null ? null : gameThread.get(minecraft);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "PLEXUS-BOOTSTRAP-008 Failed to read Minecraft.gameThread required to run Fabric and Quilt mod initialization before it is set",
                    e);
        }
        if (thread == null) {
            modInitialization.run();
            return;
        }
        try {
            gameThread.set(minecraft, null);
            try {
                modInitialization.run();
            } finally {
                gameThread.set(minecraft, thread);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "PLEXUS-BOOTSTRAP-008 Failed to clear or restore Minecraft.gameThread required to run Fabric and Quilt mod initialization before it is set",
                    e);
        }
    }
}
