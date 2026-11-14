package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge on NeoForge, F-N3 (ADR-0051): whether every pair of {@link ForgeEventPairs#GAME_EVENTS} holds for one NeoForge and
 * one Forge release, read from their {@code universal} jars before the game starts. A pair holds when NeoForge's event
 * class has each getter the pair names (in the class or a superclass in the same jar), Forge's event class has a public
 * constructor taking those values in order and a public static {@code BUS}, and, when that bus is cancellable, NeoForge's
 * event can be cancelled too. The NeoForge host declines the Forge mods when a pair does not hold, rather than letting
 * FML stop the game when the listeners are added.
 *
 * <p>Thread: stateless.
 */
final class ForgeEventPairCheck {

    private static final String EVENT_BUS = "Lnet/minecraftforge/eventbus/api/bus/EventBus;";
    private static final String CANCELLABLE_EVENT_BUS = "Lnet/minecraftforge/eventbus/api/bus/CancellableEventBus;";
    private static final String CANCELLABLE_EVENT = "net/neoforged/bus/api/ICancellableEvent";
    private static final String BOOLEAN_SUPPLIER = "Ljava/util/function/BooleanSupplier;";
    private static final String LOGICAL_SIDE = "Lnet/minecraftforge/fml/LogicalSide;";

    private ForgeEventPairCheck() {}

    /**
     * The pairs of {@code pairs} that do not hold for NeoForge's {@code neoForgeUniversal} and Forge's {@code
     * forgeUniversal} jars, each as {@code neoForge -> forge: what is missing}; empty when all hold.
     *
     * @throws IOException when a jar or one of the classes cannot be read
     */
    static List<String> missing(List<ForgeEventPairs.Pair> pairs, Path neoForgeUniversal, Path forgeUniversal)
            throws IOException {
        try (JarFile neoForgeJar = new JarFile(neoForgeUniversal.toFile());
                JarFile forgeJar = new JarFile(forgeUniversal.toFile())) {
            Classes neoForge = new Classes(neoForgeJar);
            Classes forge = new Classes(forgeJar);
            List<String> missing = new ArrayList<>();
            for (ForgeEventPairs.Pair pair : pairs) {
                List<String> reasons = check(pair, neoForge, forge);
                if (!reasons.isEmpty()) {
                    missing.add(pair.neoForge() + " -> " + pair.forge() + ": " + String.join(", ", reasons));
                }
            }
            return List.copyOf(missing);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static List<String> check(ForgeEventPairs.Pair pair, Classes neoForge, Classes forge) throws IOException {
        String neoForgeName = internal(pair.neoForge());
        String forgeName = internal(pair.forge());
        Optional<ClassModel> neoForgeEvent = neoForge.get(neoForgeName);
        Optional<ClassModel> forgeEvent = forge.get(forgeName);
        List<String> reasons = new ArrayList<>();
        if (neoForgeEvent.isEmpty()) {
            reasons.add("no " + neoForgeName);
        }
        if (forgeEvent.isEmpty()) {
            reasons.add("no " + forgeName);
        }
        if (!reasons.isEmpty()) {
            return reasons;
        }
        StringBuilder descriptor = new StringBuilder("(");
        for (ForgeEventPairs.Argument argument : pair.arguments()) {
            switch (argument) {
                case ForgeEventPairs.Getter getter -> {
                    Optional<String> type = getter(neoForge, neoForgeName, getter.method());
                    type.ifPresentOrElse(descriptor::append, () -> reasons.add("no " + getter.method() + "()"));
                }
                case ForgeEventPairs.TimeLeft timeLeft -> {
                    Optional<String> type = getter(neoForge, neoForgeName, timeLeft.method());
                    if (type.isEmpty() || !type.get().equals("Z")) {
                        reasons.add("no " + timeLeft.method() + "()Z");
                    }
                    descriptor.append(BOOLEAN_SUPPLIER);
                }
                case ForgeEventPairs.Side side -> descriptor.append(LOGICAL_SIDE);
            }
        }
        String constructor = descriptor.append(")V").toString();
        ClassModel forgeModel = forgeEvent.get();
        boolean hasConstructor = forgeModel.methods().stream()
                .anyMatch(method -> method.methodName().equalsString("<init>")
                        && method.methodType().equalsString(constructor)
                        && method.flags().has(AccessFlag.PUBLIC));
        if (reasons.isEmpty() && !hasConstructor) {
            reasons.add("no public <init>" + constructor);
        }
        Optional<FieldModel> bus = forgeModel.fields().stream()
                .filter(field -> field.fieldName().equalsString("BUS")
                        && field.flags().has(AccessFlag.PUBLIC)
                        && field.flags().has(AccessFlag.STATIC))
                .findFirst();
        if (bus.isEmpty()
                || !(bus.get().fieldType().equalsString(EVENT_BUS)
                        || bus.get().fieldType().equalsString(CANCELLABLE_EVENT_BUS))) {
            reasons.add("no public static BUS");
        } else if (bus.get().fieldType().equalsString(CANCELLABLE_EVENT_BUS)
                && !implementsInterface(neoForge, neoForgeName, CANCELLABLE_EVENT)) {
            reasons.add("cancellable, but " + neoForgeName + " is not " + CANCELLABLE_EVENT);
        }
        return reasons;
    }

    /** The return type of the public no-argument method {@code name} of {@code owner} or a superclass in the jar. */
    private static Optional<String> getter(Classes classes, String owner, String name) throws IOException {
        Optional<ClassModel> model = classes.get(owner);
        while (model.isPresent()) {
            for (MethodModel method : model.get().methods()) {
                String type = method.methodType().stringValue();
                if (method.methodName().equalsString(name)
                        && type.startsWith("()")
                        && method.flags().has(AccessFlag.PUBLIC)
                        && !method.flags().has(AccessFlag.STATIC)) {
                    return Optional.of(type.substring(2));
                }
            }
            Optional<ClassEntry> superclass = model.get().superclass();
            if (superclass.isEmpty()) {
                break;
            }
            model = classes.get(superclass.get().asInternalName());
        }
        return Optional.empty();
    }

    private static boolean implementsInterface(Classes classes, String owner, String face) throws IOException {
        Optional<ClassModel> model = classes.get(owner);
        while (model.isPresent()) {
            if (model.get().interfaces().stream()
                    .anyMatch(entry -> entry.asInternalName().equals(face))) {
                return true;
            }
            Optional<ClassEntry> superclass = model.get().superclass();
            if (superclass.isEmpty()) {
                break;
            }
            model = classes.get(superclass.get().asInternalName());
        }
        return false;
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }

    /** The classes of one jar, parsed when first asked for. */
    private static final class Classes {
        private final JarFile jar;
        private final Map<String, Optional<ClassModel>> parsed = new HashMap<>();

        Classes(JarFile jar) {
            this.jar = jar;
        }

        Optional<ClassModel> get(String internalName) throws IOException {
            Optional<ClassModel> known = parsed.get(internalName);
            if (known != null) {
                return known;
            }
            JarEntry entry = jar.getJarEntry(internalName + ".class");
            Optional<ClassModel> model = Optional.empty();
            if (entry != null) {
                try (InputStream input = jar.getInputStream(entry)) {
                    model = Optional.of(ClassFile.of().parse(input.readAllBytes()));
                }
            }
            parsed.put(internalName, model);
            return model;
        }
    }
}
