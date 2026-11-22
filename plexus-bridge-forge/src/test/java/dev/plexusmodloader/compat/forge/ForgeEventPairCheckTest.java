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
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Forge on NeoForge, F-N3: the event pairs hold for the NeoForge and Forge releases Plexus Mod Loader pins for Minecraft
 * 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 (their {@code universal} jars, from {@code plexus.forgeEventJars}), and the runtime
 * hands over the Forge mods that use them.
 */
class ForgeEventPairCheckTest {

    /** Each Minecraft 26.x release's NeoForge and Forge universal jar name prefixes. */
    @ParameterizedTest
    @CsvSource({
        "neoforge-26.1.0., forge-26.1-",
        "neoforge-26.1.1., forge-26.1.1-",
        "neoforge-26.1.2., forge-26.1.2-",
        "neoforge-26.2., forge-26.2-",
        "neoforge-26.3., forge-26.3-"
    })
    void everyPairHolds(String neoForge, String forge) throws IOException {
        assertEquals(List.of(), ForgeEventPairCheck.missing(ForgeEventPairs.GAME_EVENTS, jar(neoForge), jar(forge)));
    }

    /**
     * NeoForge's player events declare {@code getEntity()} bridges returning less specific types; the check takes the
     * player getter of their superclass, as reflection does, and a pair is checked whichever games it is passed on in.
     */
    @Test
    void playerAndClientPairsAreChecked() throws IOException {
        List<ForgeEventPairs.Pair> pairs = ForgeEventPairs.GAME_EVENTS.stream()
                .filter(pair -> pair.neoForge().contains(".PlayerEvent$")
                        || pair.neoForge().contains(".PlayerTickEvent$")
                        || pair.where() == ForgeEventPairs.Where.CLIENT)
                .toList();
        assertEquals(14, pairs.size());
        assertEquals(List.of(), ForgeEventPairCheck.missing(pairs, jar("neoforge-26.2."), jar("forge-26.2-")));
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

    /** NeoForge's and Forge's reload listener events have what the host passes on, on every 26.x release. */
    @ParameterizedTest
    @CsvSource({
        "neoforge-26.1.0., forge-26.1-",
        "neoforge-26.1.1., forge-26.1.1-",
        "neoforge-26.1.2., forge-26.1.2-",
        "neoforge-26.2., forge-26.2-",
        "neoforge-26.3., forge-26.3-"
    })
    void reloadListenersHold(String neoForge, String forge) throws IOException {
        assertEquals(List.of(), ForgeEventPairCheck.missingReloadListeners(jar(neoForge), jar(forge)));
    }

    /** NeoForge's and Forge's key mapping registration events have what the host passes on, on every 26.x release. */
    @ParameterizedTest
    @CsvSource({
        "neoforge-26.1.0., forge-26.1-",
        "neoforge-26.1.1., forge-26.1.1-",
        "neoforge-26.1.2., forge-26.1.2-",
        "neoforge-26.2., forge-26.2-",
        "neoforge-26.3., forge-26.3-"
    })
    void keyMappingsHold(String neoForge, String forge) throws IOException {
        assertEquals(List.of(), ForgeEventPairCheck.missingKeyMappings(jar(neoForge), jar(forge)));
    }

    @Test
    void keyMappingsThatDoNotHoldAreNamed() throws IOException {
        assertEquals(
                List.of(
                        "no net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent",
                        "no net/minecraftforge/client/event/RegisterKeyMappingsEvent"),
                ForgeEventPairCheck.missingKeyMappings(jar("forge-26.2-"), jar("neoforge-26.2.")));
    }

    /** With the two jars swapped, neither event is found and the wrapper is named. */
    @Test
    void reloadListenersThatDoNotHoldAreNamed() throws IOException {
        assertEquals(
                List.of(
                        "no net/neoforged/neoforge/event/AddServerReloadListenersEvent",
                        "no net/minecraftforge/event/AddReloadListenerEvent",
                        "net/minecraftforge/event/AddReloadListenerEvent$WrappedStateAwareListener: no"
                                + " wrapped()Lnet/minecraft/server/packs/resources/PreparableReloadListener;"),
                ForgeEventPairCheck.missingReloadListeners(jar("forge-26.2-"), jar("neoforge-26.2.")));
    }

    /**
     * F-N4: NeoForge's registry event and Forge's registry event have what registration passes on, and Forge's {@code
     * ForgeRegistries} and {@code IForgeRegistry} what the host's views of the game's registries need.
     */
    @ParameterizedTest
    @CsvSource({
        "neoforge-26.1.0., forge-26.1-",
        "neoforge-26.1.1., forge-26.1.1-",
        "neoforge-26.1.2., forge-26.1.2-",
        "neoforge-26.2., forge-26.2-",
        "neoforge-26.3., forge-26.3-"
    })
    void registrationHolds(String neoForge, String forge) throws IOException {
        assertEquals(List.of(), ForgeEventPairCheck.missingRegistration(jar(neoForge), jar(forge)));
    }

    /**
     * With Forge's jar in NeoForge's place, NeoForge's registry event is not found, and the other way round, where Forge's
     * {@code RegistryObject} is missing too.
     */
    @Test
    void registrationThatDoesNotHoldIsNamed() throws IOException {
        Path neoForge = jar("neoforge-26.2.");
        Path forge = jar("forge-26.2-");
        assertEquals(
                List.of("no net/neoforged/neoforge/registries/RegisterEvent"),
                ForgeEventPairCheck.missingRegistration(forge, forge));
        assertEquals(
                List.of(
                        "no net/minecraftforge/registries/RegisterEvent",
                        "net/minecraftforge/registries/RegistryObject.class: 0 MappedRegistry.isIntrusive() call(s)",
                        "no net/minecraftforge/registries/ForgeRegistries.class",
                        "no net/minecraftforge/registries/IForgeRegistry"),
                ForgeEventPairCheck.missingRegistration(neoForge, neoForge));
    }

    /** The runtime's scope check lets a Forge mod register with Forge's {@code DeferredRegister} and a registry key. */
    @Test
    void theScopeCheckAllowsRegistrationWithARegistryKey() {
        for (String forge : List.of(
                "net/minecraftforge/registries/DeferredRegister",
                "net/minecraftforge/registries/RegistryObject",
                "net/minecraftforge/registries/RegisterEvent",
                "net/minecraftforge/registries/ForgeRegistries$Keys")) {
            assertTrue(ForgeOnNeoForgeScope.allowed(forge), forge);
        }
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
