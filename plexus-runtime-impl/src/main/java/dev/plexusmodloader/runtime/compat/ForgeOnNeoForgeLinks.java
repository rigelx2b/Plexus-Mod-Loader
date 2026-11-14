package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.GameHost;
import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.ModLoaderBridge;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forge on NeoForge (ADR-0051): whether the MinecraftForge mods handed to the NeoForge host link against the game it
 * runs, before they run. A Forge mod is compiled against Forge's patched game, and calls members Forge's patches add to
 * Minecraft classes ({@code Entity.canUpdate()} and the like); NeoForge's patched game lacks most of them, and such a
 * call would fail in the middle of a game. Each mod is checked as other loaders' mods are ({@link ModLinkVerifier}):
 * against NeoForge's patched game and the vanilla jar, the host's loader libraries (Forge's API jars among them), the
 * NeoForge mods and the other Forge mods, with members reached through the mod's own subclasses of game classes too
 * ({@link ModLinkVerifier#builtForAnotherPatch}). A mod class that overrides a method NeoForge's patch made {@code final}
 * is found as well ({@link HostPatchCheck#finalOverrides}).
 *
 * <p>The Forge API jars must be among the jars checked against: otherwise every class that uses Forge's API counts as
 * another loader's code path, and its missing Minecraft members are noted instead of blocking.
 *
 * <p>Thread: the launch thread, once, after the host is created and before it prepares the game.
 */
public final class ForgeOnNeoForgeLinks {

    private ForgeOnNeoForgeLinks() {}

    /**
     * What keeps each of {@code forgeMods} from running on {@code host}, by jar, in the order given; jars that link are
     * absent.
     *
     * @param hosted the host's launch, for the final override check
     * @param vanillaGameJar the vanilla game jar, for classes the host's jars do not hold
     * @param otherJars the NeoForge mods the host runs
     * @param forgeBridge the MinecraftForge bridge, whose runtime packages the check uses
     * @param platform the class loader with the JDK, the game's libraries and Plexus Mod Loader's own classes
     * @throws java.io.UncheckedIOException when a jar cannot be read
     */
    public static Map<Path, List<String>> check(
            GameHost host,
            HostedLaunch hosted,
            Path vanillaGameJar,
            GameSide side,
            List<Path> forgeMods,
            List<Path> otherJars,
            ModLoaderBridge forgeBridge,
            ClassLoader platform) {
        List<Path> gameJars = new ArrayList<>();
        gameJars.add(host.gameJar());
        gameJars.addAll(host.classBytesJars());
        gameJars.addAll(host.resourceJars());
        gameJars.add(vanillaGameJar);
        gameJars = gameJars.stream().distinct().toList();
        List<Path> installed = new ArrayList<>(host.loaderJars());
        installed.addAll(otherJars);
        installed.addAll(forgeMods);
        Map<Path, List<String>> reasons = new LinkedHashMap<>();
        try (ModLinkVerifier verifier = new ModLinkVerifier(gameJars, platform, side)) {
            verifier.hostLoaderJars(host.loaderJars());
            verifier.builtForAnotherPatch(
                    "added by MinecraftForge patches absent from NeoForge, or the mod was built for another Minecraft version");
            for (Path jar : forgeMods) {
                List<Path> others =
                        installed.stream().filter(other -> !other.equals(jar)).toList();
                for (CompatibilityFinding finding : verifier.verify(jar, others, forgeBridge, List.of())) {
                    if (finding.blocking()) {
                        reasons.computeIfAbsent(jar, key -> new ArrayList<>()).add(finding.detail());
                    }
                }
            }
        }
        List<Path> patched = new ArrayList<>();
        patched.add(host.gameJar());
        patched.addAll(host.classBytesJars());
        patched.addAll(host.resourceJars());
        HostPatchCheck.finalOverrides(hosted, patched.stream().distinct().toList(), platform, forgeMods)
                .forEach((jar, lines) ->
                        reasons.computeIfAbsent(jar, key -> new ArrayList<>()).addAll(lines));
        Map<Path, List<String>> ordered = new LinkedHashMap<>();
        for (Path jar : forgeMods) {
            List<String> lines = reasons.get(jar);
            if (lines != null) {
                ordered.put(jar, List.copyOf(lines));
            }
        }
        return ordered;
    }
}
