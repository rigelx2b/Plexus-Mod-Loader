package org.quiltmc.loader.impl.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.quiltmc.config.api.ConfigEnvironment;
import org.quiltmc.config.api.Serializer;
import org.quiltmc.config.api.serializers.Json5Serializer;
import org.quiltmc.loader.impl.QuiltLoaderImpl;

/**
 * Plexus Mod Loader's stand-in for Quilt Loader's internal {@code QuiltConfigImpl}, the one class the packed {@code
 * QuiltConfig} API ({@code org.quiltmc.loader.api.config} and {@code .v2}) asks for its config environment. Like Quilt
 * Loader it saves to {@code QuiltLoader.getConfigDir()}, adds the serializers mods register as {@code
 * config_serializer} entrypoints, and reads {@code loader.defaultConfigExtension} and {@code
 * loader.globalConfigExtension}.
 *
 * <p>Unlike Quilt Loader, the built-in serializer is JSON5 only: Quilt Loader's TOML serializer needs NightConfig, which
 * Plexus Mod Loader does not ship, so without {@code loader.defaultConfigExtension} configs are {@code json5} files
 * instead of {@code toml} files.
 *
 * <p>Thread: the environment is created once, on the first call; later calls return it.
 */
// The packed QuiltConfig classes call getConfigEnvironment() with the deprecated api.ConfigEnvironment return type.
@SuppressWarnings("deprecation")
public final class QuiltConfigImpl {

    private static ConfigEnvironment environment;

    private QuiltConfigImpl() {}

    /** Creates the environment now, as Quilt Loader does while it starts. */
    public static synchronized void init() {
        Map<String, Serializer> serializers = new LinkedHashMap<>();
        serializers.put(Json5Serializer.INSTANCE.getFileExtension(), Json5Serializer.INSTANCE);
        for (Serializer serializer : QuiltLoaderImpl.INSTANCE.getEntrypoints("config_serializer", Serializer.class)) {
            serializers.put(serializer.getFileExtension(), serializer);
        }
        String global = System.getProperty("loader.globalConfigExtension");
        String fallback = System.getProperty("loader.defaultConfigExtension");
        // Quilt Loader's own messages.
        if (global != null && !serializers.containsKey(global)) {
            throw new RuntimeException(
                    "Cannot use file extension " + global + " globally: no matching serializer found");
        }
        if (fallback != null && !serializers.containsKey(fallback)) {
            throw new RuntimeException(
                    "Cannot use file extension " + fallback + " by default: no matching serializer found");
        }
        // As Quilt Loader: without a default extension, the first serializer is the default.
        Serializer defaultSerializer =
                fallback == null ? serializers.values().iterator().next() : serializers.get(fallback);
        ConfigEnvironment created =
                new ConfigEnvironment(QuiltLoaderImpl.INSTANCE.getConfigDir(), global, defaultSerializer);
        for (Serializer serializer : serializers.values()) {
            if (serializer != defaultSerializer) {
                created.registerSerializer(serializer);
            }
        }
        environment = created;
    }

    /** The environment {@code QuiltConfig.create} saves configs in, created on the first call. */
    public static synchronized ConfigEnvironment getConfigEnvironment() {
        if (environment == null) {
            init();
        }
        return environment;
    }
}
