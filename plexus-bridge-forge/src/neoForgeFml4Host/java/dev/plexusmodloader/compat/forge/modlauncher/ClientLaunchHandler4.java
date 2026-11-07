package dev.plexusmodloader.compat.forge.modlauncher;

import net.neoforged.fml.loading.targets.NeoForgeClientLaunchHandler;

/**
 * NeoForge 21.1 / 20.4's own client launch handler ({@code NeoForgeClientLaunchHandler}: Minecraft and NeoForge from the libraries folder) under
 * Plexus Mod Loader's name (ADR-0043), without FML's replacement of Log4j's configuration: FML does that for every production
 * launch, with a configuration whose plugins (LoggerNamePatternSelector, TerminalConsole) the game class loader does not find,
 * which leaves the game without a console appender. The configuration the run was started with stays. The {@code --fml.*}\n * options are FML's; the game's own parser rejects them ({@link FmlArguments}).
 */
public final class ClientLaunchHandler4 extends NeoForgeClientLaunchHandler {

    /** Called by the service loader. */
    public ClientLaunchHandler4() {}

    @Override
    public String name() {
        return "plexusmodloader_client";
    }

    @Override
    protected String[] preLaunch(String[] arguments, ModuleLayer layer) {
        return FmlArguments.withoutFml(arguments);
    }
}
