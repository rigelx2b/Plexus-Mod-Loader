package dev.plexusmodloader.compat.fabric.mixin;

import static java.lang.constant.ConstantDescs.CD_Integer;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_float;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.InjectionPointReplacement;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fabric mixins of mods next to MinecraftForge that the bridges for Forge's patch carry as written ({@code
 * PLEXUS-COMPAT-212}, {@code 226}, ADR-0030): Fabric API 26.3's {@code TagBuilder.remove} next to Forge's ({@link
 * HostAddedMembers}), Balm's soil moisture read where Forge asks {@code isFertile} ({@link HostFertileSoil}), Lithium's
 * mixin of an anonymous class the patch numbered differently ({@link HostRenumberedClasses}), and Lithium's injection in
 * {@code Level.setBlock}, whose part Forge moved to {@code markAndNotifyBlock} ({@link HostBlockChangeNotify}). The
 * classes are built to the shapes {@code javap} shows in Forge 26.2-65.1.5 and 26.3-66.1.0 and in the mods' jars;
 * each bridge also has a case it must not carry.
 */
class HostForgeBridgesTest {

    private static final String CONFIG = "forge.mixins.json";
    private static final String MOD = "fabric-test-mod";
    private static final String HOST = "MinecraftForge 26.3-66.1.0";
    private static final ClassDesc LIST = ClassDesc.of("java.util.List");
    private static final ClassDesc ARRAY_LIST = ClassDesc.of("java.util.ArrayList");
    private static final ClassDesc COLLECTIONS = ClassDesc.of("java.util.Collections");
    private static final ClassDesc OPERATION =
            ClassDesc.ofDescriptor("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;");
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

    @TempDir
    Path directory;

    // --- TagBuilder.remove: the mixin reads the patch's list in place of its own @Unique one
    // ---------------------------

    private static final ClassDesc TAG_BUILDER = ClassDesc.of("t.TagBuilder");
    private static final ClassDesc TAG_ENTRY = ClassDesc.of("t.TagEntry");
    private static final MethodTypeDesc REMOVE = MethodTypeDesc.of(TAG_BUILDER, TAG_ENTRY);

    /**
     * Fabric API 26.3's {@code TagBuilderMixin.remove(TagEntry)} adds to its {@code @Unique} list {@code removals};
     * Forge 26.3 adds the same method, adding to its own {@code removeEntries}. Forge's method stays, and the mixin's
     * {@code getRemovals()} reads {@code removeEntries}: one list that both loaders' code fills and reads.
     */
    @Test
    void aMethodOnAUniqueListThePatchAddedOnItsOwnListSharesThePatchsList() throws IOException {
        byte[] mixin = tagBuilderMixin(true);
        HostPatchAdjustments adjustments = plan(
                Map.of("t/TagBuilder", tagBuilder(false, true)),
                Map.of("t/TagBuilder", tagBuilder(true, true)),
                "m/TagBuilderMixin",
                mixin,
                List.of());

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-212", finding.code(), finding.reason());
        assertFalse(finding.incompatible(), finding.reason());
        assertTrue(finding.reason().contains("removeEntries"), finding.reason());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.TagBuilderMixin", mixin));
        assertTrue(
                applied.methods().stream()
                        .noneMatch(method -> method.methodName().equalsString("remove")),
                "Forge's remove(TagEntry) serves the mixin's callers");
        assertEquals(
                List.of(HostAddedMembers.ALIASED_FIELD_PREFIX + "removals"),
                applied.fields().stream()
                        .map(field -> field.fieldName().stringValue())
                        .toList());
        List<String> read = fields(method(applied, "getRemovals"));
        assertEquals(List.of("GETFIELD t/TagBuilder.removeEntries"), read);
        List<String> constructor = fields(method(applied, "<init>"));
        assertEquals(
                List.of("PUTFIELD m/TagBuilderMixin." + HostAddedMembers.ALIASED_FIELD_PREFIX + "removals"),
                constructor);
        // removeElement still calls remove(TagEntry), now Forge's.
        assertTrue(calls(method(applied, "removeElement")).contains("m/TagBuilderMixin.remove"));
    }

    /** The patch's list is set to something else than a new empty list: reading it in place of the mixin's would not
     * be the same, so the mod stays incompatible. */
    @Test
    void aPatchListThatDoesNotStartEmptyIsNotShared() throws IOException {
        HostPatchAdjustments adjustments = plan(
                Map.of("t/TagBuilder", tagBuilder(false, true)),
                Map.of("t/TagBuilder", tagBuilder(true, false)),
                "m/TagBuilderMixin",
                tagBuilderMixin(true),
                List.of());

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-224", finding.code(), finding.reason());
    }

    /** The mixin's method writes another list than the patch's method reads: not the same code. */
    @Test
    void aMethodWithOtherCodeOnTheListIsNotShared() throws IOException {
        HostPatchAdjustments adjustments = plan(
                Map.of("t/TagBuilder", tagBuilder(false, true)),
                Map.of("t/TagBuilder", tagBuilder(true, true)),
                "m/TagBuilderMixin",
                tagBuilderMixin(false),
                List.of());

        assertEquals("PLEXUS-COMPAT-224", single(adjustments).code());
    }

    /** {@code TagBuilder}: {@code entries}; patched, also {@code removeEntries} and {@code remove(TagEntry)}. */
    private static byte[] tagBuilder(boolean patched, boolean freshList) {
        return ClassFile.of().build(TAG_BUILDER, builder -> {
            builder.withField("entries", LIST, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            if (patched) {
                builder.withField("removeEntries", LIST, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL);
            }
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0).invokespecial(CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                newList(code, TAG_BUILDER, "entries");
                if (patched) {
                    if (freshList) {
                        newList(code, TAG_BUILDER, "removeEntries");
                    } else {
                        code.aload(0)
                                .getfield(TAG_BUILDER, "entries", LIST)
                                .aload(0)
                                .swap()
                                .putfield(TAG_BUILDER, "removeEntries", LIST);
                    }
                }
                code.return_();
            });
            if (patched) {
                builder.withMethodBody(
                        "remove",
                        REMOVE,
                        ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .getfield(TAG_BUILDER, "removeEntries", LIST)
                                .aload(1)
                                .invokeinterface(LIST, "add", MethodTypeDesc.of(CD_boolean, CD_Object))
                                .pop()
                                .aload(0)
                                .areturn());
            }
        });
    }

    /**
     * Fabric API 26.3's {@code TagBuilderMixin}: {@code @Unique final List removals = new ArrayList()}, {@code
     * getRemovals()} (unmodifiable), {@code remove(TagEntry)} (adds, then returns {@code (TagBuilder) this}) and {@code
     * removeElement(TagEntry)} calling it, as {@code javap} shows them. Without {@code adds}, {@code remove} takes the
     * entry out of the list instead ({@code List.remove}), which is other code than Forge's.
     */
    private static byte[] tagBuilderMixin(boolean adds) {
        ClassDesc self = ClassDesc.of("m.TagBuilderMixin");
        return mixin(self, TAG_BUILDER, builder -> {
            builder.withField(
                    "removals",
                    LIST,
                    field -> field.withFlags(ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                            .with(RuntimeVisibleAnnotationsAttribute.of(
                                    Annotation.of(ClassDesc.ofDescriptor(UNIQUE)))));
            builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0).invokespecial(CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                newList(code, self, "removals");
                code.return_();
            });
            builder.withMethodBody(
                    "getRemovals",
                    MethodTypeDesc.of(LIST),
                    ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .getfield(self, "removals", LIST)
                            .invokestatic(COLLECTIONS, "unmodifiableList", MethodTypeDesc.of(LIST, LIST))
                            .areturn());
            builder.withMethodBody(
                    "remove",
                    REMOVE,
                    ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .getfield(self, "removals", LIST)
                            .aload(1)
                            .invokeinterface(LIST, adds ? "add" : "remove", MethodTypeDesc.of(CD_boolean, CD_Object))
                            .pop()
                            .aload(0)
                            .checkcast(TAG_BUILDER)
                            .areturn());
            builder.withMethodBody(
                    "removeElement",
                    REMOVE,
                    ClassFile.ACC_PUBLIC,
                    code -> code.aload(0)
                            .aload(1)
                            .invokevirtual(self, "remove", REMOVE)
                            .areturn());
        });
    }

    private static void newList(CodeBuilder code, ClassDesc owner, String field) {
        code.aload(0)
                .new_(ARRAY_LIST)
                .dup()
                .invokespecial(ARRAY_LIST, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                .putfield(owner, field, LIST);
    }

    // --- CropBlock.getGrowthSpeed: Balm's moisture wrap on Forge's isFertile
    // -------------------------------------------

    private static final ClassDesc CROP_BLOCK = ClassDesc.of("net.minecraft.world.level.block.CropBlock");
    private static final ClassDesc BLOCK = ClassDesc.of("net.minecraft.world.level.block.Block");
    private static final ClassDesc BLOCK_STATE = ClassDesc.of("net.minecraft.world.level.block.state.BlockState");
    private static final ClassDesc BLOCK_GETTER = ClassDesc.of("net.minecraft.world.level.BlockGetter");
    private static final ClassDesc BLOCK_POS = ClassDesc.of("net.minecraft.core.BlockPos");
    private static final ClassDesc PROPERTY = ClassDesc.of("net.minecraft.world.level.block.state.properties.Property");
    private static final ClassDesc INTEGER_PROPERTY =
            ClassDesc.of("net.minecraft.world.level.block.state.properties.IntegerProperty");
    private static final ClassDesc FARMLAND = ClassDesc.of("net.minecraft.world.level.block.FarmlandBlock");
    private static final ClassDesc COMPARABLE = ClassDesc.of("java.lang.Comparable");
    private static final MethodTypeDesc GROWTH_SPEED = MethodTypeDesc.of(CD_float, BLOCK, BLOCK_GETTER, BLOCK_POS);
    private static final MethodTypeDesc MOISTURE_HANDLER =
            MethodTypeDesc.of(COMPARABLE, BLOCK_STATE, PROPERTY, COMPARABLE, OPERATION, BLOCK, BLOCK_GETTER, BLOCK_POS);

    /**
     * Balm's {@code getGrowthSpeedGetMoisture} (static, javap): it makes the read once, keeps the value, and returns
     * either {@code 1} or the value, testing it only with {@code intValue() > 0}. The bridge wraps Forge's {@code
     * isFertile}, gives the handler vanilla's arguments and Forge's answer as 1 or 0, and decides by its result above 0.
     */
    @Test
    void balmsMoistureReadRunsOnForgesFertileCheck() throws IOException {
        byte[] mixin = moistureMixin(true);
        HostPatchAdjustments adjustments = cropPlan(mixin);

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-226", finding.code(), finding.reason());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.CropMixin", mixin));
        assertFalse(hasAnnotation(method(applied, "getGrowthSpeedGetMoisture"), WRAP_OPERATION));
        MethodModel bridge = method(applied, "plexus$hostCall$getGrowthSpeedGetMoisture");
        assertEquals(
                MethodTypeDesc.of(
                                CD_boolean,
                                BLOCK_STATE,
                                BLOCK_GETTER,
                                BLOCK_POS,
                                OPERATION,
                                BLOCK,
                                BLOCK_GETTER,
                                BLOCK_POS)
                        .descriptorString(),
                bridge.methodType().stringValue());
        assertTrue(bridge.flags().has(java.lang.reflect.AccessFlag.STATIC));
        assertTrue(annotationText(bridge).contains(HostFertileSoil.REPLACEMENT), annotationText(bridge));
        List<String> calls = calls(bridge);
        assertTrue(calls.contains("com/llamalad7/mixinextras/injector/wrapoperation/Operation.call"), calls.toString());
        assertTrue(calls.contains("m/CropMixin.getGrowthSpeedGetMoisture"), calls.toString());
        assertTrue(fields(bridge).contains("GETSTATIC net/minecraft/world/level/block/FarmlandBlock.MOISTURE"));
    }

    /** A handler that computes with the moisture (here, adds 1 to it) would see 0 or 1 in place of it: not bridged. */
    @Test
    void aMoistureHandlerThatComputesWithTheValueIsNotBridged() throws IOException {
        HostPatchAdjustments adjustments = cropPlan(moistureMixin(false));

        assertEquals("PLEXUS-COMPAT-224", single(adjustments).code());
    }

    private HostPatchAdjustments cropPlan(byte[] mixin) throws IOException {
        String getValueOrElse = "Lnet/minecraft/world/level/block/state/BlockState;getValueOrElse("
                + "Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)"
                + "Ljava/lang/Comparable;";
        byte[] vanilla = ClassFile.of()
                .build(
                        CROP_BLOCK,
                        builder -> builder.withMethodBody(
                                "getGrowthSpeed",
                                GROWTH_SPEED,
                                ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC,
                                code -> code.aconst_null()
                                        .checkcast(BLOCK_STATE)
                                        .getstatic(FARMLAND, "MOISTURE", INTEGER_PROPERTY)
                                        .iconst_0()
                                        .invokestatic(CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int))
                                        .invokevirtual(
                                                BLOCK_STATE,
                                                "getValueOrElse",
                                                MethodTypeDesc.of(COMPARABLE, PROPERTY, COMPARABLE))
                                        .pop()
                                        .fconst_1()
                                        .freturn()));
        byte[] patched = ClassFile.of()
                .build(
                        CROP_BLOCK,
                        builder -> builder.withMethodBody(
                                "getGrowthSpeed",
                                GROWTH_SPEED,
                                ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC,
                                code -> code.aconst_null()
                                        .checkcast(BLOCK_STATE)
                                        .aload(1)
                                        .aload(2)
                                        .invokevirtual(
                                                BLOCK_STATE,
                                                "isFertile",
                                                MethodTypeDesc.of(CD_boolean, BLOCK_GETTER, BLOCK_POS))
                                        .pop()
                                        .fconst_1()
                                        .freturn()));
        return plan(
                Map.of("net/minecraft/world/level/block/CropBlock", vanilla),
                Map.of("net/minecraft/world/level/block/CropBlock", patched),
                "m/CropMixin",
                mixin,
                List.of(new InjectionPointReplacement(
                        "net/minecraft/world/level/block/CropBlock",
                        "getGrowthSpeed",
                        getValueOrElse,
                        HostFertileSoil.REPLACEMENT)));
    }

    /** Balm's handler shape; without {@code asWritten}, it returns the value plus one instead of the value. */
    private static byte[] moistureMixin(boolean asWritten) {
        String getValueOrElse = "Lnet/minecraft/world/level/block/state/BlockState;getValueOrElse("
                + "Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)"
                + "Ljava/lang/Comparable;";
        return mixin(
                ClassDesc.of("m.CropMixin"),
                CROP_BLOCK,
                builder -> builder.withMethod(
                        "getGrowthSpeedGetMoisture",
                        MOISTURE_HANDLER,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                        method -> {
                            method.with(RuntimeVisibleAnnotationsAttribute.of(
                                    injector(WRAP_OPERATION, "getGrowthSpeed", getValueOrElse)));
                            method.withCode(code -> {
                                code.aload(3)
                                        .iconst_3()
                                        .anewarray(CD_Object)
                                        .dup()
                                        .iconst_0()
                                        .aload(0)
                                        .aastore()
                                        .dup()
                                        .iconst_1()
                                        .aload(1)
                                        .aastore()
                                        .dup()
                                        .iconst_2()
                                        .aload(2)
                                        .aastore()
                                        .invokeinterface(
                                                OPERATION, "call", MethodTypeDesc.of(CD_Object, CD_Object.arrayType()))
                                        .checkcast(COMPARABLE)
                                        .astore(7)
                                        .aload(7)
                                        .checkcast(CD_Integer)
                                        .invokevirtual(CD_Integer, "intValue", MethodTypeDesc.of(CD_int));
                                java.lang.classfile.Label keep = code.newLabel();
                                code.ifgt(keep)
                                        .iconst_1()
                                        .invokestatic(CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int))
                                        .areturn()
                                        .labelBinding(keep);
                                if (asWritten) {
                                    code.aload(7).areturn();
                                } else {
                                    code.aload(7)
                                            .checkcast(CD_Integer)
                                            .invokevirtual(CD_Integer, "intValue", MethodTypeDesc.of(CD_int))
                                            .iconst_1()
                                            .iadd()
                                            .invokestatic(CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int))
                                            .areturn();
                                }
                            });
                        }));
    }

    // --- An anonymous class the patch numbered differently
    // ------------------------------------------------------------

    private static final ClassDesc TAG_TYPE = ClassDesc.of("t.TagType");
    private static final ClassDesc NBT_BUILDER = ClassDesc.of("t.NbtBuilder");
    private static final String NEW_HASH_MAP = "Lt/Maps;newHashMap()Ljava/util/HashMap;";

    /**
     * Lithium's {@code CompoundTagMixin$Type} targets {@code CompoundTag$1}, vanilla's {@code TagType} of compound
     * tags; Forge adds an {@code INBTBuilder} as {@code CompoundTag$1}, so vanilla's class is {@code CompoundTag$2}
     * there. The mixin applies to {@code CompoundTag$2}, and its injection is checked against vanilla's {@code $1}.
     */
    @Test
    void aMixinOfAnAnonymousClassThePatchRenumberedAppliesToItsNewName() throws IOException {
        byte[] mixin = anonymousMixin("t/Outer$1");
        HostPatchAdjustments adjustments = plan(
                Map.of("t/Outer$1", tagType()),
                Map.of("t/Outer$1", nbtBuilder(), "t/Outer$2", tagType()),
                "m/TypeMixin",
                mixin,
                List.of());

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-226", finding.code(), finding.reason());
        assertTrue(finding.reason().contains("t/Outer$2"), finding.reason());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.TypeMixin", mixin));
        assertEquals(List.of("t/Outer$2"), HostPatchAdjustments.mixinTargets(applied));
    }

    /** Without a renumbering (the patched class of that name is vanilla's), nothing changes. */
    @Test
    void aMixinOfAnAnonymousClassThePatchKeptIsLeftAsItIs() throws IOException {
        byte[] mixin = anonymousMixin("t/Outer$1");
        HostPatchAdjustments adjustments = plan(
                Map.of("t/Outer$1", tagType()),
                Map.of("t/Outer$1", tagType(), "t/Outer$2", nbtBuilder()),
                "m/TypeMixin",
                mixin,
                List.of());

        assertEquals(List.of(), adjustments.findings());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.TypeMixin", mixin));
        assertEquals(List.of("t/Outer$1"), HostPatchAdjustments.mixinTargets(applied));
    }

    /** Two patched classes look like vanilla's: which one the mixin means is unknown, so it is not renumbered. */
    @Test
    void anAmbiguousRenumberingIsNotGuessed() throws IOException {
        byte[] mixin = anonymousMixin("t/Outer$1");
        HostPatchAdjustments adjustments = plan(
                Map.of("t/Outer$1", tagType()),
                Map.of("t/Outer$1", nbtBuilder(), "t/Outer$2", tagType(), "t/Outer$3", tagType()),
                "m/TypeMixin",
                mixin,
                List.of());

        assertEquals("PLEXUS-COMPAT-224", single(adjustments).code());
    }

    private static byte[] tagType() {
        return ClassFile.of()
                .build(
                        ClassDesc.ofInternalName("t/Outer$1"),
                        builder -> builder.withInterfaceSymbols(TAG_TYPE)
                                .withMethodBody(
                                        "loadCompound",
                                        MethodTypeDesc.of(CD_Object),
                                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                                        code -> code.invokestatic(
                                                        ClassDesc.of("t.Maps"),
                                                        "newHashMap",
                                                        MethodTypeDesc.of(ClassDesc.of("java.util.HashMap")))
                                                .areturn()));
    }

    private static byte[] nbtBuilder() {
        return ClassFile.of()
                .build(ClassDesc.ofInternalName("t/Outer$1"), builder -> builder.withInterfaceSymbols(NBT_BUILDER));
    }

    /** Lithium's {@code @ModifyVariable} into {@code loadCompound} at {@code INVOKE_ASSIGN Maps.newHashMap()}. */
    private static byte[] anonymousMixin(String target) {
        return ClassFile.of().build(ClassDesc.of("m.TypeMixin"), builder -> {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(
                    ClassDesc.ofDescriptor("Lorg/spongepowered/asm/mixin/Mixin;"),
                    AnnotationElement.of("targets", AnnotationValue.ofArray(AnnotationValue.ofString(target))))));
            builder.withMethod(
                    "useFasterCollection",
                    MethodTypeDesc.of(ClassDesc.of("java.util.Map"), ClassDesc.of("java.util.Map")),
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC,
                    method -> {
                        method.with(RuntimeVisibleAnnotationsAttribute.of(Annotation.of(
                                ClassDesc.ofDescriptor(MODIFY_VARIABLE),
                                AnnotationElement.of(
                                        "method", AnnotationValue.ofArray(AnnotationValue.ofString("loadCompound"))),
                                AnnotationElement.of(
                                        "at",
                                        AnnotationValue.ofArray(AnnotationValue.ofAnnotation(Annotation.of(
                                                ClassDesc.ofDescriptor("Lorg/spongepowered/asm/mixin/injection/At;"),
                                                AnnotationElement.ofString("value", "INVOKE_ASSIGN"),
                                                AnnotationElement.ofString("target", NEW_HASH_MAP))))))));
                        method.withCode(code -> code.aload(0).areturn());
                    });
        });
    }

    // --- Level.setBlock: Lithium's injection where Forge moved the body into markAndNotifyBlock
    // ------------------------

    private static final ClassDesc LEVEL = ClassDesc.of("net.minecraft.world.level.Level");
    private static final ClassDesc LEVEL_CHUNK = ClassDesc.of("net.minecraft.world.level.chunk.LevelChunk");
    private static final ClassDesc CALLBACK_INFO_RETURNABLE =
            ClassDesc.of("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable");
    private static final MethodTypeDesc SET_BLOCK =
            MethodTypeDesc.of(CD_boolean, BLOCK_POS, BLOCK_STATE, CD_int, CD_int);
    private static final MethodTypeDesc NOTIFY =
            MethodTypeDesc.of(CD_void, BLOCK_POS, LEVEL_CHUNK, BLOCK_STATE, BLOCK_STATE, CD_int, CD_int);
    private static final MethodTypeDesc UPDATE_POI = MethodTypeDesc.of(CD_void, BLOCK_POS, BLOCK_STATE, BLOCK_STATE);
    private static final MethodTypeDesc HOPPER_HANDLER = MethodTypeDesc.of(
            CD_void,
            BLOCK_POS,
            BLOCK_STATE,
            CD_int,
            CD_int,
            CALLBACK_INFO_RETURNABLE,
            LEVEL_CHUNK,
            BLOCK,
            BLOCK_STATE,
            BLOCK_STATE);

    /**
     * Lithium's {@code updateHopperOnUpdateSuppression}: {@code setBlock}'s parameters, its {@code
     * CallbackInfoReturnable} (unused), and {@code @Local} chunk, block, {@code BlockState} ordinals 1 and 2. On Forge it
     * runs from an {@code @Inject} at the same call in {@code markAndNotifyBlock}, with the same values.
     */
    @Test
    void lithiumsSetBlockInjectionRunsInForgesMarkAndNotifyBlock() throws IOException {
        byte[] mixin = hopperMixin(false);
        HostPatchAdjustments adjustments = levelPlan(mixin);

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-226", finding.code(), finding.reason());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.LevelMixin", mixin));
        assertFalse(hasAnnotation(method(applied, "updateHopperOnUpdateSuppression"), INJECT));
        MethodModel bridge = method(applied, "plexus$hostCall$updateHopperOnUpdateSuppression");
        assertTrue(hasAnnotation(bridge, INJECT));
        assertTrue(annotationText(bridge).contains(HostBlockChangeNotify.REPLACEMENT), annotationText(bridge));
        List<List<Annotation>> parameters = bridge.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .orElseThrow()
                .parameterAnnotations();
        assertEquals(8, parameters.size());
        assertEquals(LOCAL, parameters.get(7).getFirst().className().stringValue());
        assertTrue(calls(bridge).contains("m/LevelMixin.updateHopperOnUpdateSuppression"));
    }

    /** A handler that reads its {@code CallbackInfoReturnable} cannot run where there is none: not bridged. */
    @Test
    void aSetBlockHandlerThatUsesItsCallbackIsNotBridged() throws IOException {
        assertEquals("PLEXUS-COMPAT-224", single(levelPlan(hopperMixin(true))).code());
    }

    private HostPatchAdjustments levelPlan(byte[] mixin) throws IOException {
        String level = "net/minecraft/world/level/Level";
        byte[] vanilla = ClassFile.of()
                .build(
                        LEVEL,
                        builder -> builder.withMethodBody("setBlock", SET_BLOCK, ClassFile.ACC_PUBLIC, code -> {
                            code.aload(0)
                                    .aload(1)
                                    .invokevirtual(LEVEL, "getChunkAt", MethodTypeDesc.of(LEVEL_CHUNK, BLOCK_POS));
                            code.astore(5);
                            code.aload(2)
                                    .invokevirtual(BLOCK_STATE, "getBlock", MethodTypeDesc.of(BLOCK))
                                    .astore(6);
                            code.aload(5)
                                    .aload(1)
                                    .aload(2)
                                    .iload(3)
                                    .invokevirtual(
                                            LEVEL_CHUNK,
                                            "setBlockState",
                                            MethodTypeDesc.of(BLOCK_STATE, BLOCK_POS, BLOCK_STATE, CD_int))
                                    .astore(7);
                            code.aload(0)
                                    .aload(1)
                                    .invokevirtual(LEVEL, "getBlockState", MethodTypeDesc.of(BLOCK_STATE, BLOCK_POS));
                            code.astore(8);
                            code.aload(0)
                                    .aload(1)
                                    .aload(7)
                                    .aload(8)
                                    .invokevirtual(LEVEL, "updatePOIOnBlockStateChange", UPDATE_POI);
                            code.iconst_1().ireturn();
                        }));
        byte[] patched = ClassFile.of().build(LEVEL, builder -> {
            builder.withMethodBody("setBlock", SET_BLOCK, ClassFile.ACC_PUBLIC, code -> {
                code.aload(0).aload(1).invokevirtual(LEVEL, "getChunkAt", MethodTypeDesc.of(LEVEL_CHUNK, BLOCK_POS));
                code.astore(5);
                code.aload(2)
                        .invokevirtual(BLOCK_STATE, "getBlock", MethodTypeDesc.of(BLOCK))
                        .astore(6);
                code.aload(5)
                        .aload(1)
                        .aload(2)
                        .iload(3)
                        .invokevirtual(
                                LEVEL_CHUNK,
                                "setBlockState",
                                MethodTypeDesc.of(BLOCK_STATE, BLOCK_POS, BLOCK_STATE, CD_int))
                        .astore(8);
                code.aload(0).aload(1).aload(5).aload(8).aload(2).iload(3).iload(4);
                code.invokevirtual(LEVEL, "markAndNotifyBlock", NOTIFY);
                code.iconst_1().ireturn();
            });
            builder.withMethodBody("markAndNotifyBlock", NOTIFY, ClassFile.ACC_PUBLIC, code -> {
                code.aload(4)
                        .invokevirtual(BLOCK_STATE, "getBlock", MethodTypeDesc.of(BLOCK))
                        .astore(7);
                code.aload(0).aload(1).invokevirtual(LEVEL, "getBlockState", MethodTypeDesc.of(BLOCK_STATE, BLOCK_POS));
                code.astore(8);
                code.aload(0)
                        .aload(1)
                        .aload(3)
                        .aload(8)
                        .invokevirtual(LEVEL, "updatePOIOnBlockStateChange", UPDATE_POI);
                code.return_();
            });
        });
        return plan(Map.of(level, vanilla), Map.of(level, patched), "m/LevelMixin", mixin, List.of());
    }

    /** Lithium's handler; with {@code readsCallback}, it also reads its {@code CallbackInfoReturnable}. */
    private static byte[] hopperMixin(boolean readsCallback) {
        String target = "Lnet/minecraft/world/level/Level;updatePOIOnBlockStateChange("
                + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/block/state/BlockState;)V";
        return mixin(
                ClassDesc.of("m.LevelMixin"),
                LEVEL,
                builder -> builder.withMethod(
                        "updateHopperOnUpdateSuppression", HOPPER_HANDLER, ClassFile.ACC_PRIVATE, method -> {
                            method.with(RuntimeVisibleAnnotationsAttribute.of(injector(
                                    INJECT,
                                    "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
                                    target)));
                            List<List<Annotation>> parameters = new ArrayList<>();
                            for (int index = 0; index < 5; index++) {
                                parameters.add(List.of());
                            }
                            parameters.add(List.of(Annotation.of(ClassDesc.ofDescriptor(LOCAL))));
                            parameters.add(List.of(Annotation.of(ClassDesc.ofDescriptor(LOCAL))));
                            parameters.add(List.of(Annotation.of(
                                    ClassDesc.ofDescriptor(LOCAL), AnnotationElement.ofInt("ordinal", 1))));
                            parameters.add(List.of(Annotation.of(
                                    ClassDesc.ofDescriptor(LOCAL), AnnotationElement.ofInt("ordinal", 2))));
                            method.with(RuntimeInvisibleParameterAnnotationsAttribute.of(parameters));
                            method.withCode(code -> {
                                if (readsCallback) {
                                    code.aload(5).pop();
                                }
                                code.aload(6).pop().return_();
                            });
                        }));
    }

    // --- Helpers
    // -------------------------------------------------------------------------------------------------------

    // --- Gui.extractRenderState: a screen call the patch makes through a static helper with an argument of its own ---

    private static final ClassDesc GUI = ClassDesc.of("t.Gui");
    private static final ClassDesc SCREEN = ClassDesc.of("t.Screen");
    private static final ClassDesc HOOKS = ClassDesc.of("t.Hooks");
    private static final ClassDesc STACK = ClassDesc.of("java.util.Stack");
    private static final String SCREEN_DRAW = "Lt/Screen;draw(I)V";
    private static final String HOOKS_DRAW = "Lt/Hooks;draw(Lt/Screen;Ljava/util/Stack;I)V";

    /**
     * NeoForge calls {@code ClientHooks.extractScreen(screen, screenLayers, graphics, ...)} where vanilla calls {@code
     * screen.extractRenderStateWithTooltipAndSubtitles(graphics, ...)}, and FancyMenu wraps the vanilla call, making it
     * once with its own arguments. The wrap runs on the host's call through a bridge that passes the handler the
     * receiver, the vanilla arguments and an {@link InsertedArguments} holding the host's own argument.
     */
    @Test
    void aWrapOfACallThePatchMakesThroughAStaticHelperRunsOnTheHelper() throws IOException {
        byte[] mixin = screenMixin(1);
        HostPatchAdjustments adjustments = screenPlan(mixin);

        HostPatchAdjustments.Finding finding = single(adjustments);
        assertEquals("PLEXUS-COMPAT-226", finding.code(), finding.reason());
        assertFalse(finding.incompatible(), finding.reason());
        ClassModel applied = ClassFile.of().parse(adjustments.apply("m.GuiMixin", mixin));
        assertFalse(hasAnnotation(method(applied, "wrapDraw"), WRAP_OPERATION));
        MethodModel bridge = method(applied, HostStaticCalls.PREFIX + "wrapDraw");
        assertEquals(MethodTypeDesc.of(CD_void, SCREEN, STACK, CD_int, OPERATION), bridge.methodTypeSymbol());
        assertTrue(annotationText(bridge).contains(HOOKS_DRAW), annotationText(bridge));
        assertEquals(
                List.of("dev/plexusmodloader/compat/fabric/mixin/InsertedArguments.<init>", "m/GuiMixin.wrapDraw"),
                calls(bridge));
    }

    /** A handler that makes the wrapped call twice would make the host's call twice: not bridged. */
    @Test
    void aWrapThatMakesTheCallTwiceDoesNotRunOnTheStaticHelper() throws IOException {
        HostPatchAdjustments.Finding finding = single(screenPlan(screenMixin(2)));
        assertEquals("PLEXUS-COMPAT-224", finding.code(), finding.reason());
        assertTrue(finding.incompatible(), finding.reason());
    }

    /** The receiver, then the host's own arguments, then the vanilla ones, as the helper takes them. */
    @Test
    void insertedArgumentsPutTheHostsArgumentsAfterTheReceiver() {
        List<Object[]> made = new ArrayList<>();
        InsertedArguments operation = new InsertedArguments(
                arguments -> {
                    made.add(arguments);
                    return "made";
                },
                new Object[] {"layers"});
        assertEquals("made", operation.call("screen", 1, 2.0F));
        assertEquals(List.of("screen", "layers", 1, 2.0F), List.of(made.getFirst()));
    }

    private HostPatchAdjustments screenPlan(byte[] mixin) throws IOException {
        MethodTypeDesc render = MethodTypeDesc.of(CD_void);
        byte[] vanilla = ClassFile.of()
                .build(
                        GUI,
                        builder -> builder.withMethodBody(
                                "render",
                                render,
                                ClassFile.ACC_PUBLIC,
                                code -> code.aconst_null()
                                        .checkcast(SCREEN)
                                        .iconst_1()
                                        .invokevirtual(SCREEN, "draw", MethodTypeDesc.of(CD_void, CD_int))
                                        .return_()));
        byte[] patched = ClassFile.of()
                .build(
                        GUI,
                        builder -> builder.withMethodBody(
                                "render",
                                render,
                                ClassFile.ACC_PUBLIC,
                                code -> code.aconst_null()
                                        .checkcast(SCREEN)
                                        .aconst_null()
                                        .checkcast(STACK)
                                        .iconst_1()
                                        .invokestatic(HOOKS, "draw", MethodTypeDesc.of(CD_void, SCREEN, STACK, CD_int))
                                        .return_()));
        return plan(
                Map.of("t/Gui", vanilla),
                Map.of("t/Gui", patched),
                "m/GuiMixin",
                mixin,
                List.of(new InjectionPointReplacement("t/Gui", "render", SCREEN_DRAW, HOOKS_DRAW)));
    }

    /** {@code wrapDraw(Screen, int, Operation)}, which calls the {@code Operation} {@code times} times as given. */
    private static byte[] screenMixin(int times) {
        return mixin(
                ClassDesc.of("m.GuiMixin"),
                GUI,
                builder -> builder.withMethod(
                        "wrapDraw",
                        MethodTypeDesc.of(CD_void, SCREEN, CD_int, OPERATION),
                        ClassFile.ACC_PRIVATE,
                        method -> {
                            method.with(RuntimeVisibleAnnotationsAttribute.of(
                                    injector(WRAP_OPERATION, "render", SCREEN_DRAW)));
                            method.withCode(code -> {
                                for (int call = 0; call < times; call++) {
                                    code.aload(3)
                                            .iconst_2()
                                            .anewarray(CD_Object)
                                            .dup()
                                            .iconst_0()
                                            .aload(1)
                                            .aastore()
                                            .dup()
                                            .iconst_1()
                                            .iload(2)
                                            .invokestatic(CD_Integer, "valueOf", MethodTypeDesc.of(CD_Integer, CD_int))
                                            .aastore()
                                            .invokeinterface(
                                                    OPERATION,
                                                    "call",
                                                    MethodTypeDesc.of(CD_Object, CD_Object.arrayType()))
                                            .pop();
                                }
                                code.return_();
                            });
                        }));
    }

    private static Annotation injector(String type, String method, String target) {
        return Annotation.of(
                ClassDesc.ofDescriptor(type),
                AnnotationElement.of("method", AnnotationValue.ofArray(AnnotationValue.ofString(method))),
                AnnotationElement.of(
                        "at",
                        AnnotationValue.ofArray(AnnotationValue.ofAnnotation(Annotation.of(
                                ClassDesc.ofDescriptor("Lorg/spongepowered/asm/mixin/injection/At;"),
                                AnnotationElement.ofString("value", "INVOKE"),
                                AnnotationElement.ofString("target", target))))));
    }

    private static byte[] mixin(ClassDesc name, ClassDesc target, Consumer<ClassBuilder> members) {
        return ClassFile.of().build(name, builder -> {
            builder.with(RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(
                    ClassDesc.ofDescriptor("Lorg/spongepowered/asm/mixin/Mixin;"),
                    AnnotationElement.of("value", AnnotationValue.ofArray(AnnotationValue.ofClass(target))))));
            members.accept(builder);
        });
    }

    private HostPatchAdjustments plan(
            Map<String, byte[]> vanilla,
            Map<String, byte[]> patched,
            String mixinName,
            byte[] mixin,
            List<InjectionPointReplacement> replacements)
            throws IOException {
        String simpleName = mixinName.substring(mixinName.lastIndexOf('/') + 1);
        String config = "{\"required\": true, \"package\": \"m\", \"mixins\": [\"" + simpleName + "\"]}";
        Path jar = directory.resolve(simpleName + ".jar");
        try (OutputStream file = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(CONFIG));
            out.write(config.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry(mixinName + ".class"));
            out.write(mixin);
            out.closeEntry();
        }
        return HostPatchAdjustments.plan(
                new HostedLaunch(HOST, List.of(), replacements, directory),
                GameSide.DEDICATED_SERVER,
                Map.of(CONFIG, MOD),
                Map.of(MOD, jar),
                className -> Optional.ofNullable(vanilla.get(className.replace('.', '/'))),
                new GameClassBytes() {
                    @Override
                    public Optional<byte[]> classBytes(String className) {
                        return Optional.ofNullable(patched.get(className.replace('.', '/')));
                    }

                    @Override
                    public boolean isDefined(String className) {
                        return patched.containsKey(className.replace('.', '/'));
                    }
                });
    }

    private static HostPatchAdjustments.Finding single(HostPatchAdjustments adjustments) {
        assertEquals(1, adjustments.findings().size(), adjustments.findings().toString());
        return adjustments.findings().getFirst();
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in "
                        + model.methods().stream()
                                .map(method -> method.methodName().stringValue())
                                .toList()));
    }

    private static boolean hasAnnotation(MethodModel method, String type) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                .flatMap(attribute -> attribute.annotations().stream())
                .anyMatch(annotation -> annotation.className().equalsString(type));
    }

    private static String annotationText(MethodModel method) {
        return method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attribute -> attribute.annotations().toString())
                .orElse("");
    }

    private static List<String> calls(MethodModel method) {
        List<String> calls = new ArrayList<>();
        for (CodeElement element : method.code().orElseThrow()) {
            if (element instanceof InvokeInstruction invoke) {
                calls.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue());
            }
        }
        return calls;
    }

    private static List<String> fields(MethodModel method) {
        List<String> fields = new ArrayList<>();
        for (CodeElement element : method.code().orElseThrow()) {
            if (element instanceof FieldInstruction field) {
                fields.add(field.opcode() + " " + field.owner().asInternalName() + "."
                        + field.name().stringValue());
            }
        }
        return fields;
    }
}
