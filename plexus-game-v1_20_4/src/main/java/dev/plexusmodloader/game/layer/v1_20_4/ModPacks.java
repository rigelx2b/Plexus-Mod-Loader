package dev.plexusmodloader.game.layer.v1_20_4;

import dev.plexusmodloader.game.version.layer.ModResourcePack;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds every mod jar as a required pack ({@code plexus/<mod id>}): its {@code assets/} to the client's
 * resource packs and its {@code data/} to the server's data packs, above the game's own packs. A jar
 * with a {@code pack.mcmeta} also gets the overlay directories it lists for the running game's pack
 * format, as the game's own packs do, so one mod can carry data and assets for several versions.
 */
final class ModPacks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    private static volatile Field packTypeField;
    private static volatile MethodHandle infoConstructor;

    private ModPacks() {}

    static void addTo(BuiltInPackSource source, Consumer<Pack> output) {
        LayerState.context().ifPresent(context -> {
            PackType type = packType(source);
            String root = type == PackType.CLIENT_RESOURCES ? "assets/" : "data/";
            for (ModResourcePack mod : context.modPacks()) {
                if (hasEntry(mod.jar(), name -> name.startsWith(root))) {
                    String id = "plexus/" + mod.modId();
                    Pack.ResourcesSupplier resources =
                            laterFormats(new FilePackResources.FileResourcesSupplier(mod.jar(), false));
                    output.accept(pack(
                            id,
                            mod.name(),
                            mod.name() + " " + mod.version() + " (Plexus Mod Loader)",
                            resources,
                            overlays(id, resources, type, mod.jar())));
                }
            }
            if (type == PackType.SERVER_DATA) {
                LayerState.generatedDataPack()
                        .ifPresent(directory -> output.accept(pack(
                                "plexus/generated",
                                "Plexus Mod Loader generated data",
                                "Data Plexus Mod Loader derives from mod declarations",
                                new PathPackResources.PathResourcesSupplier(directory, true),
                                List.of())));
            }
        });
    }

    /** The jar's pack, with or without its overlays, as {@link LaterFormatPack} shows it. */
    private static Pack.ResourcesSupplier laterFormats(Pack.ResourcesSupplier jar) {
        return new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(String id) {
                return new LaterFormatPack(jar.openPrimary(id));
            }

            @Override
            public PackResources openFull(String id, Pack.Info info) {
                return new LaterFormatPack(jar.openFull(id, info));
            }
        };
    }

    /**
     * A required pack on top of the game's. 1.20.4 has no known-pack information (that arrived in 1.20.5), so
     * dimensions that mod packs add are declared stable by {@code GameHooks.onCheckDimensionStability} instead.
     */
    private static Pack pack(
            String id, String title, String description, Pack.ResourcesSupplier resources, List<String> overlays) {
        return Pack.create(
                id,
                Component.literal(title),
                true,
                resources,
                info(Component.literal(description), overlays),
                Pack.Position.TOP,
                false,
                PackSource.BUILT_IN);
    }

    /** A compatible pack's information with no required features and the given overlays. */
    private static Pack.Info info(Component description, List<String> overlays) {
        MethodHandle constructor = infoConstructor;
        if (constructor == null) {
            constructor = infoConstructor(MethodHandles.publicLookup(), Pack.Info.class);
            infoConstructor = constructor;
        }
        try {
            return (Pack.Info)
                    constructor.invoke(description, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), overlays);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("PLEXUS-RESOURCE-012 cannot create the pack information of a mod pack", e);
        }
    }

    /**
     * {@code type}'s constructor taking (description, compatibility, required features, overlays), as a handle of
     * that type. Vanilla 1.20.4 has only that constructor; NeoForge 20.4 replaces it with one that also takes
     * {@code boolean isHidden}, which is passed {@code false} (the vanilla value). Throws {@code
     * PLEXUS-RESOURCE-012} when the class has neither.
     */
    static MethodHandle infoConstructor(MethodHandles.Lookup lookup, Class<?> type) {
        MethodType vanilla = MethodType.methodType(
                void.class, Component.class, PackCompatibility.class, FeatureFlagSet.class, List.class);
        try {
            return lookup.findConstructor(type, vanilla);
        } catch (NoSuchMethodException vanillaMissing) {
            try {
                MethodHandle hidden = lookup.findConstructor(type, vanilla.appendParameterTypes(boolean.class));
                return MethodHandles.insertArguments(hidden, 4, false);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                e.addSuppressed(vanillaMissing);
                throw new IllegalStateException(
                        "PLEXUS-RESOURCE-012 " + type.getName() + " has neither the vanilla constructor " + vanilla
                                + " nor NeoForge's with a trailing boolean isHidden",
                        e);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("PLEXUS-RESOURCE-012 cannot use the constructor of " + type.getName(), e);
        }
    }

    /**
     * The overlay directories the jar's {@code pack.mcmeta} lists for the running game's pack format, read
     * the way the game reads its own packs; none without a {@code pack.mcmeta}. The pack stays required and
     * compatible whatever formats the file names: the mod's metadata decided where it runs.
     */
    static List<String> overlays(String id, Pack.ResourcesSupplier resources, PackType type, Path jar) {
        if (!hasEntry(jar, name -> name.equals("pack.mcmeta"))) {
            return List.of();
        }
        Pack.Info info = Pack.readPackInfo(
                id, resources, SharedConstants.getCurrentVersion().getPackVersion(type));
        if (info == null) {
            LOGGER.warn(
                    "[Plexus Mod Loader] PLEXUS-RESOURCE-010 cannot read pack.mcmeta of {} (see the warning above); "
                            + "its overlays are not used",
                    id);
            return List.of();
        }
        if (!info.overlays().isEmpty()) {
            LOGGER.info("[Plexus Mod Loader] Pack {} uses overlays {}", id, info.overlays());
        }
        return info.overlays();
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
