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
    private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";
    private static final String REGISTRY = "Lnet/minecraft/core/Registry;";
    private static final String NEOFORGE_REGISTER_EVENT = "net/neoforged/neoforge/registries/RegisterEvent";
    private static final String NEOFORGE_MOD_BUS_EVENT = "net/neoforged/fml/event/IModBusEvent";
    private static final String FORGE_REGISTER_EVENT = "net/minecraftforge/registries/RegisterEvent";
    private static final String FORGE_REGISTER_EVENT_CONSTRUCTOR =
            "(" + RESOURCE_KEY + "Lnet/minecraftforge/registries/ForgeRegistry;" + REGISTRY + ")V";
    private static final String FORGE_GET_BUS =
            "(Lnet/minecraftforge/eventbus/api/bus/BusGroup;)Lnet/minecraftforge/eventbus/api/bus/EventBus;";
    private static final String FORGE_REGISTER =
            "(" + RESOURCE_KEY + "Lnet/minecraft/resources/Identifier;Ljava/util/function/Supplier;)V";

    private static final String NEOFORGE_RELOAD_EVENT = "net/neoforged/neoforge/event/AddServerReloadListenersEvent";
    private static final String FORGE_RELOAD_EVENT = "net/minecraftforge/event/AddReloadListenerEvent";
    private static final String FORGE_RELOAD_WRAPPER = FORGE_RELOAD_EVENT + "$WrappedStateAwareListener";
    private static final String SERVER_RESOURCES = "Lnet/minecraft/server/ReloadableServerResources;";
    private static final String RELOAD_LISTENER = "Lnet/minecraft/server/packs/resources/PreparableReloadListener;";
    private static final String NEOFORGE_ADD_LISTENER =
            "(Lnet/minecraft/resources/Identifier;" + RELOAD_LISTENER + ")V";
    private static final String FORGE_RELOAD_EVENT_CONSTRUCTOR =
            "(" + SERVER_RESOURCES + "Lnet/minecraft/core/HolderLookup$Provider;)V";

    private static final String NEOFORGE_KEY_MAPPINGS = "net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent";
    private static final String FORGE_KEY_MAPPINGS = "net/minecraftforge/client/event/RegisterKeyMappingsEvent";
    private static final String OPTIONS = "Lnet/minecraft/client/Options;";

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

    /**
     * What registration (F-N4) needs of NeoForge's {@code neoForgeUniversal} and Forge's {@code forgeUniversal} jars and
     * they lack; empty when it holds. NeoForge's {@code RegisterEvent} is a mod bus event with public {@code
     * getRegistryKey()} and {@code getRegistry()}; Forge's {@code RegisterEvent} has the constructor taking the registry
     * key, Forge's registry ({@code null} here) and the registry, the static {@code getBus(BusGroup)}, and {@code
     * register(ResourceKey, Identifier, Supplier)}, which adds to the registry it was given when it has no Forge registry;
     * Forge's {@code RegistryObject} has the one {@code MappedRegistry.isIntrusive()} call the host redirects ({@link
     * ForgeRegistryObjectPatch}); Forge's {@code ForgeRegistries} has the calls of its registry set-up the host redirects
     * ({@link ForgeRegistriesPatch}); and Forge's {@code IForgeRegistry} declares the members the host's view of a game
     * registry answers ({@link ForgeRegistriesPatch#VIEW_METHODS}).
     *
     * @throws IOException when a jar or one of the classes cannot be read
     */
    static List<String> missingRegistration(Path neoForgeUniversal, Path forgeUniversal) throws IOException {
        try (JarFile neoForgeJar = new JarFile(neoForgeUniversal.toFile());
                JarFile forgeJar = new JarFile(forgeUniversal.toFile())) {
            Classes neoForge = new Classes(neoForgeJar);
            Classes forge = new Classes(forgeJar);
            List<String> missing = new ArrayList<>();
            Optional<ClassModel> neoForgeEvent = neoForge.get(NEOFORGE_REGISTER_EVENT);
            if (neoForgeEvent.isEmpty()) {
                missing.add("no " + NEOFORGE_REGISTER_EVENT);
            } else {
                if (!getter(neoForge, NEOFORGE_REGISTER_EVENT, "getRegistryKey").equals(Optional.of(RESOURCE_KEY))) {
                    missing.add(NEOFORGE_REGISTER_EVENT + ": no getRegistryKey()" + RESOURCE_KEY);
                }
                if (!getter(neoForge, NEOFORGE_REGISTER_EVENT, "getRegistry").equals(Optional.of(REGISTRY))) {
                    missing.add(NEOFORGE_REGISTER_EVENT + ": no getRegistry()" + REGISTRY);
                }
                if (!implementsInterface(neoForge, NEOFORGE_REGISTER_EVENT, NEOFORGE_MOD_BUS_EVENT)) {
                    missing.add(NEOFORGE_REGISTER_EVENT + ": not " + NEOFORGE_MOD_BUS_EVENT);
                }
            }
            Optional<ClassModel> forgeEvent = forge.get(FORGE_REGISTER_EVENT);
            if (forgeEvent.isEmpty()) {
                missing.add("no " + FORGE_REGISTER_EVENT);
            } else {
                ClassModel model = forgeEvent.get();
                if (!has(model, "<init>", FORGE_REGISTER_EVENT_CONSTRUCTOR, false)) {
                    missing.add(FORGE_REGISTER_EVENT + ": no <init>" + FORGE_REGISTER_EVENT_CONSTRUCTOR);
                }
                if (!has(model, "getBus", FORGE_GET_BUS, true)) {
                    missing.add(FORGE_REGISTER_EVENT + ": no public static getBus" + FORGE_GET_BUS);
                }
                if (!has(model, "register", FORGE_REGISTER, false)) {
                    missing.add(FORGE_REGISTER_EVENT + ": no register" + FORGE_REGISTER);
                }
            }
            JarEntry registryObject = forgeJar.getJarEntry(ForgeRegistryObjectPatch.CLASS_FILE);
            int calls = 0;
            if (registryObject != null) {
                try (InputStream input = forgeJar.getInputStream(registryObject)) {
                    calls = ForgeRegistryObjectPatch.calls(input.readAllBytes());
                }
            }
            if (calls != 1) {
                missing.add(
                        ForgeRegistryObjectPatch.CLASS_FILE + ": " + calls + " MappedRegistry.isIntrusive() call(s)");
            }
            JarEntry registries = forgeJar.getJarEntry(ForgeRegistriesPatch.CLASS_FILE);
            if (registries == null) {
                missing.add("no " + ForgeRegistriesPatch.CLASS_FILE);
            } else {
                ForgeRegistriesPatch.Calls redirected;
                try (InputStream input = forgeJar.getInputStream(registries)) {
                    redirected = ForgeRegistriesPatch.calls(input.readAllBytes());
                }
                if (!redirected.fit()) {
                    missing.add(ForgeRegistriesPatch.CLASS_FILE + ": " + redirected);
                }
            }
            Optional<ClassModel> forgeRegistry = forge.get(ForgeRegistriesPatch.FORGE_REGISTRY);
            if (forgeRegistry.isEmpty()) {
                missing.add("no " + ForgeRegistriesPatch.FORGE_REGISTRY);
            } else {
                List<String> declared = forgeRegistry.get().methods().stream()
                        .map(method -> method.methodName().stringValue()
                                + method.methodType().stringValue())
                        .toList();
                for (String method : ForgeRegistriesPatch.VIEW_METHODS) {
                    if (!declared.contains(method)) {
                        missing.add(ForgeRegistriesPatch.FORGE_REGISTRY + ": no " + method);
                    }
                }
            }
            return List.copyOf(missing);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * What passing NeoForge's server reload listener event on as Forge's ({@code ForgeReloadListeners} in the host, F-N3)
     * needs of NeoForge's {@code neoForgeUniversal} and Forge's {@code forgeUniversal} jars and they lack; empty when it
     * holds. NeoForge's {@code AddServerReloadListenersEvent} has public {@code getServerResources()} and, in its
     * superclass, {@code addListener(Identifier, PreparableReloadListener)}; Forge's {@code AddReloadListenerEvent} has
     * the public constructor taking the server resources and a registry lookup, {@code getListeners()} and a public
     * static {@code BUS}, and the listener wrapper it adds has {@code wrapped()}. The lookup the host passes comes from
     * the vanilla server resources ({@code fullRegistries().lookup()}), which are not in these jars.
     *
     * @throws IOException when a jar or one of the classes cannot be read
     */
    static List<String> missingReloadListeners(Path neoForgeUniversal, Path forgeUniversal) throws IOException {
        try (JarFile neoForgeJar = new JarFile(neoForgeUniversal.toFile());
                JarFile forgeJar = new JarFile(forgeUniversal.toFile())) {
            Classes neoForge = new Classes(neoForgeJar);
            Classes forge = new Classes(forgeJar);
            List<String> missing = new ArrayList<>();
            if (neoForge.get(NEOFORGE_RELOAD_EVENT).isEmpty()) {
                missing.add("no " + NEOFORGE_RELOAD_EVENT);
            } else {
                if (!getter(neoForge, NEOFORGE_RELOAD_EVENT, "getServerResources")
                        .equals(Optional.of(SERVER_RESOURCES))) {
                    missing.add(NEOFORGE_RELOAD_EVENT + ": no getServerResources()" + SERVER_RESOURCES);
                }
                if (!inherits(neoForge, NEOFORGE_RELOAD_EVENT, "addListener", NEOFORGE_ADD_LISTENER)) {
                    missing.add(NEOFORGE_RELOAD_EVENT + ": no addListener" + NEOFORGE_ADD_LISTENER);
                }
            }
            Optional<ClassModel> forgeEvent = forge.get(FORGE_RELOAD_EVENT);
            if (forgeEvent.isEmpty()) {
                missing.add("no " + FORGE_RELOAD_EVENT);
            } else {
                ClassModel model = forgeEvent.get();
                if (!has(model, "<init>", FORGE_RELOAD_EVENT_CONSTRUCTOR, false)) {
                    missing.add(FORGE_RELOAD_EVENT + ": no <init>" + FORGE_RELOAD_EVENT_CONSTRUCTOR);
                }
                if (!has(model, "getListeners", "()Ljava/util/List;", false)) {
                    missing.add(FORGE_RELOAD_EVENT + ": no getListeners()Ljava/util/List;");
                }
                if (model.fields().stream()
                        .noneMatch(field -> field.fieldName().equalsString("BUS")
                                && field.fieldType().equalsString(EVENT_BUS)
                                && field.flags().has(AccessFlag.PUBLIC)
                                && field.flags().has(AccessFlag.STATIC))) {
                    missing.add(FORGE_RELOAD_EVENT + ": no public static BUS");
                }
            }
            Optional<ClassModel> wrapper = forge.get(FORGE_RELOAD_WRAPPER);
            if (wrapper.isEmpty() || !has(wrapper.get(), "wrapped", "()" + RELOAD_LISTENER, false)) {
                missing.add(FORGE_RELOAD_WRAPPER + ": no wrapped()" + RELOAD_LISTENER);
            }
            return List.copyOf(missing);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * What passing NeoForge's key mapping registration on as Forge's ({@code ForgeKeyMappings} in the host, F-N3) needs
     * of NeoForge's {@code neoForgeUniversal} and Forge's {@code forgeUniversal} jars and they lack; empty when it holds.
     * NeoForge's {@code RegisterKeyMappingsEvent} is a mod bus event with a field {@code options} of the game's options
     * type (it has no getter; the host reads the field); Forge's {@code RegisterKeyMappingsEvent} has the public
     * constructor taking the options and a public static {@code BUS}.
     *
     * @throws IOException when a jar or one of the classes cannot be read
     */
    static List<String> missingKeyMappings(Path neoForgeUniversal, Path forgeUniversal) throws IOException {
        try (JarFile neoForgeJar = new JarFile(neoForgeUniversal.toFile());
                JarFile forgeJar = new JarFile(forgeUniversal.toFile())) {
            Classes neoForge = new Classes(neoForgeJar);
            Classes forge = new Classes(forgeJar);
            List<String> missing = new ArrayList<>();
            Optional<ClassModel> neoForgeEvent = neoForge.get(NEOFORGE_KEY_MAPPINGS);
            if (neoForgeEvent.isEmpty()) {
                missing.add("no " + NEOFORGE_KEY_MAPPINGS);
            } else {
                if (neoForgeEvent.get().fields().stream()
                        .noneMatch(field -> field.fieldName().equalsString("options")
                                && field.fieldType().equalsString(OPTIONS)
                                && !field.flags().has(AccessFlag.STATIC))) {
                    missing.add(NEOFORGE_KEY_MAPPINGS + ": no field options " + OPTIONS);
                }
                if (!implementsInterface(neoForge, NEOFORGE_KEY_MAPPINGS, NEOFORGE_MOD_BUS_EVENT)) {
                    missing.add(NEOFORGE_KEY_MAPPINGS + ": not " + NEOFORGE_MOD_BUS_EVENT);
                }
            }
            Optional<ClassModel> forgeEvent = forge.get(FORGE_KEY_MAPPINGS);
            if (forgeEvent.isEmpty()) {
                missing.add("no " + FORGE_KEY_MAPPINGS);
            } else {
                ClassModel model = forgeEvent.get();
                String constructor = "(" + OPTIONS + ")V";
                if (model.methods().stream()
                        .noneMatch(method -> method.methodName().equalsString("<init>")
                                && method.methodType().equalsString(constructor)
                                && method.flags().has(AccessFlag.PUBLIC))) {
                    missing.add(FORGE_KEY_MAPPINGS + ": no public <init>" + constructor);
                }
                if (model.fields().stream()
                        .noneMatch(field -> field.fieldName().equalsString("BUS")
                                && field.fieldType().equalsString(EVENT_BUS)
                                && field.flags().has(AccessFlag.PUBLIC)
                                && field.flags().has(AccessFlag.STATIC))) {
                    missing.add(FORGE_KEY_MAPPINGS + ": no public static BUS");
                }
            }
            return List.copyOf(missing);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Whether {@code owner} or a superclass in the jar declares the public method {@code name}{@code descriptor}. */
    private static boolean inherits(Classes classes, String owner, String name, String descriptor) throws IOException {
        Optional<ClassModel> model = classes.get(owner);
        while (model.isPresent()) {
            if (model.get().methods().stream()
                    .anyMatch(method -> method.methodName().equalsString(name)
                            && method.methodType().equalsString(descriptor)
                            && method.flags().has(AccessFlag.PUBLIC))) {
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

    /** Whether {@code model} declares the method {@code name}{@code descriptor}, public and static when asked. */
    private static boolean has(ClassModel model, String name, String descriptor, boolean publicStatic) {
        return model.methods().stream()
                .anyMatch(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor)
                        && (!publicStatic
                                || (method.flags().has(AccessFlag.PUBLIC)
                                        && method.flags().has(AccessFlag.STATIC))));
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

    /**
     * The return type of the public no-argument method {@code name} of {@code owner} or a superclass in the jar. Bridge
     * methods are skipped: NeoForge's player events declare {@code getEntity()} bridges returning {@code LivingEntity}
     * and {@code Entity}, while {@code getEntity()} returning the player is declared in their superclass, and
     * reflection ({@code Class.getMethod}) picks that one, the most specific return type.
     */
    private static Optional<String> getter(Classes classes, String owner, String name) throws IOException {
        Optional<ClassModel> model = classes.get(owner);
        while (model.isPresent()) {
            for (MethodModel method : model.get().methods()) {
                String type = method.methodType().stringValue();
                if (method.methodName().equalsString(name)
                        && type.startsWith("()")
                        && method.flags().has(AccessFlag.PUBLIC)
                        && !method.flags().has(AccessFlag.STATIC)
                        && !method.flags().has(AccessFlag.BRIDGE)) {
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
