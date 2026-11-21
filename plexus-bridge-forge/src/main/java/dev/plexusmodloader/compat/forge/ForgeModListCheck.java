package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge on NeoForge (ADR-0051): whether Forge's {@code ModList} and {@code ModLoadingContext} can be set up as the host
 * sets them ({@code ForgeModList} in the host). Forge's {@code ModList} reads Forge's loading mod list when it is
 * initialised, which Forge's launch fills and which does not run on NeoForge; the host gives it an empty one ({@code
 * LoadingModListImpl.temp}, an empty {@code ModSorter.State}) and hands it the Forge mods' own containers ({@code
 * setLoadedMods}). The members Forge mods may use ({@link #ANSWERED}) must read only what {@code setLoadedMods} sets.
 * Forge's {@code ModLoadingContext} must let the host set the container a mod's code runs for ({@code
 * setActiveContainer}). When any of it differs (another Forge release), every Forge mod is left out ({@code
 * PLEXUS-COMPAT-229}).
 *
 * <p>Thread: stateless.
 */
final class ForgeModListCheck {

    static final String MOD_LIST = "net/minecraftforge/fml/ModList";
    static final String MOD_LOADING_CONTEXT = "net/minecraftforge/fml/ModLoadingContext";
    static final String LOADING_MOD_LIST_IMPL = "net/minecraftforge/fml/loading/LoadingModListImpl";
    static final String SORTER_STATE = "net/minecraftforge/fml/loading/ModSorter$State";

    /** The members of Forge's {@code ModList} that Forge mods may use ({@code ForgeOnNeoForgeScope}). */
    static final List<String> ANSWERED = List.of(
            "isLoaded",
            "getModContainerById",
            "getModObjectById",
            "getModContainerByObject",
            "getLoadedMods",
            "forEachModContainer",
            "forEachModInOrder",
            "applyForEachModContainer",
            "size");

    /** The fields of Forge's {@code ModList} that {@code setLoadedMods} sets. */
    static final Set<String> SET_BY_LOADED_MODS = Set.of("mods", "indexedMods", "sortedContainers");

    private static final String LOADING_MOD_LIST = "net/minecraftforge/fml/loading/LoadingModList";
    private static final String MOD_CONTAINER = "Lnet/minecraftforge/fml/ModContainer;";

    private ForgeModListCheck() {}

    /**
     * What of the above Forge's {@code fmlcore} jar and {@code fmlloader} jar do not have; empty when the host can set
     * up Forge's {@code ModList} and {@code ModLoadingContext}.
     *
     * @throws IOException when a jar or one of its classes cannot be read
     */
    static List<String> missing(Path fmlcore, Path fmlloader) throws IOException {
        List<String> missing = new ArrayList<>();
        try (JarFile core = new JarFile(fmlcore.toFile());
                JarFile loader = new JarFile(fmlloader.toFile())) {
            Optional<ClassModel> modList = read(core, MOD_LIST);
            if (modList.isEmpty()) {
                missing.add("missing class " + MOD_LIST);
            } else {
                if (method(modList.get(), "setLoadedMods", "(Ljava/util/List;)V", Set.of(AccessFlag.STATIC))
                        .isEmpty()) {
                    missing.add(MOD_LIST + ": missing static method setLoadedMods(Ljava/util/List;)V");
                }
                for (String name : ANSWERED) {
                    Optional<MethodModel> method = modList.get().methods().stream()
                            .filter(candidate -> candidate.methodName().equalsString(name)
                                    && candidate
                                            .flags()
                                            .flags()
                                            .containsAll(Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC)))
                            .findFirst();
                    if (method.isEmpty()) {
                        missing.add(MOD_LIST + ": missing public static method " + name);
                    } else {
                        for (String read : unanswered(method.get())) {
                            missing.add(MOD_LIST + ": " + name + "() " + read);
                        }
                    }
                }
            }
            Optional<ClassModel> context = read(core, MOD_LOADING_CONTEXT);
            if (context.isEmpty()) {
                missing.add("missing class " + MOD_LOADING_CONTEXT);
            } else {
                String self = "()L" + MOD_LOADING_CONTEXT + ";";
                if (method(context.get(), "get", self, Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC))
                        .isEmpty()) {
                    missing.add(MOD_LOADING_CONTEXT + ": missing public static method get" + self);
                }
                Optional<MethodModel> setActive =
                        method(context.get(), "setActiveContainer", "(" + MOD_CONTAINER + ")V", Set.of());
                if (setActive.isEmpty() || setActive.get().flags().has(AccessFlag.STATIC)) {
                    missing.add(MOD_LOADING_CONTEXT + ": missing instance method setActiveContainer(" + MOD_CONTAINER
                            + ")V");
                }
            }
            Optional<ClassModel> impl = read(loader, LOADING_MOD_LIST_IMPL);
            if (impl.isEmpty()) {
                missing.add("missing class " + LOADING_MOD_LIST_IMPL);
            } else if (!privateStaticField(impl.get(), "temp", "L" + SORTER_STATE + ";")) {
                missing.add(
                        LOADING_MOD_LIST_IMPL + ": missing private static non-final field temp L" + SORTER_STATE + ";");
            }
            Optional<ClassModel> state = read(loader, SORTER_STATE);
            if (state.isEmpty()) {
                missing.add("missing class " + SORTER_STATE);
            } else if (method(state.get(), "<init>", "(Ljava/util/List;Ljava/util/List;)V", Set.of())
                    .isEmpty()) {
                missing.add(SORTER_STATE + ": missing constructor (Ljava/util/List;Ljava/util/List;)V");
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot parse class file in Forge's fmlcore or fmlloader jar: " + e, e);
        }
        return List.copyOf(missing);
    }

    /**
     * What {@code method} of Forge's {@code ModList} reads that {@code setLoadedMods} does not set: another of its
     * fields, Forge's loading mod list, or a member of {@code ModList} outside {@link #ANSWERED}.
     */
    private static Set<String> unanswered(MethodModel method) {
        Set<String> found = new LinkedHashSet<>();
        method.code().ifPresent(code -> {
            for (CodeElement element : code) {
                if (element instanceof FieldInstruction field
                        && field.owner().asInternalName().equals(MOD_LIST)
                        && (field.opcode() == Opcode.GETSTATIC || field.opcode() == Opcode.PUTSTATIC)
                        && !SET_BY_LOADED_MODS.contains(field.name().stringValue())) {
                    found.add("reads static field " + field.name().stringValue() + ", which is not set");
                } else if (element instanceof InvokeInstruction invoke) {
                    String owner = invoke.owner().asInternalName();
                    String name = invoke.name().stringValue();
                    if (owner.equals(LOADING_MOD_LIST)
                            || owner.equals(LOADING_MOD_LIST_IMPL)
                            || (owner.equals(MOD_LIST) && !ANSWERED.contains(name))) {
                        found.add("calls " + owner + "." + name + ", which is not set up");
                    }
                }
            }
        });
        return found;
    }

    private static Optional<ClassModel> read(JarFile jar, String internalName) throws IOException {
        JarEntry entry = jar.getJarEntry(internalName + ".class");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = jar.getInputStream(entry)) {
            return Optional.of(ClassFile.of().parse(input.readAllBytes()));
        }
    }

    private static Optional<MethodModel> method(
            ClassModel model, String name, String descriptor, Set<AccessFlag> flags) {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor)
                        && method.flags().flags().containsAll(flags))
                .findFirst();
    }

    private static boolean privateStaticField(ClassModel model, String name, String descriptor) {
        for (FieldModel field : model.fields()) {
            if (field.fieldName().equalsString(name)
                    && field.fieldType().equalsString(descriptor)
                    && field.flags().has(AccessFlag.PRIVATE)
                    && field.flags().has(AccessFlag.STATIC)
                    && !field.flags().has(AccessFlag.FINAL)) {
                return true;
            }
        }
        return false;
    }
}
