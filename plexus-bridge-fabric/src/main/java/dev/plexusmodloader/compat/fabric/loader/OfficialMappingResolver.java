package dev.plexusmodloader.compat.fabric.loader;

import dev.plexusmodloader.compat.fabric.intermediary.IntermediaryTranslation;
import dev.plexusmodloader.compat.fabric.intermediary.IntermediaryTranslations;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.MappingResolver;

/**
 * {@code FabricLoader.getMappingResolver()}. The runtime namespace is {@code official} and, as under Fabric Loader 0.19.5
 * without a mappings file, every name maps to itself. Names must be in dot form, as Fabric Loader requires.
 *
 * <p>On Minecraft 26.x, which is not obfuscated, that is all. On Minecraft before 26.1 the game here has Mojang's names
 * (ADR-0019) while Fabric mods know it by intermediary names, so the resolver also answers for the {@code intermediary}
 * namespace (ADR-0037): {@code mapClassName("intermediary", "net.minecraft.class_310")} gives the name this game has,
 * as a mod expects from Fabric Loader, and the other methods translate members the same way. The translation exists
 * only while the mappings are on this machine; without them the resolver answers as on 26.x.
 *
 * <p>Fabric Loader's resolver over its empty mapping tree reports a namespace set holding only {@code null}; this one
 * reports {@code official}, the namespace the game runs in.
 *
 * <p>Thread: stateless, safe from any thread.
 */
final class OfficialMappingResolver implements MappingResolver {

    static final String OFFICIAL = "official";
    static final String INTERMEDIARY = "intermediary";

    @Override
    public Collection<String> getNamespaces() {
        return IntermediaryTranslations.available() ? Set.of(OFFICIAL, INTERMEDIARY) : Set.of(OFFICIAL);
    }

    @Override
    public String getCurrentRuntimeNamespace() {
        return OFFICIAL;
    }

    @Override
    public String mapClassName(String namespace, String className) {
        dotted(className);
        Optional<IntermediaryTranslation> translation = intermediary(namespace);
        return translation.isEmpty()
                ? className
                : translation.get().mapClass(internal(className)).replace('/', '.');
    }

    @Override
    public String unmapClassName(String targetNamespace, String className) {
        dotted(className);
        Optional<IntermediaryTranslation> translation = intermediary(targetNamespace);
        return translation.isEmpty()
                ? className
                : translation.get().unmapClass(internal(className)).replace('/', '.');
    }

    @Override
    public String mapFieldName(String namespace, String owner, String name, String descriptor) {
        dotted(owner);
        Optional<IntermediaryTranslation> translation = intermediary(namespace);
        return translation.isEmpty() ? name : translation.get().mapField(internal(owner), name, descriptor);
    }

    @Override
    public String mapMethodName(String namespace, String owner, String name, String descriptor) {
        dotted(owner);
        Optional<IntermediaryTranslation> translation = intermediary(namespace);
        return translation.isEmpty() ? name : translation.get().mapMethod(internal(owner), name, descriptor);
    }

    private static Optional<IntermediaryTranslation> intermediary(String namespace) {
        return INTERMEDIARY.equals(namespace) ? translation() : Optional.empty();
    }

    private static Optional<IntermediaryTranslation> translation() {
        return IntermediaryTranslations.translation();
    }

    private static String internal(String className) {
        return className.replace('.', '/');
    }

    private static void dotted(String className) {
        if (className.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Class names must be provided in dot format: " + className);
        }
    }
}
