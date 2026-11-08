package dev.plexusmodloader.compat.forge.modlauncher;

import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerActivity;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import dev.plexusmodloader.compat.bridge.BridgeClassTransformer;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * ModLauncher's class transformation, as Plexus Mod Loader's game class loader stage (ADR-0021). For each class, in the
 * order ModLauncher documents for its services:
 *
 * <ol>
 *   <li>launch plugins that handle the class in the {@code BEFORE} phase ({@link ILaunchPluginService#handlesClass});
 *   <li>the transformation services' {@link ITransformer}s, by vote: pre-class transformers, then each field's and
 *       each method's, then class transformers;
 *   <li>launch plugins in the {@code AFTER} phase.
 * </ol>
 *
 * <p>A class nothing touches keeps its bytes. Written for Plexus Mod Loader from ModLauncher's public API; not ModLauncher's
 * code.
 *
 * <p>Thread: the game class loader calls it from any thread; the transformer registry is fixed before the first call.
 */
final class ForgeClassTransformer implements BridgeClassTransformer {

    private static final String FIELD_TO_METHOD_TRANSFORMER =
            "net.minecraftforge.forge.transformers.FieldToMethodTransformer";

    /** The class of a coremod script's class transformer in Forge's coremods 5 (Forge 47 and 49). */
    private static final String COREMOD_CLASS_TRANSFORMER =
            "net.minecraftforge.coremod.transformer.CoreModClassTransformer";

    /**
     * Functions of Forge's {@code coremods/field_to_method.js} (47.4.28, 49.2.13) calling {@code
     * ASMAPI.redirectFieldToMethod}, as labels() names them with owner {@code forge}.
     */
    private static final Set<String> FIELD_TO_METHOD_COREMOD_FUNCTIONS = Set.of(
            "biome",
            "structure",
            "potion",
            "flowing_fluid_block",
            "bucketitem",
            "stairsblock",
            "flowerpotblock",
            "itemstack");

    /**
     * NeoForge 21.1's coremod with the same job ({@code ReplaceFieldWithGetterAccess}, NeoForge 21.1.265; ADR-0043 point
     * 10): it refuses the same way ({@code Field climateSettings in net/minecraft/world/level/biome/Biome is not private
     * and an instance field}).
     */
    private static final String NEOFORGE_FIELD_TO_METHOD_TRANSFORMER =
            "net.neoforged.neoforge.coremods.ReplaceFieldWithGetterAccess";

    /** The class of a coremod script's class transformer in NeoForge's coremods 6 (NeoForge 20.4). */
    private static final String NEOFORGE_COREMOD_CLASS_TRANSFORMER =
            "net.neoforged.coremod.transformer.CoreModClassTransformer";

    /** Forge's launch plugin that adds enum constants at run time (by an enum's {@code create} methods). */
    private static final String RUNTIME_ENUM_EXTENDER = "runtime_enum_extender";
    /** The flags javac gives an enum's {@code $VALUES} field, which Forge's enum extender looks it up by. */
    private static final int ENUM_VALUES_ACCESS =
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    /** The launch plugins now; a mod may replace the map they come from (ModernFix adds its own). */
    private final Supplier<Collection<ILaunchPluginService>> plugins;

    private final GameClassBytes classes;
    private final Map<String, List<ITransformer<?>>> transformersByTarget = new HashMap<>();
    private final Set<String> classesWithTransformers = new HashSet<>();
    /** How deep this thread is in {@link #transform(String, byte[], boolean, String)}. */
    private final ThreadLocal<Integer> processing = ThreadLocal.withInitial(() -> 0);

    ForgeClassTransformer(Supplier<Collection<ILaunchPluginService>> plugins, GameClassBytes classes) {
        this.plugins = plugins;
        this.classes = classes;
    }

    /** Registers a service's transformers under each of their targets; called before the first class is loaded. */
    void addTransformers(ITransformationService service) {
        for (ITransformer<?> transformer : service.transformers()) {
            for (ITransformer.Target target : transformer.targets()) {
                String internalName = ((String) read(target, "className")).replace('.', '/');
                classesWithTransformers.add(internalName);
                transformersByTarget
                        .computeIfAbsent(
                                key(
                                        internalName,
                                        typeName(read(target, "targetType")),
                                        (String) read(target, "elementName"),
                                        (String) read(target, "elementDescriptor")),
                                k -> new ArrayList<>())
                        .add(transformer);
            }
        }
    }

    /**
     * A member of {@code ITransformer.Target}: ModLauncher 10.2 (Forge 26.x, 1.21.x) made it a record with accessors such
     * as {@code className()}; ModLauncher 10.1 (Forge 49, Minecraft 1.20.4) has a class with {@code getClassName()}.
     */
    private static Object read(ITransformer.Target target, String member) {
        String getter = "get" + Character.toUpperCase(member.charAt(0)) + member.substring(1);
        for (String name : new String[] {member, getter}) {
            try {
                return target.getClass().getMethod(name).invoke(target);
            } catch (NoSuchMethodException e) {
                // the other form
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read " + member + " of " + target, e);
            }
        }
        throw new IllegalStateException("ITransformer.Target has neither " + member + "() nor " + getter + "()");
    }

    /**
     * The name of a target type ({@code PRE_CLASS}, {@code CLASS}, {@code METHOD}, {@code FIELD}): ModLauncher 10 has an enum
     * {@code ITransformer.TargetType}, ModLauncher 11 (NeoForge's FML 4, ADR-0043) the class {@code TargetType} with a
     * constant of each name.
     */
    private static String typeName(Object type) {
        if (type instanceof Enum<?> constant) {
            return constant.name();
        }
        for (java.lang.reflect.Field field : type.getClass().getFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.get(null) == type) {
                    return field.getName();
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read " + field, e);
            }
        }
        throw new IllegalStateException("Unknown ModLauncher target type " + type);
    }

    @Override
    public String name() {
        return "ModLauncher services (Plexus Mod Loader)";
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        byte[] result = transform(className, bytes, false, ITransformerActivity.CLASSLOADING_REASON);
        return result == null ? bytes : result;
    }

    /**
     * The class as launch plugin {@code reason} reads it, the way ModLauncher's class loader builds it for the loader a
     * plugin gets in {@code initializeLaunch}: every other plugin and transformer applied, the plugin itself skipping
     * classes offered under its own name. Mixin builds its class metadata from this, so it sees the access an access
     * transformer gave a member (NeoForge 21.1: a private {@code LevelRenderer.renderHitOutline} made public, and Mixin
     * looked for a private method in its metadata).
     */
    byte[] transformFor(String reason, String className, byte[] bytes) {
        byte[] result = transform(className, bytes, false, reason);
        return result == null ? bytes : result;
    }

    /**
     * A class no jar holds, created by a launch plugin that handles empty classes (Mixin), or {@code null}. Not while a
     * plugin is processing a class on this thread: a missing class it asks for comes from its own code, which ModLauncher
     * loads in the BOOT layer, where nothing is generated (Mixin probes for classes while it creates its transformer).
     */
    @Override
    public byte[] generate(String className) {
        if (processing.get() > 0) {
            return null;
        }
        return transform(className, new byte[0], true, ITransformerActivity.CLASSLOADING_REASON);
    }

    /** The transformed class, or {@code null} when nothing changed it. */
    private byte[] transform(String className, byte[] input, boolean empty, String reason) {
        processing.set(processing.get() + 1);
        try {
            return transformOnce(className, input, empty, reason);
        } finally {
            processing.set(processing.get() - 1);
        }
    }

    private byte[] transformOnce(String className, byte[] input, boolean empty, String reason) {
        String internalName = className.replace('.', '/');
        Type type = Type.getObjectType(internalName);
        EnumMap<ILaunchPluginService.Phase, List<ILaunchPluginService>> phases =
                new EnumMap<>(ILaunchPluginService.Phase.class);
        for (ILaunchPluginService plugin : plugins.get()) {
            for (ILaunchPluginService.Phase phase : plugin.handlesClass(type, empty, reason)) {
                phases.computeIfAbsent(phase, p -> new ArrayList<>()).add(plugin);
            }
        }
        boolean hasTransformers = !empty && classesWithTransformers.contains(internalName);
        if (phases.isEmpty() && !hasTransformers) {
            return null;
        }
        ClassNode node = new ClassNode(Opcodes.ASM9);
        if (empty) {
            node.name = internalName;
            node.version = Opcodes.V1_8;
            node.superName = "java/lang/Object";
        } else {
            new ClassReader(input).accept(node, 0);
        }
        int before = offer(ILaunchPluginService.Phase.BEFORE, phases, node, type, reason);
        List<ILaunchPluginService> after = phases.getOrDefault(ILaunchPluginService.Phase.AFTER, List.of());
        if (before == ILaunchPluginService.ComputeFlags.NO_REWRITE && !hasTransformers && after.isEmpty()) {
            return null;
        }
        if (hasTransformers) {
            node = vote(internalName, input, node);
        }
        int afterFlags = offer(ILaunchPluginService.Phase.AFTER, phases, node, type, reason);
        if (before == ILaunchPluginService.ComputeFlags.NO_REWRITE
                && afterFlags == ILaunchPluginService.ComputeFlags.NO_REWRITE
                && !hasTransformers) {
            return null;
        }
        int flags = hasTransformers ? ClassWriter.COMPUTE_FRAMES : (before | afterFlags);
        ClassWriter writer = new HierarchyClassWriter(flags & (ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
        node.accept(writer);
        return writer.toByteArray();
    }

    private static int offer(
            ILaunchPluginService.Phase phase,
            Map<ILaunchPluginService.Phase, List<ILaunchPluginService>> phases,
            ClassNode node,
            Type type,
            String reason) {
        int flags = ILaunchPluginService.ComputeFlags.NO_REWRITE;
        for (ILaunchPluginService plugin : phases.getOrDefault(phase, List.of())) {
            flags |= RUNTIME_ENUM_EXTENDER.equals(plugin.name())
                    ? withEnumValuesAsCompiled(node, () -> plugin.processClassWithFlags(phase, node, type, reason))
                    : plugin.processClassWithFlags(phase, node, type, reason);
        }
        return flags;
    }

    /**
     * Runs {@code process} with an enum's {@code $VALUES} field {@code private static final synthetic}, as the compiler
     * wrote it, then puts back the visibility another loader's mod widened it to. Forge's {@code RuntimeEnumExtender}
     * finds the field by exactly those flags, and an access widener of a Fabric or Quilt mod makes it public or mutable
     * (Forge 1.20.1 {@code EnchantmentCategory}: "Enum has create method but we could not find $VALUES").
     */
    static int withEnumValuesAsCompiled(ClassNode node, java.util.function.IntSupplier process) {
        Map<FieldNode, Integer> widened = new HashMap<>();
        String descriptor = "[L" + node.name + ";";
        for (FieldNode field : node.fields) {
            if (field.name.equals("$VALUES")
                    && field.desc.equals(descriptor)
                    && (field.access & ENUM_VALUES_ACCESS) != ENUM_VALUES_ACCESS
                    && (field.access & Opcodes.ACC_STATIC) != 0) {
                widened.put(field, field.access);
                field.access = (field.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | ENUM_VALUES_ACCESS;
            }
        }
        try {
            return process.getAsInt();
        } finally {
            // Forge may have taken final away; the widened visibility, and the widener's own removal of final, stay.
            widened.forEach((field, access) -> field.access = (field.access
                            & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL))
                    | (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE))
                    | (access & field.access & Opcodes.ACC_FINAL));
        }
    }

    private ClassNode vote(String internalName, byte[] input, ClassNode node) {
        VotingContext context = new VotingContext(internalName.replace('/', '.'), input);
        ClassNode result = vote(transformersFor(internalName, "PRE_CLASS", "", ""), node, context);
        List<FieldNode> fields = new ArrayList<>(result.fields.size());
        for (FieldNode field : result.fields) {
            fields.add(vote(transformersFor(internalName, "FIELD", field.name, ""), field, context));
        }
        List<MethodNode> methods = new ArrayList<>(result.methods.size());
        for (MethodNode method : result.methods) {
            methods.add(vote(transformersFor(internalName, "METHOD", method.name, method.desc), method, context));
        }
        result.fields = fields;
        result.methods = methods;
        return vote(transformersFor(internalName, "CLASS", "", ""), result, context);
    }

    @SuppressWarnings("unchecked")
    private <T> List<ITransformer<T>> transformersFor(
            String internalName, String targetType, String name, String descriptor) {
        List<ITransformer<T>> found = new ArrayList<>();
        for (ITransformer<?> transformer :
                transformersByTarget.getOrDefault(key(internalName, targetType, name, descriptor), List.of())) {
            found.add((ITransformer<T>) transformer);
        }
        return found;
    }

    /**
     * ModLauncher's vote: every remaining transformer votes; a {@code REJECT} stops the class, {@code NO} drops the
     * transformer, the first {@code YES} transforms and leaves; {@code DEFER} with nothing else left is a deadlock.
     */
    private static <T> T vote(List<ITransformer<T>> transformers, T node, VotingContext context) {
        T current = node;
        context.node = current;
        while (!transformers.isEmpty()) {
            EnumMap<TransformerVoteResult, List<ITransformer<T>>> votes = new EnumMap<>(TransformerVoteResult.class);
            for (ITransformer<T> transformer : transformers) {
                votes.computeIfAbsent(transformer.castVote(context), v -> new ArrayList<>())
                        .add(transformer);
            }
            if (votes.containsKey(TransformerVoteResult.REJECT)) {
                throw new IllegalStateException("A ModLauncher transformer rejected " + context.getClassName() + ": "
                        + votes.get(TransformerVoteResult.REJECT));
            }
            transformers.removeAll(votes.getOrDefault(TransformerVoteResult.NO, List.of()));
            List<ITransformer<T>> yes = votes.get(TransformerVoteResult.YES);
            if (yes != null) {
                ITransformer<T> transformer = yes.get(0);
                current = transformWithPrivateFields(transformer, current, context);
                context.node = current;
                transformers.remove(transformer);
                continue;
            }
            if (votes.containsKey(TransformerVoteResult.DEFER)) {
                throw new IllegalStateException("ModLauncher transformers deadlocked on " + context.getClassName()
                        + ": " + votes.get(TransformerVoteResult.DEFER));
            }
        }
        return current;
    }

    /**
     * Runs {@code transformer}; Forge's {@code FieldToMethodTransformer} sees the class's instance fields private, as
     * in the game Forge patched. That transformer redirects reads of a private field to a getter (Forge's biome
     * modifiers: {@code Biome.climateSettings}) and refuses a field that is not private, but another loader's mod may
     * have widened it (Fabric API's biome class tweaker, ADR-0021 G8). The redirect applies as on the unwidened class,
     * and the field keeps the access the widening gave it afterwards. {@link #redirectsFieldsToMethods} covers Forge
     * 47/49's {@code field_to_method.js} ({@code ASMAPI.redirectFieldToMethod}), avoiding {@code Field climateSettings
     * is not private and an instance field}.
     */
    private static <T> T transformWithPrivateFields(ITransformer<T> transformer, T node, VotingContext context) {
        if (!(node instanceof ClassNode classNode) || !redirectsFieldsToMethods(transformer)) {
            return transformer.transform(node, context);
        }
        Map<FieldNode, Integer> widened = new HashMap<>();
        for (FieldNode field : classNode.fields) {
            if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                widened.put(field, field.access);
                field.access = (field.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
            }
        }
        try {
            return transformer.transform(node, context);
        } finally {
            widened.forEach((field, access) -> field.access = access);
        }
    }

    /**
     * Whether {@code transformer} redirects private field reads to getters: Forge's {@code FieldToMethodTransformer} or
     * coremod {@code field_to_method.js} ({@link #FIELD_TO_METHOD_COREMOD_FUNCTIONS}).
     */
    static boolean redirectsFieldsToMethods(ITransformer<?> transformer) {
        String type = transformer.getClass().getName();
        if (type.equals(FIELD_TO_METHOD_TRANSFORMER) || type.equals(NEOFORGE_FIELD_TO_METHOD_TRANSFORMER)) {
            return true;
        }
        String owner;
        if (type.equals(COREMOD_CLASS_TRANSFORMER)) {
            owner = "forge";
        } else if (type.equals(NEOFORGE_COREMOD_CLASS_TRANSFORMER)) {
            // NeoForge 20.4's coremods/field_to_method.js has a subset of Forge's functions, owned by "neoforge".
            owner = "neoforge";
        } else {
            return false;
        }
        String[] labels = transformer.labels();
        return labels != null
                && labels.length == 2
                && owner.equals(labels[0])
                && FIELD_TO_METHOD_COREMOD_FUNCTIONS.contains(labels[1]);
    }

    private static String key(String internalName, String type, String name, String descriptor) {
        return type + " " + internalName + " " + name + " " + descriptor;
    }

    /** Computes frames from class files the game class loader can read, without loading those classes. */
    private final class HierarchyClassWriter extends ClassWriter {

        HierarchyClassWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String first, String second) {
            if (isInterface(first) || isInterface(second)) {
                return "java/lang/Object";
            }
            Set<String> ancestors = new HashSet<>();
            for (String type = first; type != null; type = superName(type)) {
                ancestors.add(type);
            }
            for (String type = second; type != null; type = superName(type)) {
                if (ancestors.contains(type)) {
                    return type;
                }
            }
            return "java/lang/Object";
        }

        private boolean isInterface(String internalName) {
            return header(internalName)
                    .map(reader -> (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0)
                    .orElse(false);
        }

        private String superName(String internalName) {
            return header(internalName).map(ClassReader::getSuperName).orElse(null);
        }

        private Optional<ClassReader> header(String internalName) {
            return classes.classBytes(internalName).map(ClassReader::new);
        }
    }

    /** What a transformer sees while voting and transforming. */
    private static final class VotingContext implements ITransformerVotingContext {

        private final String className;
        private final byte[] input;
        private Object node;

        VotingContext(String className, byte[] input) {
            this.className = className;
            this.input = input;
        }

        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public boolean doesClassExist() {
            return input.length > 0;
        }

        @Override
        public byte[] getInitialClassSha256() {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Every Java runtime has SHA-256", e);
            }
        }

        @Override
        public List<ITransformerActivity> getAuditActivities() {
            return List.of();
        }

        @Override
        public String getReason() {
            return ITransformerActivity.CLASSLOADING_REASON;
        }

        @Override
        public boolean applyFieldPredicate(FieldPredicate predicate) {
            return node instanceof FieldNode field
                    && predicate.test(field.access, field.name, field.desc, field.signature, field.value);
        }

        @Override
        public boolean applyMethodPredicate(MethodPredicate predicate) {
            return node instanceof MethodNode method
                    && predicate.test(
                            method.access,
                            method.name,
                            method.desc,
                            method.signature,
                            method.exceptions.toArray(String[]::new));
        }

        @Override
        public boolean applyClassPredicate(ClassPredicate predicate) {
            return node instanceof ClassNode clazz
                    && predicate.test(
                            clazz.version,
                            clazz.access,
                            clazz.name,
                            clazz.signature,
                            clazz.superName,
                            clazz.interfaces.toArray(String[]::new));
        }

        @Override
        public boolean applyInstructionPredicate(InsnPredicate predicate) {
            if (!(node instanceof MethodNode method)) {
                return false;
            }
            int count = 0;
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (instruction.getOpcode() >= 0 && predicate.test(count++, instruction.getOpcode())) {
                    return true;
                }
            }
            return false;
        }
    }
}
