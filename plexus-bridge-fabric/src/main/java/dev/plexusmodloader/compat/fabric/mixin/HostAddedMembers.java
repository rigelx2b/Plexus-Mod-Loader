package dev.plexusmodloader.compat.fabric.mixin;

import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.fabric.mixin.HostPatchAdjustments.Finding;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.ConvertInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.NopInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StackInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Fabric mixin members that meet a member the host loader's patch added or changed (ADR-0021 G8). Mixin fails a whole
 * mixin when one of its members collides with the patched class: an {@code @Accessor} or {@code @Invoker} whose method
 * the patch added itself, a method the mixin adds that the patch also added, or an {@code @Accessor} of a field whose
 * type the patch changed. Decided from the vanilla and the patched class, one member at a time:
 *
 * <ul>
 *   <li>The patch added a public method with the code the accessor or invoker would have (reads or writes that field,
 *       calls that method): Mixin does not see the accessor, and the patched method implements it ({@code
 *       PLEXUS-COMPAT-212}). The mixin works as written.
 *   <li>The patch added the method the mixin adds, with the same code: the patched class keeps its own ({@code
 *       PLEXUS-COMPAT-212}). The mixin works as written.
 *   <li>The patch added a getter of a list field of its own, and the mixin adds the same getter of a list in an
 *       {@code @Unique} field of the mixin (Fabric's tag API and Forge both add {@code TagFile.remove()}, Fabric for
 *       {@code "fabric:remove"}, Forge for {@code "remove"}). Mixin keeps the two fields apart, so the code only looks
 *       the same; one getter then answers for both: the mixin's overwrites the patched one and returns the patched list
 *       followed by the mixin's ({@code PLEXUS-COMPAT-212}). The mixin's field takes a name of its own.
 *   <li>The patch added a method that works on a list field of its own exactly as the mixin's method works on an
 *       {@code @Unique} list field of the mixin, both lists final and set only by the constructors to a new empty list
 *       (Fabric API 26.3's data generation and Forge 26.3 both add {@code TagBuilder.remove(TagEntry)}, adding to {@code
 *       removals} and {@code removeEntries}): the patched method stays, and the mixin's other methods read the patch's
 *       list where they read their own, so both loaders see one list ({@code PLEXUS-COMPAT-212}).
 *   <li>A non-public {@code @Unique} method of the mixin has the name and descriptor of a default method an interface
 *       the patch added gives the target (Fabric API's entity events add {@code self()} to {@code LivingEntity}, and Forge's
 *       {@code IForgeLivingEntity} has a default {@code self()}). Mixin renames a unique method only when the class
 *       itself has one of that name, so it would add it as it is, and the patch's own calls of the default would find
 *       the mixin's private method instead ({@code AbstractMethodError}). The method takes a name of its own, as Mixin
 *       gives one on a clash, and the mixin's calls follow it ({@code PLEXUS-COMPAT-212}).
 *   <li>The patch added it, or an accessor's or invoker's method, with other code, or changed the type of the field an
 *       accessor reads or writes: one class cannot hold both, so the mixin cannot work as written on that game. The mod
 *       is incompatible ({@code PLEXUS-COMPAT-224}, ADR-0030) and is not loaded; Plexus Mod Loader does not rename or
 *       replace either method to make the rest of the mod run.
 * </ul>
 *
 * <p>Only single-target mixins are checked (Mixin allows accessors only there); a member Mixin itself resolves
 * ({@code @Unique}, {@code @Shadow}, {@code @Overwrite}, injectors) is left to Mixin.
 */
final class HostAddedMembers {

    static final HostAddedMembers NONE = new HostAddedMembers(Map.of());

    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";

    private static final ClassDesc CD_LIST = ClassDesc.of("java.util.List");
    private static final ClassDesc CD_void = java.lang.constant.ConstantDescs.CD_void;
    private static final ClassDesc CD_boolean = java.lang.constant.ConstantDescs.CD_boolean;

    /**
     * A mixin method Mixin does not see, because the patched class has it with the same code; or, with {@code
     * joinedField}, the mixin's getter of that {@code @Unique} list field, which overwrites the patched getter of the
     * target's field of the same name and returns both lists.
     */
    private record Change(
            String name, String descriptor, String joinedField, String target, boolean renamed, String hostField) {

        Change(String name, String descriptor) {
            this(name, descriptor, null, null, false, null);
        }

        Change(String name, String descriptor, String joinedField, String target) {
            this(name, descriptor, joinedField, target, false, null);
        }

        /** An {@code @Unique} method that takes the name {@link #UNIQUE_METHOD_PREFIX} + its name. */
        static Change renamed(String name, String descriptor) {
            return new Change(name, descriptor, null, null, true, null);
        }

        /**
         * A method the patched class has with the same code on its own list field {@code hostField}, where the mixin's
         * works on its {@code @Unique} list {@code uniqueField}: the patched method stays, and the mixin reads {@code
         * hostField} wherever it read {@code uniqueField}.
         */
        static Change aliased(String name, String descriptor, String uniqueField, String hostField, String target) {
            return new Change(name, descriptor, uniqueField, target, false, hostField);
        }

        boolean joined() {
            return joinedField != null && hostField == null;
        }

        boolean aliased() {
            return hostField != null;
        }
    }

    /** The prefix of the name an {@code @Unique} method takes when it would hide a default of the host's interface. */
    static final String UNIQUE_METHOD_PREFIX = "plexus$unique$";

    /** The prefix of the name the mixin's own field takes when its getter answers for the patched field too. */
    static final String JOINED_FIELD_PREFIX = "plexus$joined$";

    /** The prefix of the name the mixin's own field takes when the mixin reads the patched field in its place. */
    static final String ALIASED_FIELD_PREFIX = "plexus$aliased$";

    private final Map<String, List<Change>> changes;

    private HostAddedMembers(Map<String, List<Change>> changes) {
        this.changes = changes;
    }

    /** Collects the changes while {@link HostPatchAdjustments#plan} goes through the mixins. Not thread-safe. */
    static final class Planner {

        private final HostedLaunch host;
        private final HostPatchAdjustments.Classes classes;
        private final Map<String, List<Change>> changes = new HashMap<>();

        Planner(HostedLaunch host, HostPatchAdjustments.Classes classes) {
            this.host = host;
            this.classes = classes;
        }

        /**
         * The findings for {@code mixin} of mod {@code modId}, from configuration {@code config}; {@code
         * overwriteNeedsAnnotation} is the configuration's {@code overwrites.requireAnnotations}. When one of them is
         * {@link Finding#leftOut} (Mixin would fail the whole mixin), it is the only one and nothing is planned for the
         * mixin.
         */
        List<Finding> check(String modId, String config, ClassModel mixin, boolean overwriteNeedsAnnotation) {
            String mixinName = mixin.thisClass().asInternalName();
            List<String> targets = HostPatchAdjustments.mixinTargets(mixin);
            if (targets.size() != 1) {
                return List.of();
            }
            String targetName = targets.getFirst();
            Optional<ClassModel> vanilla = classes.vanilla(targetName);
            Optional<ClassModel> patched = classes.patched(targetName);
            if (vanilla.isEmpty() || patched.isEmpty()) {
                return List.of();
            }
            List<Finding> findings = new ArrayList<>();
            List<Change> ownChanges = new ArrayList<>();
            Map<String, String> hostDefaults = addedInterfaceDefaults(vanilla.get(), patched.get());
            for (MethodModel method : mixin.methods()) {
                String member =
                        method.methodName().stringValue() + method.methodType().stringValue();
                String hostInterface = hostDefaults.get(member);
                if (hostInterface != null
                        && HostPatchAdjustments.has(method.attributes(), UNIQUE)
                        && !method.flags().has(java.lang.reflect.AccessFlag.STATIC)
                        // A public method implements the default (as a mod's own interface intends); only another one
                        // hides it.
                        && !method.flags().has(java.lang.reflect.AccessFlag.PUBLIC)
                        && !declaredInClassChain(patched.get(), member)) {
                    ownChanges.add(Change.renamed(
                            method.methodName().stringValue(),
                            method.methodType().stringValue()));
                    findings.add(new Finding(
                            modId,
                            config,
                            mixinName,
                            "its @Unique method " + member + " has the name and descriptor of the default method "
                                    + host.hostName() + "'s " + hostInterface + " gives " + targetName
                                    + "; Mixin would add it as it is, hiding that default from the host's calls, so it"
                                    + " takes the name " + UNIQUE_METHOD_PREFIX
                                    + method.methodName().stringValue()
                                    + " and the mixin's calls follow it",
                            false,
                            "PLEXUS-COMPAT-212",
                            false));
                }
            }
            for (MethodModel method : mixin.methods()) {
                String name = method.methodName().stringValue();
                String descriptor = method.methodType().stringValue();
                String member = name + descriptor;
                boolean isStatic = method.flags().has(java.lang.reflect.AccessFlag.STATIC);
                boolean added = HostPatchAdjustments.methods(vanilla.get(), name, descriptor)
                                .isEmpty()
                        && !HostPatchAdjustments.methods(patched.get(), name, descriptor)
                                .isEmpty();
                Optional<Annotation> accessor =
                        HostPatchAdjustments.annotation(method.attributes(), HostPatchAdjustments.ACCESSOR);
                Optional<Annotation> invoker =
                        HostPatchAdjustments.annotation(method.attributes(), HostPatchAdjustments.INVOKER);
                if (accessor.isPresent() || invoker.isPresent()) {
                    if (isStatic) {
                        continue;
                    }
                    String use = accessor.isPresent() ? "@Accessor" : "@Invoker";
                    if (added) {
                        MethodModel own = HostPatchAdjustments.methods(patched.get(), name, descriptor)
                                .getFirst();
                        boolean same = own.flags().has(java.lang.reflect.AccessFlag.PUBLIC)
                                && !own.flags().has(java.lang.reflect.AccessFlag.STATIC)
                                && (accessor.isPresent()
                                        ? (isFieldAccessor(own, targetName, field(accessor.get(), name), method)
                                                || isAttributeBuilderBridgeMethod(
                                                        own, targetName, field(accessor.get(), name)))
                                        : isInvoker(own, targetName, invoked(invoker.get(), name), method));
                        if (!same) {
                            // With @Overwrite required Mixin fails the whole mixin; otherwise it replaces the patched
                            // method, and the host's code calling it gets the accessor's result.
                            Finding finding = Finding.incompatible(
                                    modId,
                                    config,
                                    mixinName,
                                    "its " + use + " " + member + " is also a method " + host.hostName()
                                            + "'s patch added to " + targetName + ", with other code; one class cannot"
                                            + " hold both",
                                    overwriteNeedsAnnotation);
                            if (overwriteNeedsAnnotation) {
                                return List.of(finding);
                            }
                            findings.add(finding);
                            continue;
                        }
                        ownChanges.add(new Change(name, descriptor));
                        findings.add(new Finding(
                                modId,
                                config,
                                mixinName,
                                "its " + use + " " + member + " is a method " + host.hostName()
                                        + "'s patch added to " + targetName + " with the same code; that method"
                                        + " serves it and the rest of the mixin applies",
                                false,
                                "PLEXUS-COMPAT-212",
                                false));
                    } else if (accessor.isPresent()) {
                        String field = field(accessor.get(), name);
                        Optional<String> type = accessedType(method.methodTypeSymbol());
                        Optional<String> patchedType = type.flatMap(
                                accessed -> changedFieldType(vanilla.get(), patched.get(), field, accessed));
                        if (patchedType.isPresent()) {
                            if (isAttributeSupplierBuilder(targetName, field, type.get(), patchedType.get())) {
                                ownChanges.add(new Change(name, descriptor));
                                findings.add(new Finding(
                                        modId,
                                        config,
                                        mixinName,
                                        "its @Accessor " + member + " uses field " + field + " of " + targetName
                                                + " as "
                                                + type.get() + ", which " + host.hostName() + "'s patch changed to "
                                                + patchedType.get() + "; adapted via AttributeBuilderBridge",
                                        false,
                                        "PLEXUS-COMPAT-212",
                                        false));
                            } else {
                                findings.add(Finding.incompatible(
                                        modId,
                                        config,
                                        mixinName,
                                        "its @Accessor " + member + " uses field " + field + " of " + targetName
                                                + " as "
                                                + type.get() + ", which " + host.hostName() + "'s patch changed to "
                                                + patchedType.get()
                                                + "; no accessor of that type can work on that game",
                                        false));
                            }
                        }
                    }
                    continue;
                }
                if (!added
                        || name.startsWith("<")
                        || method.flags().has(java.lang.reflect.AccessFlag.SYNTHETIC)
                        || method.flags().has(java.lang.reflect.AccessFlag.BRIDGE)
                        || method.code().isEmpty()
                        || resolvedByMixin(method)
                        || inVanillaSupertypes(vanilla.get(), name, descriptor, new HashSet<>())) {
                    continue;
                }
                MethodModel own = HostPatchAdjustments.methods(patched.get(), name, descriptor)
                        .getFirst();
                Optional<String> unique = uniqueFieldRead(mixin, method, mixinName);
                if (unique.isPresent() && joinable(mixin, method, mixinName, own, targetName, unique.get())) {
                    ownChanges.add(new Change(name, descriptor, unique.get(), targetName));
                    findings.add(new Finding(
                            modId,
                            config,
                            mixinName,
                            "its method " + member + " returns the mixin's own @Unique list " + unique.get() + ", and "
                                    + host.hostName() + "'s patch added it to " + targetName + " for its own field "
                                    + unique.get() + "; one method now answers for both, returning " + targetName
                                    + "'s list followed by the mixin's",
                            false,
                            "PLEXUS-COMPAT-212",
                            false));
                    continue;
                }
                Optional<String> hostField = unique.flatMap(field ->
                        aliasedField(mixin, method, mixinName, own, targetName, field, vanilla.get(), patched.get()));
                if (hostField.isPresent()) {
                    ownChanges.add(Change.aliased(name, descriptor, unique.get(), hostField.get(), targetName));
                    findings.add(new Finding(
                            modId,
                            config,
                            mixinName,
                            "its method " + member + " works on the mixin's own @Unique list " + unique.get() + ", and "
                                    + host.hostName() + "'s patch added it to " + targetName
                                    + " with the same code on its own list " + hostField.get()
                                    + " (both start empty and are set only by the constructor); " + targetName
                                    + " keeps its method and the mixin reads " + hostField.get() + " where it read "
                                    + unique.get() + ", so both loaders see one list",
                            false,
                            "PLEXUS-COMPAT-212",
                            false));
                    continue;
                }
                if (unique.isEmpty() && sameCode(method, mixinName, own, targetName)) {
                    ownChanges.add(new Change(name, descriptor));
                    findings.add(new Finding(
                            modId,
                            config,
                            mixinName,
                            "its method " + member + " is a method " + host.hostName() + "'s patch added to "
                                    + targetName + " with the same code; " + targetName
                                    + " keeps its own and the rest of the mixin applies",
                            false,
                            "PLEXUS-COMPAT-212",
                            false));
                    continue;
                }
                findings.add(Finding.incompatible(
                        modId,
                        config,
                        mixinName,
                        "its method " + member + " is also a method " + host.hostName() + "'s patch added to "
                                + targetName + ", with other code; one class cannot hold both",
                        false));
            }
            if (!ownChanges.isEmpty()) {
                changes.put(mixinName, List.copyOf(ownChanges));
            }
            return findings;
        }

        /**
         * The default methods of the interfaces the patch added to {@code patched} (not in {@code vanilla}'s list) and
         * their superinterfaces, as name and descriptor to the interface.
         */
        private Map<String, String> addedInterfaceDefaults(ClassModel vanilla, ClassModel patched) {
            Set<String> vanillaInterfaces = new HashSet<>();
            vanilla.interfaces().forEach(face -> vanillaInterfaces.add(face.asInternalName()));
            Map<String, String> found = new HashMap<>();
            for (var face : patched.interfaces()) {
                if (!vanillaInterfaces.contains(face.asInternalName())) {
                    HostPatchAdjustments.defaults(face.asInternalName(), classes::patched, new HashSet<>())
                            .forEach(found::putIfAbsent);
                }
            }
            return found;
        }

        /** Whether {@code type} or a patched superclass declares {@code member}, which then wins over every default. */
        private boolean declaredInClassChain(ClassModel type, String member) {
            Optional<ClassModel> current = Optional.of(type);
            for (int depth = 0; current.isPresent() && depth < 32; depth++) {
                boolean declared = current.get().methods().stream()
                        .anyMatch(method -> member.equals(method.methodName().stringValue()
                                + method.methodType().stringValue()));
                if (declared) {
                    return true;
                }
                current = current.get().superclass().flatMap(parent -> classes.patched(parent.asInternalName()));
            }
            return false;
        }

        HostAddedMembers build() {
            return changes.isEmpty() ? NONE : new HostAddedMembers(Map.copyOf(changes));
        }

        /**
         * Whether a vanilla supertype of {@code type} declares {@code name descriptor}: the mixin's method then
         * overrides it and is not the mixin's own.
         */
        private boolean inVanillaSupertypes(ClassModel type, String name, String descriptor, Set<String> seen) {
            List<String> supertypes = new ArrayList<>();
            type.superclass().ifPresent(superclass -> supertypes.add(superclass.asInternalName()));
            type.interfaces().forEach(entry -> supertypes.add(entry.asInternalName()));
            for (String supertype : supertypes) {
                if (!seen.add(supertype)) {
                    continue;
                }
                Optional<ClassModel> model = classes.vanilla(supertype);
                if (model.isPresent()
                        && (!HostPatchAdjustments.methods(model.get(), name, descriptor)
                                        .isEmpty()
                                || inVanillaSupertypes(model.get(), name, descriptor, seen))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The mixin {@code mixin} as Mixin reads it: without the methods the patched class has with the same code, and with
     * each joined getter returning the patched list followed by the mixin's, whose field is renamed.
     */
    byte[] apply(String mixin, byte[] bytes) {
        List<Change> own = changes.getOrDefault(mixin, List.of());
        if (own.isEmpty()) {
            return bytes;
        }
        ClassFile classFile = ClassFile.of();
        ClassModel model = classFile.parse(bytes);
        Map<String, String> renamed = new HashMap<>();
        Set<String> renamedMethods = new HashSet<>();
        Map<String, Change> aliasedFields = new HashMap<>();
        for (Change change : own) {
            if (change.joined()) {
                renamed.put(change.joinedField(), JOINED_FIELD_PREFIX + change.joinedField());
            }
            if (change.aliased()) {
                renamed.put(change.joinedField(), ALIASED_FIELD_PREFIX + change.joinedField());
                aliasedFields.put(change.joinedField(), change);
            }
            if (change.renamed()) {
                renamedMethods.add(change.name() + change.descriptor());
            }
        }
        ClassDesc self = model.thisClass().asSymbol();
        CodeTransform renameFields = (code, element) -> {
            if (element instanceof FieldInstruction field
                    && field.opcode() == Opcode.GETFIELD
                    && field.owner().asSymbol().equals(self)
                    && aliasedFields.containsKey(field.name().stringValue())) {
                // Outside the constructor the mixin only reads its list (checked) and reads the patched one instead.
                Change alias = aliasedFields.get(field.name().stringValue());
                code.getfield(ClassDesc.ofInternalName(alias.target()), alias.hostField(), field.typeSymbol());
            } else if (element instanceof FieldInstruction field
                    && field.owner().asSymbol().equals(self)
                    && renamed.containsKey(field.name().stringValue())) {
                code.fieldAccess(field.opcode(), self, renamed.get(field.name().stringValue()), field.typeSymbol());
            } else if (element instanceof InvokeInstruction call
                    && call.owner().asSymbol().equals(self)
                    && renamedMethods.contains(
                            call.name().stringValue() + call.type().stringValue())) {
                code.invoke(
                        call.opcode(),
                        self,
                        UNIQUE_METHOD_PREFIX + call.name().stringValue(),
                        call.typeSymbol(),
                        call.isInterface());
            } else {
                code.with(element);
            }
        };
        CodeTransform renameOnly = (code, element) -> {
            if (element instanceof FieldInstruction field
                    && field.owner().asSymbol().equals(self)
                    && renamed.containsKey(field.name().stringValue())) {
                code.fieldAccess(field.opcode(), self, renamed.get(field.name().stringValue()), field.typeSymbol());
            } else {
                renameFields.accept(code, element);
            }
        };
        return classFile.transformClass(model, (builder, element) -> {
            if (element instanceof FieldModel field
                    && renamed.containsKey(field.fieldName().stringValue())) {
                builder.withField(
                        renamed.get(field.fieldName().stringValue()),
                        field.fieldTypeSymbol(),
                        fieldBuilder -> field.forEach(fieldBuilder::with));
                return;
            }
            if (element instanceof MethodModel method) {
                Optional<Change> change = own.stream()
                        .filter(candidate -> method.methodName().equalsString(candidate.name())
                                && method.methodType().equalsString(candidate.descriptor()))
                        .findFirst();
                if (change.isPresent() && change.get().renamed()) {
                    builder.withMethod(
                            UNIQUE_METHOD_PREFIX + method.methodName().stringValue(),
                            method.methodTypeSymbol(),
                            method.flags().flagsMask(),
                            renamedMethod -> method.forEach(element1 -> {
                                if (element1 instanceof java.lang.classfile.CodeModel code) {
                                    renamedMethod.transformCode(code, renameFields);
                                } else {
                                    renamedMethod.with(element1);
                                }
                            }));
                    return;
                }
                if (change.isPresent() && !change.get().joined()) {
                    return;
                }
                if (change.isPresent()) {
                    joinedGetter(builder, method, self, change.get());
                    return;
                }
                if (method.methodName().equalsString("<init>")) {
                    // The constructor keeps initialising the mixin's own list, renamed and no longer read.
                    builder.transformMethod(method, MethodTransform.transformingCode(renameOnly));
                    return;
                }
                builder.transformMethod(method, MethodTransform.transformingCode(renameFields));
                return;
            }
            builder.with(element);
        });
    }

    /**
     * {@code getter} as an {@code @Overwrite} of the patched getter: a list of the target's field followed by the
     * mixin's (renamed) field, unmodifiable as the mixin's getter returned it.
     */
    private static void joinedGetter(
            java.lang.classfile.ClassBuilder builder, MethodModel getter, ClassDesc self, Change change) {
        ClassDesc target = ClassDesc.ofInternalName(change.target());
        ClassDesc arrayList = ClassDesc.of("java.util.ArrayList");
        ClassDesc collection = ClassDesc.of("java.util.Collection");
        builder.withMethod(
                getter.methodName().stringValue(),
                getter.methodTypeSymbol(),
                getter.flags().flagsMask(),
                method -> {
                    getter.findAttribute(java.lang.classfile.Attributes.runtimeInvisibleAnnotations())
                            .ifPresent(method::with);
                    // Mixin reads @Overwrite from the visible annotations.
                    List<Annotation> visible = new ArrayList<>(
                            getter.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations())
                                    .map(RuntimeVisibleAnnotationsAttribute::annotations)
                                    .orElse(List.of()));
                    visible.add(Annotation.of(ClassDesc.ofDescriptor(HostPatchAdjustments.OVERWRITE)));
                    method.with(RuntimeVisibleAnnotationsAttribute.of(visible));
                    method.withCode(code -> code.new_(arrayList)
                            .dup()
                            .aload(0)
                            .getfield(target, change.joinedField(), CD_LIST)
                            .invokespecial(arrayList, "<init>", MethodTypeDesc.of(CD_void, collection))
                            .astore(1)
                            .aload(1)
                            .aload(0)
                            .getfield(self, JOINED_FIELD_PREFIX + change.joinedField(), CD_LIST)
                            .invokeinterface(CD_LIST, "addAll", MethodTypeDesc.of(CD_boolean, collection))
                            .pop()
                            .aload(1)
                            .invokestatic(
                                    ClassDesc.of("java.util.Collections"),
                                    "unmodifiableList",
                                    MethodTypeDesc.of(CD_LIST, CD_LIST))
                            .areturn());
                });
    }

    /**
     * The {@code @Unique} field of {@code mixin} that {@code method} reads, when it reads one: a field Mixin keeps apart
     * from a target field of the same name.
     */
    private static Optional<String> uniqueFieldRead(ClassModel mixin, MethodModel method, String mixinName) {
        for (Instruction instruction : instructions(method)) {
            if (instruction instanceof FieldInstruction field
                    && field.owner().asInternalName().equals(mixinName)) {
                String name = field.name().stringValue();
                boolean unique = mixin.fields().stream()
                        .anyMatch(declared -> declared.fieldName().equalsString(name)
                                && HostPatchAdjustments.has(declared.attributes(), UNIQUE));
                if (unique) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Whether the mixin's {@code method} only returns its {@code @Unique} list {@code field} (as it is or unmodifiable)
     * and the patched {@code own} only returns the target's own list field of the same name.
     */
    private static boolean joinable(
            ClassModel mixin, MethodModel method, String mixinName, MethodModel own, String targetName, String field) {
        String list = CD_LIST.descriptorString();
        if (method.flags().has(java.lang.reflect.AccessFlag.STATIC)
                || !method.methodType().equalsString("()" + list)
                || !own.methodType().equalsString("()" + list)
                || mixin.fields().stream()
                        .noneMatch(declared -> declared.fieldName().equalsString(field)
                                && declared.fieldType().equalsString(list))) {
            return false;
        }
        List<Instruction> ownCode = instructions(own);
        boolean ownGetter = ownCode.size() == 3
                && isThis(ownCode.get(0))
                && ownCode.get(1) instanceof FieldInstruction get
                && get.opcode() == Opcode.GETFIELD
                && isMember(get.owner().asInternalName(), get.name().stringValue(), targetName, field)
                && get.type().equalsString(list)
                && ownCode.get(2) instanceof ReturnInstruction;
        List<Instruction> mixinCode = instructions(method);
        boolean mixinGetter =
                (mixinCode.size() == 3 || isUnmodifiableCollectionGetter(mixinCode, ownCode, mixinName, targetName))
                        && isThis(mixinCode.get(0))
                        && mixinCode.get(1) instanceof FieldInstruction get
                        && get.opcode() == Opcode.GETFIELD
                        && isMember(get.owner().asInternalName(), get.name().stringValue(), mixinName, field)
                        && mixinCode.getLast() instanceof ReturnInstruction;
        return ownGetter && mixinGetter;
    }

    /**
     * The patched target's own list field that the patched {@code own} works on exactly as the mixin's {@code method}
     * works on its {@code @Unique} list {@code field}, when the mixin can read that field in place of its own without a
     * change of meaning: Fabric API 26.3's data generation and MinecraftForge 26.3 both add {@code
     * TagBuilder.remove(TagEntry)}, which adds the entry to a list ({@code removals}, {@code removeEntries}) and returns
     * the builder. Both methods have the same instructions but for that field (and a cast of {@code this} to the target,
     * which is a no-op there); both fields are final {@code List}s that only the constructors set, each to a new empty
     * {@code ArrayList}; the patch's field is its own (not vanilla's). The patched method then serves the mixin's callers,
     * and the mixin's other methods see the same entries when they read the patched list.
     */
    private static Optional<String> aliasedField(
            ClassModel mixin,
            MethodModel method,
            String mixinName,
            MethodModel own,
            String targetName,
            String field,
            ClassModel vanilla,
            ClassModel patched) {
        String list = CD_LIST.descriptorString();
        if (method.flags().has(java.lang.reflect.AccessFlag.STATIC)
                || own.flags().has(java.lang.reflect.AccessFlag.STATIC)
                || method.code().isEmpty()
                || own.code().isEmpty()
                || !method.code().get().exceptionHandlers().isEmpty()
                || !own.code().get().exceptionHandlers().isEmpty()) {
            return Optional.empty();
        }
        boolean finalUniqueList = mixin.fields().stream()
                .anyMatch(declared -> declared.fieldName().equalsString(field)
                        && declared.fieldType().equalsString(list)
                        && declared.flags().has(java.lang.reflect.AccessFlag.FINAL)
                        && HostPatchAdjustments.has(declared.attributes(), UNIQUE));
        if (!finalUniqueList || !onlyNewListsInConstructors(mixin.methods(), mixinName, field)) {
            return Optional.empty();
        }
        List<Instruction> mixinCode = withoutCastsOfThis(instructions(method), targetName);
        List<Instruction> ownCode = instructions(own);
        if (mixinCode.size() != ownCode.size()) {
            return Optional.empty();
        }
        String hostField = null;
        for (int index = 0; index < mixinCode.size(); index++) {
            Instruction mine = mixinCode.get(index);
            Instruction theirs = ownCode.get(index);
            if (mine instanceof FieldInstruction read
                    && read.owner().asInternalName().equals(mixinName)
                    && read.name().equalsString(field)) {
                if (read.opcode() != Opcode.GETFIELD
                        || !(theirs instanceof FieldInstruction other)
                        || other.opcode() != Opcode.GETFIELD
                        || !other.owner().asInternalName().equals(targetName)
                        || !other.type().equalsString(list)
                        || (hostField != null && !other.name().equalsString(hostField))) {
                    return Optional.empty();
                }
                hostField = other.name().stringValue();
                continue;
            }
            if (!sameInstruction(mine, theirs, mixinName, targetName)) {
                return Optional.empty();
            }
        }
        if (hostField == null) {
            return Optional.empty();
        }
        String found = hostField;
        boolean patchesOwnList = patched.fields().stream()
                        .anyMatch(declared -> declared.fieldName().equalsString(found)
                                && declared.fieldType().equalsString(list)
                                && declared.flags().has(java.lang.reflect.AccessFlag.FINAL))
                && vanilla.fields().stream()
                        .noneMatch(declared -> declared.fieldName().equalsString(found))
                && mixin.fields().stream()
                        .noneMatch(declared -> declared.fieldName().equalsString(found));
        if (!patchesOwnList || !onlyNewListsInConstructors(patched.methods(), targetName, found)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    /**
     * Whether {@code owner.field} is written at least once in {@code methods}, and every write is in a constructor and
     * stores a new empty {@code ArrayList} ({@code new ArrayList<>()} right before it).
     */
    private static boolean onlyNewListsInConstructors(List<MethodModel> methods, String owner, String field) {
        boolean written = false;
        for (MethodModel method : methods) {
            List<Instruction> code = instructions(method);
            for (int index = 0; index < code.size(); index++) {
                if (!(code.get(index) instanceof FieldInstruction put)
                        || (put.opcode() != Opcode.PUTFIELD && put.opcode() != Opcode.PUTSTATIC)
                        || !put.owner().asInternalName().equals(owner)
                        || !put.name().equalsString(field)) {
                    continue;
                }
                boolean newList = put.opcode() == Opcode.PUTFIELD
                        && method.methodName().equalsString("<init>")
                        && index > 0
                        && code.get(index - 1) instanceof InvokeInstruction init
                        && init.opcode() == Opcode.INVOKESPECIAL
                        && init.owner().asInternalName().equals("java/util/ArrayList")
                        && init.name().equalsString("<init>")
                        && init.type().equalsString("()V");
                if (!newList) {
                    return false;
                }
                written = true;
            }
        }
        return written;
    }

    /** {@code code} without a {@code checkcast} of {@code this} ({@code aload_0}) to the target class, a no-op there. */
    private static List<Instruction> withoutCastsOfThis(List<Instruction> code, String targetName) {
        List<Instruction> kept = new ArrayList<>();
        for (int index = 0; index < code.size(); index++) {
            Instruction instruction = code.get(index);
            if (instruction instanceof TypeCheckInstruction check
                    && check.opcode() == Opcode.CHECKCAST
                    && check.type().asInternalName().equals(targetName)
                    && index > 0
                    && isThis(code.get(index - 1))) {
                continue;
            }
            kept.add(instruction);
        }
        return kept;
    }

    /** Members Mixin itself places: shadows, overwrites, injectors, {@code @Unique} (renamed or dropped by Mixin). */
    private static boolean resolvedByMixin(MethodModel method) {
        if (HostPatchAdjustments.has(method.attributes(), HostPatchAdjustments.SHADOW)
                || HostPatchAdjustments.has(method.attributes(), HostPatchAdjustments.OVERWRITE)
                || HostPatchAdjustments.has(method.attributes(), UNIQUE)) {
            return true;
        }
        return HostPatchAdjustments.INJECTORS.stream()
                .anyMatch(type -> HostPatchAdjustments.has(method.attributes(), type));
    }

    private static String field(Annotation accessor, String method) {
        return HostPatchAdjustments.value(accessor).orElse(HostPatchAdjustments.accessorName(method));
    }

    private static String invoked(Annotation invoker, String method) {
        return HostPatchAdjustments.value(invoker).orElse(HostPatchAdjustments.invokerName(method));
    }

    /** The field type an accessor of {@code type} reads ({@code ()T}) or writes ({@code (T)V}). */
    private static Optional<String> accessedType(MethodTypeDesc type) {
        if (type.parameterCount() == 0 && !type.returnType().equals(java.lang.constant.ConstantDescs.CD_void)) {
            return Optional.of(type.returnType().descriptorString());
        }
        if (type.parameterCount() == 1 && type.returnType().equals(java.lang.constant.ConstantDescs.CD_void)) {
            return Optional.of(type.parameterType(0).descriptorString());
        }
        return Optional.empty();
    }

    /** The patched type of field {@code name}, when vanilla has it as {@code type} and the patch only as another type. */
    private static Optional<String> changedFieldType(ClassModel vanilla, ClassModel patched, String name, String type) {
        boolean inVanilla = vanilla.fields().stream()
                .anyMatch(field -> field.fieldName().equalsString(name)
                        && field.fieldType().equalsString(type));
        if (!inVanilla) {
            return Optional.empty();
        }
        List<FieldModel> after = patched.fields().stream()
                .filter(field -> field.fieldName().equalsString(name))
                .toList();
        if (after.isEmpty()
                || after.stream().anyMatch(field -> field.fieldType().equalsString(type))) {
            return Optional.empty();
        }
        return Optional.of(after.getFirst().fieldType().stringValue());
    }

    /**
     * Whether {@code method} of {@code owner} is exactly the accessor {@code accessor} of field {@code field}: {@code
     * this.field} returned, or set from the one parameter.
     */
    private static boolean isFieldAccessor(MethodModel method, String owner, String field, MethodModel accessor) {
        MethodTypeDesc type = accessor.methodTypeSymbol();
        List<Instruction> code = instructions(method);
        if (code.size() == 3 && type.parameterCount() == 0) {
            return isThis(code.get(0))
                    && code.get(1) instanceof FieldInstruction get
                    && get.opcode() == Opcode.GETFIELD
                    && isMember(get.owner().asInternalName(), get.name().stringValue(), owner, field)
                    && get.typeSymbol().equals(type.returnType())
                    && code.get(2) instanceof ReturnInstruction result
                    && result.typeKind() == TypeKind.from(type.returnType());
        }
        if (code.size() == 4 && type.parameterCount() == 1) {
            return isThis(code.get(0))
                    && code.get(1) instanceof LoadInstruction value
                    && value.slot() == 1
                    && value.typeKind() == TypeKind.from(type.parameterType(0))
                    && code.get(2) instanceof FieldInstruction put
                    && put.opcode() == Opcode.PUTFIELD
                    && isMember(put.owner().asInternalName(), put.name().stringValue(), owner, field)
                    && put.typeSymbol().equals(type.parameterType(0))
                    && code.get(3) instanceof ReturnInstruction result
                    && result.typeKind() == TypeKind.VOID;
        }
        return false;
    }

    /** Whether {@code method} of {@code owner} only calls {@code owner.target} with its parameters and returns. */
    private static boolean isInvoker(MethodModel method, String owner, String target, MethodModel invoker) {
        MethodTypeDesc type = invoker.methodTypeSymbol();
        List<Instruction> code = instructions(method);
        if (code.size() != type.parameterCount() + 3 || !isThis(code.getFirst())) {
            return false;
        }
        int slot = 1;
        for (int index = 0; index < type.parameterCount(); index++) {
            TypeKind kind = TypeKind.from(type.parameterType(index));
            if (!(code.get(index + 1) instanceof LoadInstruction load)
                    || load.slot() != slot
                    || load.typeKind() != kind) {
                return false;
            }
            slot += kind.slotSize();
        }
        return code.get(code.size() - 2) instanceof InvokeInstruction call
                && (call.opcode() == Opcode.INVOKEVIRTUAL || call.opcode() == Opcode.INVOKESPECIAL)
                && isMember(call.owner().asInternalName(), call.name().stringValue(), owner, target)
                && call.typeSymbol().equals(type)
                && code.getLast() instanceof ReturnInstruction result
                && result.typeKind() == TypeKind.from(type.returnType());
    }

    private static boolean isThis(Instruction instruction) {
        return instruction instanceof LoadInstruction load && load.slot() == 0 && load.typeKind() == TypeKind.REFERENCE;
    }

    private static boolean isMember(String owner, String name, String expectedOwner, String expectedName) {
        return owner.equals(expectedOwner) && name.equals(expectedName);
    }

    /**
     * Whether the mixin's {@code method} and the patched {@code own} have the same instructions, the mixin's class
     * standing for the target. Code with branches, switches, dynamic calls or exception handlers never counts as the
     * same.
     */
    static boolean sameCode(MethodModel method, String mixinName, MethodModel own, String targetName) {
        if (method.flags().has(java.lang.reflect.AccessFlag.STATIC)
                        != own.flags().has(java.lang.reflect.AccessFlag.STATIC)
                || method.code().isEmpty()
                || own.code().isEmpty()
                || !method.code().get().exceptionHandlers().isEmpty()
                || !own.code().get().exceptionHandlers().isEmpty()) {
            return false;
        }
        List<Instruction> mixinCode = instructions(method);
        List<Instruction> ownCode = instructions(own);
        if (mixinCode.size() != ownCode.size()) {
            return isEquivalentGetter(mixinCode, ownCode, mixinName, targetName);
        }
        for (int index = 0; index < mixinCode.size(); index++) {
            if (!sameInstruction(mixinCode.get(index), ownCode.get(index), mixinName, targetName)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameInstruction(Instruction mixin, Instruction own, String mixinName, String targetName) {
        if (mixin.opcode() != own.opcode()) {
            return false;
        }
        return switch (mixin) {
            case FieldInstruction field
            when own instanceof FieldInstruction other ->
                sameOwner(field.owner().asInternalName(), other.owner().asInternalName(), mixinName, targetName)
                        && field.name().stringValue().equals(other.name().stringValue())
                        && field.type().stringValue().equals(other.type().stringValue());
            case InvokeInstruction call
            when own instanceof InvokeInstruction other ->
                sameOwner(call.owner().asInternalName(), other.owner().asInternalName(), mixinName, targetName)
                        && call.name().stringValue().equals(other.name().stringValue())
                        && call.type().stringValue().equals(other.type().stringValue())
                        && call.isInterface() == other.isInterface();
            case LoadInstruction load when own instanceof LoadInstruction other -> load.slot() == other.slot();
            case StoreInstruction store when own instanceof StoreInstruction other -> store.slot() == other.slot();
            case IncrementInstruction increment
            when own instanceof IncrementInstruction other ->
                increment.slot() == other.slot() && increment.constant() == other.constant();
            case ConstantInstruction constant
            when own instanceof ConstantInstruction other ->
                Objects.equals(constant.constantValue(), other.constantValue());
            case NewObjectInstruction created
            when own instanceof NewObjectInstruction other ->
                sameOwner(
                        created.className().asInternalName(),
                        other.className().asInternalName(),
                        mixinName,
                        targetName);
            case TypeCheckInstruction check
            when own instanceof TypeCheckInstruction other ->
                sameOwner(check.type().asInternalName(), other.type().asInternalName(), mixinName, targetName);
            case NewReferenceArrayInstruction array
            when own instanceof NewReferenceArrayInstruction other ->
                sameOwner(
                        array.componentType().asInternalName(),
                        other.componentType().asInternalName(),
                        mixinName,
                        targetName);
            case OperatorInstruction _,
                    ReturnInstruction _,
                    StackInstruction _,
                    ConvertInstruction _,
                    ThrowInstruction _,
                    ArrayLoadInstruction _,
                    ArrayStoreInstruction _,
                    NopInstruction _,
                    MonitorInstruction _ -> true;
            default -> false;
        };
    }

    private static boolean sameOwner(String mixinOwner, String ownOwner, String mixinName, String targetName) {
        return (mixinOwner.equals(mixinName) ? targetName : mixinOwner).equals(ownOwner);
    }

    private static boolean isEquivalentGetter(List<Instruction> a, List<Instruction> b, String aOwner, String bOwner) {
        return isUnmodifiableCollectionGetter(a, b, aOwner, bOwner)
                || isUnmodifiableCollectionGetter(b, a, bOwner, aOwner);
    }

    private static boolean isUnmodifiableCollectionGetter(
            List<Instruction> wrapped, List<Instruction> direct, String wrappedOwner, String directOwner) {
        if (wrapped.size() != 4 || direct.size() != 3) {
            return false;
        }
        if (!(wrapped.get(0) instanceof LoadInstruction wl
                && wl.slot() == 0
                && direct.get(0) instanceof LoadInstruction dl
                && dl.slot() == 0)) {
            return false;
        }
        if (!(wrapped.get(1) instanceof FieldInstruction wf
                && wf.opcode() == Opcode.GETFIELD
                && direct.get(1) instanceof FieldInstruction df
                && df.opcode() == Opcode.GETFIELD)) {
            return false;
        }
        if (!sameInstruction(wf, df, wrappedOwner, directOwner)) {
            return false;
        }
        if (!(wrapped.get(2) instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESTATIC)) {
            return false;
        }
        String callOwner = call.owner().asInternalName();
        String callName = call.name().stringValue();
        boolean isUnmodifiableWrapper = (callOwner.equals("java/util/Collections")
                        && (callName.equals("unmodifiableList")
                                || callName.equals("unmodifiableSet")
                                || callName.equals("unmodifiableMap")
                                || callName.equals("unmodifiableCollection")))
                || ((callOwner.equals("java/util/List")
                                || callOwner.equals("java/util/Set")
                                || callOwner.equals("java/util/Map"))
                        && callName.equals("copyOf"));
        if (!isUnmodifiableWrapper) {
            return false;
        }
        return wrapped.get(3) instanceof ReturnInstruction && direct.get(2) instanceof ReturnInstruction;
    }

    private static boolean isAttributeSupplierBuilder(
            String targetName, String field, String type, String patchedType) {
        return (targetName.equals("net/minecraft/world/entity/ai/attributes/AttributeSupplier$Builder")
                        || targetName.endsWith("/AttributeSupplier$Builder")
                        || targetName.equals("AttributeSupplier$Builder"))
                && field.equals("builder")
                && type.contains("ImmutableMap$Builder")
                && patchedType.equals("Ljava/util/Map;");
    }

    private static boolean isAttributeBuilderBridgeMethod(MethodModel method, String targetName, String field) {
        if (!targetName.endsWith("AttributeSupplier$Builder") || !field.equals("builder")) {
            return false;
        }
        List<Instruction> code = instructions(method);
        return code.size() == 4
                && isThis(code.get(0))
                && code.get(1) instanceof FieldInstruction get
                && get.opcode() == Opcode.GETFIELD
                && get.name().stringValue().equals(field)
                && code.get(2) instanceof InvokeInstruction call
                && call.name().stringValue().equals("wrap")
                && call.owner().asInternalName().endsWith("AttributeBuilderBridge")
                && code.get(3) instanceof ReturnInstruction;
    }

    private static List<Instruction> instructions(MethodModel method) {
        return method.code()
                .map(code -> code.elementList().stream()
                        .filter(Instruction.class::isInstance)
                        .map(Instruction.class::cast)
                        .toList())
                .orElse(List.of());
    }
}
