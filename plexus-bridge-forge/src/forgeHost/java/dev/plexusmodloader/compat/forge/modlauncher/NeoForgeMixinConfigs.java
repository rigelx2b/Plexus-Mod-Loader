package dev.plexusmodloader.compat.forge.modlauncher;

import cpw.mods.jarhandling.SecureJar;
import dev.plexusmodloader.compat.bridge.SharedMixinConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

/**
 * The Mixin configurations of NeoForge's mods on FML 4 and FML 2 (NeoForge 21.1 and 20.4, ADR-0043 point 10) for a launch
 * where Fabric's Mixin serves them too: FML's own Mixin does not start, and {@code LoadingModList.addMixinConfigs} calls
 * {@link #add} in place of FML's {@code DeferredMixinConfigRegistration.addMixinConfig} ({@code Fml4SharedMixinPatch}).
 *
 * <p>Thread: FML's startup thread ({@link #add}, {@link #configurations}).
 */
public final class NeoForgeMixinConfigs {

    /** The loader name of the configurations, for logs ({@link SharedMixinConfig#loaderName()}). */
    static final String LOADER = "NeoForge";

    /** Configuration name to mod ID, in FML's order. */
    private static final Map<String, String> FROM_FML = new LinkedHashMap<>();

    private NeoForgeMixinConfigs() {}

    /**
     * In place of FML's {@code DeferredMixinConfigRegistration.addMixinConfig(config, modId)}: a {@code [[mixins]]}
     * configuration of a NeoForge mod whose required mods FML found. Called by FML's patched {@code LoadingModList}.
     */
    public static synchronized void add(String config, String modId) {
        FROM_FML.putIfAbsent(config, modId);
    }

    /**
     * The configurations FML gave ({@link #add}), then those the manifests of {@code gameJars} name ({@code MixinConfigs},
     * which Mixin's platform agent reads from the jars FML's launch plugins are offered), each once.
     */
    static synchronized List<SharedMixinConfig> configurations(List<SecureJar> gameJars) {
        Map<String, String> configs = new LinkedHashMap<>(FROM_FML);
        for (SecureJar jar : gameJars) {
            for (String name : manifestConfigs(jar.moduleDataProvider().getManifest())) {
                configs.putIfAbsent(name, jar.name());
            }
        }
        List<SharedMixinConfig> shared = new ArrayList<>();
        configs.forEach((config, modId) -> shared.add(new SharedMixinConfig(LOADER, modId, config)));
        return List.copyOf(shared);
    }

    /**
     * The names Mixin's platform agent registers from {@code manifest}'s {@code MixinConfigs}: each comma-separated name,
     * trimmed, that ends in {@code .json}.
     */
    static List<String> manifestConfigs(Manifest manifest) {
        String value = manifest == null ? null : manifest.getMainAttributes().getValue("MixinConfigs");
        if (value == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String config : value.split(",")) {
            String name = config.trim();
            if (name.endsWith(".json")) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }
}
