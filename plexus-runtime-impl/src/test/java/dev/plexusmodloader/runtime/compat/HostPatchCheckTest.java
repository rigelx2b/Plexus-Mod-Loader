package dev.plexusmodloader.runtime.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.BridgeAssessment;
import dev.plexusmodloader.compat.bridge.BridgeLaunchContext;
import dev.plexusmodloader.compat.bridge.BridgedAccessWidening;
import dev.plexusmodloader.compat.bridge.BridgedDependency;
import dev.plexusmodloader.compat.bridge.BridgedEntrypoint;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.CompatibilityStatus;
import dev.plexusmodloader.compat.bridge.GameClassBytes;
import dev.plexusmodloader.compat.bridge.HostedLaunch;
import dev.plexusmodloader.compat.bridge.ModLoaderBridge;
import dev.plexusmodloader.internal.transform.MethodTarget;
import dev.plexusmodloader.runtime.discovery.ForeignModResolver.ForeignMod;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A mod whose code does not fit the host's patch is not loaded, and neither are the mods that need it (ADR-0030). */
class HostPatchCheckTest {

    @TempDir
    Path directory;

    /** Says {@code broken.jar} does not fit, and records what it was asked and could read. */
    private static final class CheckingBridge implements ModLoaderBridge {

        final List<String> askedFor = new ArrayList<>();
        boolean readPatchedClass;

        @Override
        public String loaderName() {
            return "Fabric";
        }

        @Override
        public String metadataPath() {
            return "fabric.mod.json";
        }

        @Override
        public BridgeAssessment assess(String metadata, GameSide side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invokeEntrypoint(BridgedEntrypoint entrypoint, ClassLoader gameClassLoader) {}

        @Override
        public Map<Path, List<String>> hostIncompatibilities(
                GameSide side, HostedLaunch host, List<BridgeLaunchContext.LoadedMod> mods, GameClassBytes patched) {
            mods.forEach(mod -> askedFor.add(mod.modId()));
            readPatchedClass = patched.classBytes("t.Patched").isPresent()
                    && patched.classBytes("java.lang.Object").isPresent()
                    && !patched.isDefined("t.Patched");
            return Map.of(Path.of("broken.jar"), List.of("BrokenMixin: its @Shadow field gone is not in the game"));
        }
    }

    private static final ModLoaderBridge BRIDGE = new CheckingBridge();

    private static ForeignMod mod(String id, ModLoaderBridge bridge, BridgedDependency... dependencies) {
        return new ForeignMod(
                Path.of(id + ".jar"),
                bridge,
                BridgeAssessment.of(
                        "Fabric",
                        id,
                        "1.0.0",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(dependencies),
                        CompatibilityStatus.EXPERIMENTAL));
    }

    private Path hostJar() throws Exception {
        Path jar = directory.resolve("patched.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("t/Patched.class"));
            output.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
            output.closeEntry();
        }
        return jar;
    }

    @Test
    void aModThatDoesNotFitIsBlockedWithPlexusModLoaderNamedAndTheModsThatNeedItFollow() throws Exception {
        CheckingBridge bridge = new CheckingBridge();
        BridgedDependency needsBroken =
                new BridgedDependency("broken", BridgedDependency.Kind.REQUIRED, "*", version -> true);
        List<ForeignMod> mods = List.of(mod("broken", bridge), mod("fine", bridge), mod("user", bridge, needsBroken));

        List<ForeignMod> checked = HostPatchCheck.apply(
                GameSide.DEDICATED_SERVER,
                new HostedLaunch("NeoForge 26.2.0.93", List.of(), List.of(), directory),
                List.of(hostJar()),
                HostPatchCheckTest.class.getClassLoader(),
                List.of(bridge),
                mods);

        assertEquals(List.of("broken", "fine", "user"), bridge.askedFor);
        assertTrue(bridge.readPatchedClass, "the bridge reads the host's classes and the JDK's, defining none");
        BridgeAssessment broken = checked.getFirst().assessment();
        assertEquals(CompatibilityStatus.BLOCKED, broken.status());
        CompatibilityFinding finding = broken.findings().getFirst();
        assertEquals(HostPatchCheck.CODE, finding.code());
        assertTrue(finding.blocking());
        assertTrue(
                finding.detail().contains("NeoForge 26.2.0.93")
                        && finding.detail().contains("not a bug in the mod")
                        && finding.detail().contains("report it to Plexus Mod Loader")
                        && finding.detail().contains("BrokenMixin"),
                finding.detail());
        assertSame(mods.get(1), checked.get(1));

        CrossLoaderModResolver.Result result =
                new CrossLoaderModResolver().resolve(checked, Map.of(), List.of(), "26.2", "25", null);
        List<String> statuses = result.mods().stream()
                .map(mod -> mod.assessment().modId() + "=" + mod.assessment().status()
                        + mod.assessment().blockingCodes())
                .toList();
        assertEquals(
                List.of("broken=BLOCKED[PLEXUS-COMPAT-224]", "fine=EXPERIMENTAL[]", "user=BLOCKED[PLEXUS-COMPAT-114]"),
                statuses);
    }

    @Test
    void withoutAHostNoBridgeIsAsked() {
        CheckingBridge bridge = new CheckingBridge();
        List<ForeignMod> mods = List.of(mod("broken", bridge));

        List<ForeignMod> checked = HostPatchCheck.apply(
                GameSide.CLIENT,
                HostedLaunch.NONE,
                List.of(),
                HostPatchCheckTest.class.getClassLoader(),
                List.of(bridge, BRIDGE),
                mods);

        assertSame(mods, checked);
        assertTrue(bridge.askedFor.isEmpty());
    }

    // A class of a mod that overrides a method the host's patch made final (ADR-0047).

    private static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
    private static final MethodTarget TO_NETWORK = new MethodTarget(INGREDIENT, "toNetwork", "(I)V");

    /** A test bridge that finds nothing wrong with any mixin. */
    private static final class QuietBridge implements ModLoaderBridge {

        @Override
        public String loaderName() {
            return "Fabric";
        }

        @Override
        public String metadataPath() {
            return "fabric.mod.json";
        }

        @Override
        public BridgeAssessment assess(String metadata, GameSide side) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void invokeEntrypoint(BridgedEntrypoint entrypoint, ClassLoader gameClassLoader) {}
    }

    private static byte[] ingredient(int flags) {
        return ClassFile.of()
                .build(
                        ClassDesc.ofInternalName(INGREDIENT),
                        builder -> builder.withFlags(ClassFile.ACC_PUBLIC)
                                .withMethodBody(
                                        "toNetwork",
                                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                                        flags,
                                        code -> code.return_()));
    }

    private Path jar(String name, String entry, byte[] bytes) throws Exception {
        Path jar = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry(entry + ".class"));
            output.write(bytes);
            output.closeEntry();
        }
        return jar;
    }

    private List<ForeignMod> overridingMod(List<MethodTarget> reviewed, ModLoaderBridge bridge) throws Exception {
        Path patched = jar("patched-game.jar", INGREDIENT, ingredient(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL));
        Path vanilla = jar("vanilla-game.jar", INGREDIENT, ingredient(ClassFile.ACC_PUBLIC));
        Path modJar = jar(
                "recipes.jar",
                "mod/CustomIngredientImpl",
                ClassFile.of()
                        .build(
                                ClassDesc.of("mod.CustomIngredientImpl"),
                                builder -> builder.withSuperclass(ClassDesc.ofInternalName(INGREDIENT))
                                        .withMethodBody(
                                                "toNetwork",
                                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int),
                                                ClassFile.ACC_PUBLIC,
                                                code -> code.return_())));
        ForeignMod mod = new ForeignMod(
                modJar,
                bridge,
                BridgeAssessment.of(
                        "Fabric", "recipes", "1.0.0", List.of(), List.of(), CompatibilityStatus.EXPERIMENTAL));
        return HostPatchCheck.apply(
                GameSide.DEDICATED_SERVER,
                new HostedLaunch("MinecraftForge 1.20.1-47.4.28", List.of(), List.of(), vanilla),
                reviewed,
                List.of(patched),
                HostPatchCheckTest.class.getClassLoader(),
                List.of(bridge),
                List.of(mod));
    }

    @Test
    void aReviewedMethodTheHostMadeFinalIsOpenedForTheModThatOverridesIt() throws Exception {
        QuietBridge bridge = new QuietBridge();
        ForeignMod checked = overridingMod(List.of(TO_NETWORK), bridge).getFirst();

        assertEquals(CompatibilityStatus.EXPERIMENTAL, checked.assessment().status());
        assertEquals(
                List.of(new BridgedAccessWidening(
                        HostPatchCheck.OPENED_CODE,
                        INGREDIENT,
                        BridgedAccessWidening.Kind.METHOD,
                        "toNetwork",
                        "(I)V",
                        Set.of(BridgedAccessWidening.Access.EXTENDABLE))),
                checked.assessment().accessWidenings());
        CompatibilityFinding finding = checked.assessment().findings().getFirst();
        assertEquals(HostPatchCheck.OPENED_CODE, finding.code());
        assertFalse(finding.blocking());
        assertTrue(
                finding.detail().contains("MinecraftForge 1.20.1-47.4.28")
                        && finding.detail().contains(TO_NETWORK.toString())
                        && finding.detail().contains("CustomIngredientImpl"),
                finding.detail());

        // The runtime checks the mods again after it replaced host modules: no second widening or finding.
        List<ForeignMod> again = HostPatchCheck.apply(
                GameSide.DEDICATED_SERVER,
                new HostedLaunch(
                        "MinecraftForge 1.20.1-47.4.28", List.of(), List.of(), directory.resolve("vanilla-game.jar")),
                List.of(TO_NETWORK),
                List.of(directory.resolve("patched-game.jar")),
                HostPatchCheckTest.class.getClassLoader(),
                List.of(bridge),
                List.of(checked));
        assertSame(checked, again.getFirst());
    }

    @Test
    void aMethodTheHostMadeFinalThatNoOneReviewedKeepsTheModOut() throws Exception {
        ForeignMod checked = overridingMod(List.of(), new QuietBridge()).getFirst();

        assertEquals(CompatibilityStatus.BLOCKED, checked.assessment().status());
        assertEquals(List.of(), checked.assessment().accessWidenings());
        CompatibilityFinding finding = checked.assessment().findings().getFirst();
        assertEquals(HostPatchCheck.CODE, finding.code());
        assertTrue(finding.blocking());
        assertTrue(
                finding.detail().contains("CustomIngredientImpl")
                        && finding.detail().contains(TO_NETWORK.toString())
                        && finding.detail().contains("MinecraftForge 1.20.1-47.4.28"),
                finding.detail());
    }
}
