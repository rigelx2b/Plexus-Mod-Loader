package dev.plexusmodloader.compat.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.runtime.compat.ForgeOnNeoForgeScope;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Forge on NeoForge, F-N3: the event pairs hold for the NeoForge and Forge releases Plexus Mod Loader pins for Minecraft
 * 26.2 and 26.3 (their {@code universal} jars, from {@code plexus.forgeEventJars}), and the runtime hands over the Forge
 * mods that use them.
 */
class ForgeEventPairCheckTest {

    @ParameterizedTest
    @ValueSource(strings = {"26.2", "26.3"})
    void everyPairHolds(String minecraft) throws IOException {
        assertEquals(
                List.of(),
                ForgeEventPairCheck.missing(
                        ForgeEventPairs.GAME_EVENTS,
                        jar("neoforge-" + minecraft + "."),
                        jar("forge-" + minecraft + "-")));
    }

    /** A cancellable Forge event paired with a NeoForge event that cannot be cancelled, and a constructor not found. */
    @Test
    void aPairThatDoesNotHoldIsNamed() throws IOException {
        ForgeEventPairs.Pair wrong = new ForgeEventPairs.Pair(
                "net.neoforged.neoforge.event.server.ServerStartingEvent",
                "net.minecraftforge.event.entity.EntityJoinLevelEvent",
                List.of(new ForgeEventPairs.Getter("getServer")));
        List<String> missing = ForgeEventPairCheck.missing(List.of(wrong), jar("neoforge-26.2."), jar("forge-26.2-"));
        assertEquals(1, missing.size());
        assertTrue(
                missing.getFirst().contains("no public <init>(Lnet/minecraft/server/MinecraftServer;)V"),
                missing::toString);
        assertTrue(missing.getFirst().contains("cancellable, but"), missing::toString);
    }

    @Test
    void aMissingGetterIsNamed() throws IOException {
        ForgeEventPairs.Pair wrong = new ForgeEventPairs.Pair(
                "net.neoforged.neoforge.event.server.ServerStartingEvent",
                "net.minecraftforge.event.server.ServerStartingEvent",
                List.of(new ForgeEventPairs.Getter("getLevel")));
        assertEquals(
                List.of("net.neoforged.neoforge.event.server.ServerStartingEvent ->"
                        + " net.minecraftforge.event.server.ServerStartingEvent: no getLevel()"),
                ForgeEventPairCheck.missing(List.of(wrong), jar("neoforge-26.2."), jar("forge-26.2-")));
    }

    /** The runtime's scope check lets a Forge mod name every Forge event the host passes on. */
    @Test
    void theScopeCheckAllowsEveryPassedOnEvent() {
        for (ForgeEventPairs.Pair pair : ForgeEventPairs.GAME_EVENTS) {
            String forge = pair.forge().replace('.', '/');
            assertTrue(ForgeOnNeoForgeScope.allowed(forge), forge);
        }
    }

    private static Path jar(String fileNamePrefix) {
        String jars = System.getProperty("plexus.forgeEventJars");
        return Arrays.stream(jars.split(File.pathSeparator))
                .map(Path::of)
                .filter(jar -> jar.getFileName().toString().startsWith(fileNamePrefix))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + fileNamePrefix + "* in " + jars));
    }
}
