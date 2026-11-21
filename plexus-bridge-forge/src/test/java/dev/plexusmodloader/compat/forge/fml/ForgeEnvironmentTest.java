package dev.plexusmodloader.compat.forge.fml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Forge on NeoForge: how the host sets Forge's side and game directory, against stand-ins for Forge's {@code FMLLoader}
 * and {@code Dist}. A failure stops the launch with {@code PLEXUS-COMPAT-230}, never with {@code 229} (which leaves the
 * Forge mods out and goes on); errors of the JVM itself pass through.
 */
class ForgeEnvironmentTest {

    enum Side {
        CLIENT,
        DEDICATED_SERVER
    }

    /** Forge's {@code FMLLoader} as the pre-launch check saw it. */
    static final class Loader {
        private static Side dist;
        private static Path gamePath;
    }

    /** A release whose {@code dist} field has another type. */
    static final class WrongFields {
        @SuppressWarnings("unused")
        private static String dist;

        @SuppressWarnings("unused")
        private static Path gamePath;
    }

    /** A release whose static initializer fails (it would throw {@code ExceptionInInitializerError}). */
    static final class FailingInitializer {
        @SuppressWarnings("unused")
        private static Side dist;

        @SuppressWarnings("unused")
        private static Path gamePath;

        static {
            fail(new IllegalStateException("stand-in initializer failure"));
        }
    }

    /** A static initializer that runs out of memory: the JVM's error, not FMLLoader's shape. */
    static final class OutOfMemoryInitializer {
        @SuppressWarnings("unused")
        private static Side dist;

        @SuppressWarnings("unused")
        private static Path gamePath;

        static {
            fail(new OutOfMemoryError("stand-in"));
        }
    }

    private static <T extends Throwable> void fail(T throwable) throws T {
        throw throwable;
    }

    @Test
    void theFieldsAreSet() throws ReflectiveOperationException {
        Path directory = Path.of("game", "..", "game");

        ForgeEnvironment.set(Loader.class, Side.class, "DEDICATED_SERVER", directory);

        assertEquals(Side.DEDICATED_SERVER, field(Loader.class, "dist"));
        assertEquals(directory.toAbsolutePath().normalize(), field(Loader.class, "gamePath"));
    }

    @Test
    void aFieldOfAnotherTypeStopsTheLaunchWith230() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ForgeEnvironment.set(WrongFields.class, Side.class, "CLIENT", Path.of("game")));
        assertTrue(thrown.getMessage().startsWith("PLEXUS-COMPAT-230 "), thrown::getMessage);
        assertInstanceOf(NoSuchFieldException.class, thrown.getCause());
    }

    @Test
    void aSideForgeDoesNotHaveStopsTheLaunchWith230() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ForgeEnvironment.set(Loader.class, Side.class, "SERVER", Path.of("game")));
        assertTrue(thrown.getMessage().startsWith("PLEXUS-COMPAT-230 "), thrown::getMessage);
        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
    }

    @Test
    void aFailingStaticInitializerStopsTheLaunchWith230() {
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> ForgeEnvironment.set(FailingInitializer.class, Side.class, "CLIENT", Path.of("game")));
        assertTrue(thrown.getMessage().startsWith("PLEXUS-COMPAT-230 "), thrown::getMessage);
        assertInstanceOf(ExceptionInInitializerError.class, thrown.getCause());
    }

    @Test
    void anOutOfMemoryErrorPassesThrough() {
        OutOfMemoryError thrown = assertThrows(
                OutOfMemoryError.class,
                () -> ForgeEnvironment.set(OutOfMemoryInitializer.class, Side.class, "CLIENT", Path.of("game")));
        assertEquals("stand-in", thrown.getMessage());
        assertSame(null, thrown.getCause());
    }

    private static Object field(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
