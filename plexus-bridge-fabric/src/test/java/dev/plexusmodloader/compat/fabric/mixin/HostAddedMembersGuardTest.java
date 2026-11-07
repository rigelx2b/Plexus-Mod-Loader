package dev.plexusmodloader.compat.fabric.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A method the host's patch added behind a guard on its own field, where the mixin adds the guard's fallback (NeoForge
 * 26.1's {@code Ingredient.hashCode()} and Fabric API's {@code IngredientMixin.hashCode()}).
 */
class HostAddedMembersGuardTest {

    private static final ClassFile FILES = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
    private static final ClassDesc TARGET = ClassDesc.of("test.Ingredient");
    private static final ClassDesc MIXIN = ClassDesc.of("test.IngredientMixin");
    private static final ClassDesc VALUES = ClassDesc.of("test.HolderSet");
    private static final ClassDesc CUSTOM = ClassDesc.of("test.ICustomIngredient");
    private static final MethodTypeDesc HASH = MethodTypeDesc.of(ConstantDescs.CD_int);

    private static ClassModel type(ClassDesc name, boolean withCustom, Consumer<CodeBuilder> hashCode) {
        byte[] bytes = FILES.build(name, builder -> {
            builder.withField("values", VALUES, ClassFile.ACC_PRIVATE);
            if (withCustom) {
                builder.withField("customIngredient", CUSTOM, ClassFile.ACC_PRIVATE);
            }
            if (hashCode != null) {
                builder.withMethodBody("hashCode", HASH, ClassFile.ACC_PUBLIC, hashCode);
            }
        });
        return FILES.parse(bytes);
    }

    private static MethodModel hashCode(ClassModel type) {
        return type.methods().stream()
                .filter(method -> method.methodName().equalsString("hashCode"))
                .findFirst()
                .orElseThrow();
    }

    private static void valuesHash(CodeBuilder code, ClassDesc owner) {
        code.aload(0)
                .getfield(owner, "values", VALUES)
                .invokeinterface(VALUES, "hashCode", HASH)
                .ireturn();
    }

    /** NeoForge's shape, calling {@code delegate} on the custom ingredient. */
    private static Consumer<CodeBuilder> guarded(String delegate) {
        return code -> {
            var fallback = code.newLabel();
            code.aload(0)
                    .getfield(TARGET, "customIngredient", CUSTOM)
                    .ifnull(fallback)
                    .aload(0)
                    .getfield(TARGET, "customIngredient", CUSTOM)
                    .invokeinterface(CUSTOM, delegate, HASH)
                    .ireturn()
                    .labelBinding(fallback);
            valuesHash(code, TARGET);
        };
    }

    @Test
    void keepsThePatchedMethodWhenItRunsTheMixinsCodeBehindAGuardOnItsOwnField() {
        ClassModel vanilla = type(TARGET, false, null);
        MethodModel patched = hashCode(type(TARGET, true, guarded("hashCode")));
        MethodModel mixin = hashCode(type(MIXIN, false, code -> valuesHash(code, MIXIN)));

        assertTrue(!mixin.flags().has(AccessFlag.STATIC));
        assertEquals(
                Optional.of("customIngredient"),
                HostAddedMembers.guardedByAddedField(
                        mixin, "test/IngredientMixin", patched, "test/Ingredient", vanilla));
    }

    @Test
    void doesNotWhenTheGuardedFieldIsVanillasOrTheGuardCallsSomethingElseOrTheFallbackDiffers() {
        ClassModel vanilla = type(TARGET, false, null);
        ClassModel vanillaWithField = type(TARGET, true, null);
        MethodModel patched = hashCode(type(TARGET, true, guarded("hashCode")));
        MethodModel otherDelegate = hashCode(type(TARGET, true, guarded("identity")));
        MethodModel mixin = hashCode(type(MIXIN, false, code -> valuesHash(code, MIXIN)));
        MethodModel otherMixin =
                hashCode(type(MIXIN, false, code -> code.iconst_1().ireturn()));

        assertEquals(
                Optional.empty(),
                HostAddedMembers.guardedByAddedField(
                        mixin, "test/IngredientMixin", patched, "test/Ingredient", vanillaWithField));
        assertEquals(
                Optional.empty(),
                HostAddedMembers.guardedByAddedField(
                        mixin, "test/IngredientMixin", otherDelegate, "test/Ingredient", vanilla));
        assertEquals(
                Optional.empty(),
                HostAddedMembers.guardedByAddedField(
                        otherMixin, "test/IngredientMixin", patched, "test/Ingredient", vanilla));
    }
}
