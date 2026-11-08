package dev.plexusmodloader.compat.fabric;

import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Forge 1.20.x's recompiled enums get the constructor access of the obfuscated vanilla class back. */
class HostEnumConstructorsTest {

    private static final String NAME = "net/minecraft/world/item/enchantment/EnchantmentCategory";
    private static final MethodTypeDesc CONSTRUCTOR = MethodTypeDesc.of(CD_void, CD_String, CD_int);
    private static final MethodTypeDesc OTHER = MethodTypeDesc.of(CD_void, CD_String, CD_int, CD_int);

    @Test
    void anEnumConstructorThePatchMadePrivateIsPackagePrivateAgain() {
        List<String> reports = new ArrayList<>();
        HostEnumConstructors enums = new HostEnumConstructors(HostEnumConstructorsTest::vanilla, reports::add);
        byte[] patched = enumClass(NAME, ClassFile.ACC_ENUM, ClassFile.ACC_PRIVATE, ClassFile.ACC_PRIVATE);

        byte[] opened = enums.apply(NAME, patched);
        enums.apply(NAME, patched);

        MethodModel constructor = constructor(opened, CONSTRUCTOR);
        assertFalse(constructor.flags().has(AccessFlag.PRIVATE));
        assertFalse(constructor.flags().has(AccessFlag.PUBLIC));
        assertTrue(constructor(opened, OTHER).flags().has(AccessFlag.PRIVATE), "vanilla has it private too");
        assertEquals(1, reports.size(), reports.toString());
        assertTrue(reports.getFirst().contains("(Ljava/lang/String;I)V"), reports.toString());
    }

    @Test
    void classesThatAreNotEnumsOrNotTheGamesAreLeftAsTheyAre() {
        List<String> reports = new ArrayList<>();
        HostEnumConstructors enums = new HostEnumConstructors(HostEnumConstructorsTest::vanilla, reports::add);
        byte[] notEnum = enumClass(NAME, 0, ClassFile.ACC_PRIVATE, ClassFile.ACC_PRIVATE);
        byte[] modClass = enumClass("m/Category", ClassFile.ACC_ENUM, ClassFile.ACC_PRIVATE, ClassFile.ACC_PRIVATE);

        assertArrayEquals(notEnum, enums.apply(NAME, notEnum));
        assertArrayEquals(modClass, enums.apply("m/Category", modClass));
        assertEquals(List.of(), reports);
    }

    /** The vanilla game: the class, with the first constructor package-private. */
    private static Optional<byte[]> vanilla(String internalName) {
        return internalName.equals(NAME)
                ? Optional.of(enumClass(NAME, ClassFile.ACC_ENUM, 0, ClassFile.ACC_PRIVATE))
                : Optional.empty();
    }

    /** An enum-like class with the two constructors, flagged {@code constructorFlags} and {@code otherFlags}. */
    private static byte[] enumClass(String name, int classFlags, int constructorFlags, int otherFlags) {
        ClassDesc self = ClassDesc.ofInternalName(name);
        return ClassFile.of().build(self, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT | classFlags);
            builder.withSuperclass(ClassDesc.of("java.lang.Enum"));
            builder.withMethodBody(
                    "<init>",
                    CONSTRUCTOR,
                    constructorFlags,
                    code -> code.aload(0)
                            .aload(1)
                            .iload(2)
                            .invokespecial(ClassDesc.of("java.lang.Enum"), "<init>", CONSTRUCTOR)
                            .return_());
            builder.withMethodBody(
                    "<init>",
                    OTHER,
                    otherFlags,
                    code -> code.aload(0)
                            .aload(1)
                            .iload(2)
                            .invokespecial(self, "<init>", CONSTRUCTOR)
                            .return_());
        });
    }

    private static MethodModel constructor(byte[] bytes, MethodTypeDesc type) {
        return ClassFile.of().parse(bytes).methods().stream()
                .filter(method -> method.methodName().equalsString("<init>")
                        && method.methodTypeSymbol().equals(type))
                .findFirst()
                .orElseThrow();
    }
}
