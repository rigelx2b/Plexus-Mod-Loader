package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Forge on NeoForge, F-N5: overrides of Forge extension methods, on made-up class files shaped like Forge's {@code
 * IForgeItem}, NeoForge's {@code IItemExtension} and the game's {@code Item}.
 */
class ForgeOnNeoForgeExtensionsTest {

    private static final String FORGE_ITEM = "net/minecraftforge/common/extensions/IForgeItem";
    private static final String FORGE_FLUID = "net/minecraftforge/common/extensions/IForgeFluid";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String FLUID = "net/minecraft/world/level/material/Fluid";
    private static final String ITEM_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";
    private static final String BOTH = "getEntityLifespan(I)I";
    private static final String FORGE_ONLY = "getBurnTime(I)I";
    private static final MethodTypeDesc INT_TO_INT = MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int);

    @TempDir
    Path folder;

    @Test
    void anExtensionsGameClassComesFromSelfOrTheListOfInterfacesWithoutIt() throws IOException {
        Path forge = forgeUniversal();
        List<ForgeOnNeoForgeExtensions.Extension> extensions = ForgeOnNeoForgeExtensions.extensions(forge);
        assertEquals(
                List.of(
                        new ForgeOnNeoForgeExtensions.Extension(FORGE_FLUID, Optional.of(FLUID), Set.of(FORGE_ONLY)),
                        new ForgeOnNeoForgeExtensions.Extension(
                                FORGE_ITEM, Optional.of(ITEM), Set.of(BOTH, FORGE_ONLY))),
                extensions);
    }

    /**
     * A mod item overriding a method NeoForge's extension has too is an override there; one overriding a Forge-only
     * method is named. A mixin, and a class that does not extend the game class, are not checked.
     */
    @Test
    void onlyOverridesTheGameDoesNotHaveAreNamed() throws IOException {
        List<ForgeOnNeoForgeExtensions.Extension> extensions = ForgeOnNeoForgeExtensions.extensions(forgeUniversal());
        Map<String, byte[]> game = new LinkedHashMap<>();
        game.put(ITEM_EXTENSION, type(ITEM_EXTENSION, null, List.of(), true, BOTH));
        game.put(ITEM, type(ITEM, ConstantDescs.CD_Object, List.of(ITEM_EXTENSION), false));
        Path mod = jar(
                "mod.jar",
                Map.of(
                        "example/ProbeItem",
                        type("example/ProbeItem", ClassDesc.ofInternalName(ITEM), List.of(), false, BOTH, FORGE_ONLY),
                        "example/SubItem",
                        type(
                                "example/SubItem",
                                ClassDesc.ofInternalName("example/ProbeItem"),
                                List.of(),
                                false,
                                FORGE_ONLY),
                        "example/Unrelated",
                        type("example/Unrelated", ConstantDescs.CD_Object, List.of(), false, FORGE_ONLY),
                        "example/ItemMixin",
                        mixin("example/ItemMixin", FORGE_ONLY)));

        List<ForgeOnNeoForgeExtensions.Unfit> unfit =
                ForgeOnNeoForgeExtensions.unfit(mod, extensions, name -> Optional.ofNullable(game.get(name)));

        assertEquals(
                List.of(
                        new ForgeOnNeoForgeExtensions.Unfit("example/ProbeItem", FORGE_ONLY, FORGE_ITEM, ITEM),
                        new ForgeOnNeoForgeExtensions.Unfit("example/SubItem", FORGE_ONLY, FORGE_ITEM, ITEM)),
                unfit);
        assertEquals(
                "ProbeItem.getBurnTime overrides IForgeItem.getBurnTime(I)I, which only MinecraftForge's patch adds to"
                        + " Item; NeoForge's game does not call it",
                unfit.getFirst().toString());
    }

    /** The game class itself declaring the method (vanilla or NeoForge's patch) makes the override one. */
    @Test
    void aMethodTheGameClassDeclaresIsAnOverride() throws IOException {
        List<ForgeOnNeoForgeExtensions.Extension> extensions = ForgeOnNeoForgeExtensions.extensions(forgeUniversal());
        Map<String, byte[]> game = Map.of(ITEM, type(ITEM, ConstantDescs.CD_Object, List.of(), false, FORGE_ONLY));
        Path mod = jar(
                "declared.jar",
                Map.of(
                        "example/ProbeItem",
                        type("example/ProbeItem", ClassDesc.ofInternalName(ITEM), List.of(), false, FORGE_ONLY)));
        assertEquals(
                List.of(),
                ForgeOnNeoForgeExtensions.unfit(mod, extensions, name -> Optional.ofNullable(game.get(name))));
    }

    private Path forgeUniversal() throws IOException {
        ClassDesc item = ClassDesc.ofInternalName(ITEM);
        byte[] forgeItem = ClassFile.of().build(ClassDesc.ofInternalName(FORGE_ITEM), type -> {
            type.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT);
            type.withMethodBody("self", MethodTypeDesc.of(item), ClassFile.ACC_PRIVATE, code -> {
                code.aload(0);
                code.checkcast(item);
                code.areturn();
            });
            defaults(type, BOTH, FORGE_ONLY);
        });
        byte[] forgeFluid = ClassFile.of().build(ClassDesc.ofInternalName(FORGE_FLUID), type -> {
            type.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT);
            defaults(type, FORGE_ONLY);
        });
        byte[] nested = ClassFile.of().build(ClassDesc.ofInternalName(FORGE_ITEM + "$Nested"), type -> {
            type.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT);
            defaults(type, FORGE_ONLY);
        });
        return jar(
                "forge-universal.jar",
                Map.of(FORGE_ITEM, forgeItem, FORGE_FLUID, forgeFluid, FORGE_ITEM + "$Nested", nested));
    }

    private static void defaults(ClassBuilder type, String... methods) {
        for (String method : methods) {
            type.withMethodBody(method.substring(0, method.indexOf('(')), INT_TO_INT, ClassFile.ACC_PUBLIC, code -> {
                code.iload(1);
                code.ireturn();
            });
        }
    }

    private static byte[] type(
            String name, ClassDesc superclass, List<String> interfaces, boolean isInterface, String... methods) {
        return ClassFile.of().build(ClassDesc.ofInternalName(name), type -> {
            if (isInterface) {
                type.withFlags(AccessFlag.PUBLIC, AccessFlag.INTERFACE, AccessFlag.ABSTRACT);
            } else {
                type.withFlags(AccessFlag.PUBLIC);
                type.withSuperclass(superclass);
            }
            type.withInterfaceSymbols(
                    interfaces.stream().map(ClassDesc::ofInternalName).toList());
            defaults(type, methods);
        });
    }

    private static byte[] mixin(String name, String... methods) {
        return ClassFile.of().build(ClassDesc.ofInternalName(name), type -> {
            type.withFlags(AccessFlag.PUBLIC);
            type.withSuperclass(ClassDesc.ofInternalName(ITEM));
            type.with(RuntimeInvisibleAnnotationsAttribute.of(
                    java.lang.classfile.Annotation.of(ClassDesc.of("org.spongepowered.asm.mixin.Mixin"))));
            defaults(type, methods);
        });
    }

    private Path jar(String name, Map<String, byte[]> classes) throws IOException {
        Path jar = folder.resolve(name);
        try (OutputStream output = Files.newOutputStream(jar);
                JarOutputStream out = new JarOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }
}
