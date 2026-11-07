package cpw.mods.modlauncher;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plexus Mod Loader's stand-in for ModLauncher's launch plugin handler (ADR-0021). Mods that add a launch plugin of their
 * own (ModernFix) read {@code Launcher.launchPlugins}, then this class's private field {@code plugins}, and put a changed
 * copy of that map back with reflection; {@code PlexusModLauncher} reads the plugins through {@link #plugins()} each time,
 * so that the copy takes effect. Not ModLauncher's code.
 */
public final class LaunchPluginHandler {

    private Map<String, ILaunchPluginService> plugins = new LinkedHashMap<>();

    /** The launch plugins by name, in order; the map a mod may have replaced. */
    public Map<String, ILaunchPluginService> plugins() {
        return plugins;
    }
}
