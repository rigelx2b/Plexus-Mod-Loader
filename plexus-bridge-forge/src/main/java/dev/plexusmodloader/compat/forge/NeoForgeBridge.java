package dev.plexusmodloader.compat.forge;

import dev.plexusmodloader.compat.bridge.GameHost;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** NeoForge mods ({@code META-INF/neoforge.mods.toml}): diagnosed, and run by FML through {@link #gameHost}. */
public final class NeoForgeBridge extends FmlModsTomlBridge {

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public String metadataPath() {
        return "META-INF/neoforge.mods.toml";
    }

    /**
     * FML loads NeoForge mods itself, with Plexus Mod Loader's game class loader defining its game layer: FML 10 and later
     * without ModLauncher (ADR-0026), FML 4 of NeoForge 21.1 and 20.4 on Plexus Mod Loader's ModLauncher (ADR-0043).
     */
    @Override
    public Optional<GameHost> gameHost(GameHost.Request request, List<Path> modJars) throws IOException {
        Optional<NeoForgeInstallation.Release> release = NeoForgeInstallation.release(request.gameVersion());
        if (release.isPresent()
                && NeoForgeFml4GameHost.runsOnModLauncher(release.get().neoForgeVersion())) {
            return Optional.of(NeoForgeFml4GameHost.create(request, release.get(), modJars));
        }
        return Optional.of(NeoForgeGameHost.create(request, modJars));
    }
}
