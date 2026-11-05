package dev.plexusmodloader.game.layer.v1_20_1;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.plexusmodloader.game.version.layer.ModResourcePack;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds every mod jar as a required pack ({@code plexus/<mod id>}): its {@code assets/} to the client's resource packs
 * and its {@code data/} to the server's data packs, above the game's own packs.
 *
 * <p>Minecraft 1.20.1 has no pack overlays (1.20.2 added them), so this class reads a jar's {@code pack.mcmeta} the way
 * later versions do and puts the overlay directories for the running game's pack format in front of the jar: one mod
 * can carry data and assets for several versions, on this version too.
 */
final class ModPacks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static volatile Field packTypeField;

    private ModPacks() {}

    static void addTo(BuiltInPackSource source, Consumer<Pack> output) {
        LayerState.context().ifPresent(context -> {
            PackType type = packType(source);
            String root = type == PackType.CLIENT_RESOURCES ? "assets/" : "data/";
            int format = SharedConstants.getCurrentVersion().getPackVersion(type);
            for (ModResourcePack mod : context.resourcePacks()) {
                if (hasEntry(mod.jar(), name -> name.startsWith(root))) {
                    String id = "plexus/" + mod.modId();
                    List<String> overlays = overlays(id, mod.jar(), format);
                    output.accept(pack(
                            id,
                            mod.name(),
                            mod.name() + " " + mod.version() + " (Plexus Mod Loader)",
                            name -> new LaterFormatPack(jarPack(name, mod.jar(), overlays, type)),
                            type));
                }
            }
            if (type == PackType.SERVER_DATA) {
                LayerState.generatedDataPack()
                        .ifPresent(directory -> output.accept(pack(
                                "plexus/generated",
                                "Plexus Mod Loader generated data",
                                "Data Plexus Mod Loader derives from mod declarations",
                                name -> new PathPackResources(name, directory, true),
                                type)));
            }
        });
    }

    /**
     * A required pack on top of the game's, compatible whatever the jar's {@code pack.mcmeta} names: the mod's metadata
     * decided where it runs. 1.20.1 has no known-pack information (that arrived in 1.20.5), so dimensions that mod packs
     * add are declared stable by {@code GameHooks.onCheckDimensionStability} instead.
     */
    private static Pack pack(
            String id, String title, String description, Pack.ResourcesSupplier resources, PackType type) {
        return Pack.create(
                id,
                Component.literal(title),
                true,
                resources,
                new Pack.Info(
                        Component.literal(description),
                        SharedConstants.getCurrentVersion().getPackVersion(type),
                        FeatureFlagSet.of()),
                type,
                Pack.Position.TOP,
                false,
                PackSource.BUILT_IN);
    }

    /**
     * The overlay directories the jar's {@code pack.mcmeta} lists for {@code format}, in the file's order; none without
     * a {@code pack.mcmeta} or {@code overlays}. An entry's {@code formats} is what 1.20.2 to 1.21.8 read: a number, a
     * {@code [min, max]} pair or a {@code {min_inclusive, max_inclusive}} object.
     */
    static List<String> overlays(String id, Path jar, int format) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("pack.mcmeta");
            if (entry == null) {
                return List.of();
            }
            JsonObject meta;
            try (InputStream input = zip.getInputStream(entry)) {
                meta = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            return overlays(meta, format, id);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-RESOURCE-010 cannot read pack.mcmeta of {} ({}); its overlays are not"
                            + " used",
                    id,
                    e.toString());
            return List.of();
        }
    }

    static List<String> overlays(JsonObject meta, int format, String id) {
        if (!meta.has("overlays")) {
            return List.of();
        }
        List<String> directories = new ArrayList<>();
        for (JsonElement element : meta.getAsJsonObject("overlays").getAsJsonArray("entries")) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("formats") && includes(entry.get("formats"), format)) {
                directories.add(entry.get("directory").getAsString());
            }
        }
        if (!directories.isEmpty()) {
            LOGGER.info("[Plexus Mod Loader] Pack {} uses overlays {}", id, directories);
        }
        return List.copyOf(directories);
    }

    private static boolean includes(JsonElement formats, int format) {
        if (formats.isJsonArray()) {
            JsonArray range = formats.getAsJsonArray();
            return range.size() == 2
                    && range.get(0).getAsInt() <= format
                    && format <= range.get(1).getAsInt();
        }
        if (formats.isJsonObject()) {
            JsonObject range = formats.getAsJsonObject();
            return range.get("min_inclusive").getAsInt() <= format
                    && format <= range.get("max_inclusive").getAsInt();
        }
        return formats.getAsInt() == format;
    }

    /**
     * The jar as a pack, with {@code overlays} in front of it; the last overlay listed wins, as in later versions. The
     * file system is the pack's own and not the shared one for the jar's URI: a client holds the jar's resource pack
     * open while it opens the same jar's data pack.
     */
    static PackResources jarPack(String name, Path jar, List<String> overlays, PackType type) {
        PackResources base = new FilePackResources(name, jar.toFile(), false);
        if (overlays.isEmpty()) {
            return base;
        }
        FileSystem zip;
        try {
            zip = FileSystems.newFileSystem(jar);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-RESOURCE-010 cannot open {} for its overlays {}: {}",
                    jar,
                    overlays,
                    e.toString());
            return base;
        }
        List<PackResources> layers = new ArrayList<>();
        for (String overlay : overlays) {
            // 1.20.1's data and resource packs share format 15, so an overlay of the other kind can match; the game's
            // PathPackResources logs an error for a folder that does not exist.
            if (Files.isDirectory(zip.getPath(overlay, type == PackType.CLIENT_RESOURCES ? "assets" : "data"))) {
                layers.add(new PathPackResources(name, zip.getPath(overlay), false));
            }
        }
        Collections.reverse(layers);
        layers.add(base);
        return new LayeredPackResources(name, layers, zip);
    }

    /** {@code layers}, first over last. Metadata and root files come from the jar itself, the last layer. */
    private record LayeredPackResources(String name, List<PackResources> layers, FileSystem zip)
            implements PackResources {

        private PackResources jar() {
            return layers.get(layers.size() - 1);
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            return jar().getRootResource(path);
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            for (PackResources layer : layers) {
                IoSupplier<InputStream> resource = layer.getResource(type, location);
                if (resource != null) {
                    return resource;
                }
            }
            return null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            Set<ResourceLocation> seen = new LinkedHashSet<>();
            for (PackResources layer : layers) {
                layer.listResources(type, namespace, path, (location, resource) -> {
                    if (seen.add(location)) {
                        output.accept(location, resource);
                    }
                });
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> namespaces = new LinkedHashSet<>();
            for (PackResources layer : layers) {
                namespaces.addAll(layer.getNamespaces(type));
            }
            return namespaces;
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
            return jar().getMetadataSection(serializer);
        }

        @Override
        public String packId() {
            return name;
        }

        @Override
        public boolean isBuiltin() {
            return jar().isBuiltin();
        }

        @Override
        public void close() {
            layers.forEach(PackResources::close);
            try {
                zip.close();
            } catch (IOException e) {
                LOGGER.warn("[Plexus Mod Loader] Cannot close the jar of pack {}: {}", name, e.toString());
            }
        }
    }

    private static boolean hasEntry(Path jar, Predicate<String> name) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> name.test(entry.getName()));
        } catch (IOException e) {
            LOGGER.warn("[Plexus Mod Loader] Cannot read {} to add it as a pack: {}", jar, e.toString());
            return false;
        }
    }

    private static PackType packType(BuiltInPackSource source) {
        try {
            Field field = packTypeField;
            if (field == null) {
                field = BuiltInPackSource.class.getDeclaredField("packType");
                field.setAccessible(true);
                packTypeField = field;
            }
            return (PackType) field.get(source);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("PLEXUS-CONTENT-004 cannot tell which pack type " + source + " loads", e);
        }
    }
}
