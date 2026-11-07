package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.internal.transform.MethodTarget;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finds mod classes overriding host-final methods (MinecraftForge 47 and 49 {@code Ingredient.toNetwork}; Fabric API's
 * {@code CustomIngredientImpl}) without defining classes.
 */
class HostFinalOverridesTest {

    private static final ClassFile FILES = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
    private static final String GAME = "net/minecraft/world/item/crafting/Ingredient";
    private static final String GAME_BASE = "net/minecraft/world/item/crafting/Base";
    private static final MethodTypeDesc WRITE = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    private static final MethodTarget TO_NETWORK = new MethodTarget(GAME, "toNetwork", "(I)V");

    @TempDir
    Path directory;

    private final Map<String, byte[]> patched = new HashMap<>();
    private final Map<String, byte[]> vanilla = new HashMap<>();

    /**
     * Class {@code name} extending {@code superName}, declaring {@code toNetwork(I)V} with {@code methodFlags} when
     * {@code declares} is true.
     */
    private static byte[] type(String name, String superName, int methodFlags, boolean declares) {
        return FILES.build(ClassDesc.ofInternalName(name), builder -> {
            builder.withSuperclass(ClassDesc.ofInternalName(superName));
            builder.withFlags(ClassFile.ACC_PUBLIC);
            if (declares) {
                if ((methodFlags & ClassFile.ACC_ABSTRACT) != 0) {
                    builder.withMethod("toNetwork", WRITE, methodFlags, method -> {});
                } else {
                    builder.withMethodBody("toNetwork", WRITE, methodFlags, code -> code.return_());
                }
            }
        });
    }

    private Path modJar(Map<String, byte[]> classes) throws Exception {
        Path jar = Files.createTempFile(directory, "mod", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private HostFinalOverrides check() {
        GameClassBytes classes = new GameClassBytes() {
            @Override
            public Optional<byte[]> classBytes(String className) {
                return Optional.ofNullable(patched.get(className.replace('.', '/')));
            }

            @Override
            public boolean isDefined(String className) {
                return false;
            }
        };
        return new HostFinalOverrides(classes, name -> Optional.ofNullable(vanilla.get(name)));
    }

    private void game(int patchedFlags, int vanillaFlags) {
        patched.put(GAME, type(GAME, "java/lang/Object", patchedFlags, true));
        vanilla.put(GAME, type(GAME, "java/lang/Object", vanillaFlags, true));
    }

    @Test
    void findsAModClassOverridingAMethodTheHostMadeFinal() throws Exception {
        game(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, ClassFile.ACC_PUBLIC);
        Path jar = modJar(Map.of(
                "mod/CustomIngredient", type("mod/CustomIngredient", GAME, ClassFile.ACC_PUBLIC, true),
                "mod/Unrelated", type("mod/Unrelated", "java/lang/Object", ClassFile.ACC_PUBLIC, true)));

        assertEquals(
                List.of(new HostFinalOverrides.FinalOverride("mod/CustomIngredient", TO_NETWORK)), check().find(jar));
    }

    @Test
    void findsItThroughTheModsOwnSuperclassAndAGameSuperclass() throws Exception {
        patched.put(GAME_BASE, type(GAME_BASE, "java/lang/Object", ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, true));
        vanilla.put(GAME_BASE, type(GAME_BASE, "java/lang/Object", ClassFile.ACC_PUBLIC, true));
        patched.put(GAME, type(GAME, GAME_BASE, 0, false));
        Path jar = modJar(Map.of(
                "mod/Middle", type("mod/Middle", GAME, 0, false),
                "mod/Leaf", type("mod/Leaf", "mod/Middle", ClassFile.ACC_PUBLIC, true)));

        assertEquals(
                List.of(new HostFinalOverrides.FinalOverride(
                        "mod/Leaf", new MethodTarget(GAME_BASE, "toNetwork", "(I)V"))),
                check().find(jar));
    }

    @Test
    void leavesOutWhatTheHostDidNotMakeFinal() throws Exception {
        Path jar =
                modJar(Map.of("mod/CustomIngredient", type("mod/CustomIngredient", GAME, ClassFile.ACC_PUBLIC, true)));

        // Final in vanilla too: the mod was not written for this method being overridable.
        game(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL);
        assertEquals(List.of(), check().find(jar));

        // Not final in the patched game.
        game(ClassFile.ACC_PUBLIC, ClassFile.ACC_PUBLIC);
        assertEquals(List.of(), check().find(jar));

        // Package-private in another package: the mod's method does not override it.
        game(ClassFile.ACC_FINAL, 0);
        assertEquals(List.of(), check().find(jar));

        // Static: the mod's method hides nothing the JVM checks.
        game(
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
        assertEquals(List.of(), check().find(jar));
    }

    @Test
    void leavesOutAModMethodThatIsStaticOrPrivate() throws Exception {
        game(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, ClassFile.ACC_PUBLIC);
        Path jar = modJar(Map.of(
                "mod/A", type("mod/A", GAME, ClassFile.ACC_PRIVATE, true),
                "mod/B", type("mod/B", GAME, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, true)));

        assertEquals(List.of(), check().find(jar));
    }

    @Test
    void aJarThatIsNotThereHasNothing() {
        game(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL, ClassFile.ACC_PUBLIC);

        assertEquals(List.of(), check().find(directory.resolve("missing.jar")));
    }
}
