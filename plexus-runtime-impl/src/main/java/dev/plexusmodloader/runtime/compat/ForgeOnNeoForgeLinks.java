package dev.plexusmodloader.runtime.compat;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.GameHost;
import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.ModLoaderBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Forge on NeoForge (ADR-0051): whether the MinecraftForge mods handed to the NeoForge host link against the game it
 * runs, before they run. A Forge mod is compiled against Forge's patched game, and calls members Forge's patches add to
 * Minecraft classes ({@code Entity.canUpdate()} and the like); NeoForge's patched game lacks most of them, and such a
 * call would fail in the middle of a game. Each mod is checked as other loaders' mods are ({@link ModLinkVerifier}):
 * against NeoForge's patched game and the vanilla jar, the host's loader libraries (Forge's API jars among them), the
 * NeoForge mods and the other Forge mods, with members reached through the mod's own subclasses of game classes too
 * ({@link ModLinkVerifier#builtForAnotherPatch}). A mod class that overrides a method NeoForge's patch made {@code final}
 * is found as well ({@link HostPatchCheck#finalOverrides}), and so is a mixin that names what NeoForge's patched game
 * lacks ({@link ForgeOnNeoForgeMixins}).
 *
 * <p>The Forge API jars must be among the jars checked against: otherwise every class that uses Forge's API counts as
 * another loader's code path, and its missing Minecraft members are noted instead of blocking.
 *
 * <p>Thread: the launch thread, once, after the host is created and before it prepares the game.
 */
public final class ForgeOnNeoForgeLinks {

    private ForgeOnNeoForgeLinks() {}

    /** Class files from jars, first jar first. Never defines a class. */
    private static final class GameClassFiles implements AutoCloseable {

        private final List<JarFile> jars = new ArrayList<>();

        GameClassFiles(List<Path> paths) throws IOException {
            try {
                for (Path path : paths) {
                    if (path != null && Files.isRegularFile(path)) {
                        jars.add(new JarFile(path.toFile()));
                    }
                }
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        Optional<byte[]> read(String internalName) {
            String name = internalName + ".class";
            for (JarFile jar : jars) {
                ZipEntry entry = jar.getEntry(name);
                if (entry != null) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        return Optional.of(input.readAllBytes());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to read " + name + " from " + jar.getName(), e);
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public void close() {
            for (JarFile jar : jars) {
                try {
                    jar.close();
                } catch (IOException e) {
                    // Read-only jars opened for this check; nothing was written to them.
                }
            }
        }
    }

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
        // A mixin may target NeoForge's own classes or another mod's, as well as the game's; the loader libraries
        // (Forge's API jars among them) are outside FML's game layer, where its Mixin transforms, so only supertypes.
        List<Path> mixinTargets = new ArrayList<>(patched);
        mixinTargets.add(vanillaGameJar);
        mixinTargets.addAll(otherJars);
        mixinTargets.addAll(forgeMods);
        try (GameClassFiles classes =
                        new GameClassFiles(mixinTargets.stream().distinct().toList());
                GameClassFiles libraries = new GameClassFiles(host.loaderJars())) {
            for (Path jar : forgeMods) {
                List<String> unfit = ForgeOnNeoForgeMixins.unfit(jar, side, classes::read, libraries::read);
                if (!unfit.isEmpty()) {
                    reasons.computeIfAbsent(jar, key -> new ArrayList<>())
                            .add(unfit.size() + " mixin part(s) that do not fit " + hosted.hostName()
                                    + "'s patched game: " + String.join("; ", unfit));
                }
                // FML's access transformers apply to the same classes as its Mixin.
                List<String> lines = ForgeOnNeoForgeAccessTransformers.unfit(jar, side, classes::read, libraries::read);
                if (!lines.isEmpty()) {
                    reasons.computeIfAbsent(jar, key -> new ArrayList<>())
                            .add(lines.size() + " access transformer line(s) that do not fit " + hosted.hostName()
                                    + "'s patched game: " + String.join("; ", lines));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to check the mixins and access transformers of Forge mods against NeoForge's game", e);
        }
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
