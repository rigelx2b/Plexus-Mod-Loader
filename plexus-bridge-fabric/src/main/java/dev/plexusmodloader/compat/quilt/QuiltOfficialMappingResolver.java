package dev.plexusmodloader.compat.quilt;

import dev.plexusmodloader.compat.fabric.intermediary.IntermediaryTranslation;
import dev.plexusmodloader.compat.fabric.intermediary.IntermediaryTranslations;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.quiltmc.loader.api.MappingResolver;

/**
 * {@code QuiltLoader.getMappingResolver()}: the runtime namespace is {@code official} and every name maps to itself,
 * with the meaning of the Fabric bridge's resolver (ADR-0027 decision 4). On Minecraft before 26.1 it also answers for
 * the {@code intermediary} namespace, translating to the Mojang names this game has (ADR-0037). Names must be in dot
 * form, as Quilt Loader requires.
 *
 * <p>Thread: stateless, safe from any thread.
 */
final class QuiltOfficialMappingResolver implements MappingResolver {

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
        return INTERMEDIARY.equals(namespace) ? IntermediaryTranslations.translation() : Optional.empty();
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
