package dev.plexusmodloader.game.layer.v1_20_4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.world.flag.FeatureFlagSet;
import org.junit.jupiter.api.Test;

/**
 * Vanilla 1.20.4's {@code Pack.Info} takes (description, compatibility, features, overlays); NeoForge 20.4 replaces that
 * constructor with one that also takes {@code boolean isHidden}. The mod pack's information is built with whichever
 * the running game has.
 */
class ModPackInfoTest {

    /** Vanilla's shape. */
    record VanillaInfo(
            Component description, PackCompatibility compatibility, FeatureFlagSet features, List<String> overlays) {}

    /** NeoForge 20.4's shape: the vanilla constructor is gone. */
    record NeoForgeInfo(
            Component description,
            PackCompatibility compatibility,
            FeatureFlagSet features,
            List<String> overlays,
            boolean isHidden) {}

    /** Neither shape. */
    record OtherInfo(Component description) {}

    private static Object create(MethodHandle constructor, List<String> overlays) throws Throwable {
        return constructor.invoke(Component.literal("d"), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), overlays);
    }

    @Test
    void usesTheVanillaConstructorOfTheGameOnTheTestClassPath() throws Throwable {
        MethodHandle constructor = ModPacks.infoConstructor(MethodHandles.publicLookup(), Pack.Info.class);

        Pack.Info info = (Pack.Info) create(constructor, List.of("overlay_a"));

        assertEquals("d", info.description().getString());
        assertSame(PackCompatibility.COMPATIBLE, info.compatibility());
        assertEquals(List.of("overlay_a"), info.overlays());
    }

    @Test
    void usesTheVanillaConstructorWhenPresent() throws Throwable {
        MethodHandle constructor = ModPacks.infoConstructor(MethodHandles.lookup(), VanillaInfo.class);

        VanillaInfo info = (VanillaInfo) create(constructor, List.of("o"));

        assertEquals(List.of("o"), info.overlays());
    }

    @Test
    void passesNotHiddenToNeoForgesConstructor() throws Throwable {
        MethodHandle constructor = ModPacks.infoConstructor(MethodHandles.lookup(), NeoForgeInfo.class);

        NeoForgeInfo info = (NeoForgeInfo) create(constructor, List.of("o"));

        assertEquals(List.of("o"), info.overlays());
        assertSame(PackCompatibility.COMPATIBLE, info.compatibility());
        assertFalse(info.isHidden());
    }

    @Test
    void failsWithACodeWhenNeitherConstructorExists() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> ModPacks.infoConstructor(MethodHandles.lookup(), OtherInfo.class));

        assertTrue(failure.getMessage().startsWith("PLEXUS-RESOURCE-012 "), failure.getMessage());
        assertTrue(failure.getMessage().contains(OtherInfo.class.getName()), failure.getMessage());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
