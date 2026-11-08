package dev.plexusmodloader.compat.forge;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Plexus Mod Loader's change to NeoForge's FML 4 (NeoForge 21.1) and FML 2 (NeoForge 20.4) for a launch where Fabric or
 * Quilt mods run next to NeoForge mods (ADR-0043 point 10): one class loader holds one Mixin, and Fabric's serves every
 * loader, as on MinecraftForge (ADR-0021 G8) and NeoForge 26.x (ADR-0026 30, {@link NeoForgeSharedMixinPatch}).
 *
 * <p>FML's own Mixin is left out of the BOOT layer ({@link ForgeBootModules#sort}), so its ModLauncher services never start
 * it. FML still hands its mods' {@code [[mixins]]} configurations to Mixin itself: {@code LoadingModList.addMixinConfigs}
 * calls {@code DeferredMixinConfigRegistration.addMixinConfig(config, modId)} for each configuration whose required mods
 * are present, and that class's static initializer registers FML's platform agent and a container with the running Mixin
 * (Fabric's here). The one call becomes {@code NeoForgeMixinConfigs.add(config, modId)} of Plexus Mod Loader's
 * ModLauncher implementation (same descriptor), which keeps the configuration for the shared Mixin ({@code
 * PlexusModLauncher}); FML's registration class is then never initialized.
 *
 * <p>Checked with {@code javap} against {@code loader} 4.0.45 (NeoForge 21.1.265) and 2.0.17 (NeoForge 20.4.253): {@code
 * LoadingModList} calls {@code addMixinConfig(String, String)} exactly once, in the lambda of {@code addMixinConfigs}.
 * With another shape nothing changes; the host checks this before NeoForge starts ({@link #missing}) and leaves
 * NeoForge's mods out with {@code PLEXUS-COMPAT-216} instead, so that FML never reaches a second Mixin.
 */
final class Fml4SharedMixinPatch {

    static final String PACKAGE = "net.neoforged.fml.loading";
    static final String CLASS_FILE = "net/neoforged/fml/loading/LoadingModList.class";

    /** The host class FML's call goes to, in {@code cpw.mods.modlauncher} ({@code forgeHost} source set). */
    static final ClassDesc HOST = ClassDesc.of(ForgeBootModules.HOST_PACKAGE + ".NeoForgeMixinConfigs");

    static final String HOST_METHOD = "add";

    private static final ClassDesc LOADING_MOD_LIST = ClassDesc.of(PACKAGE + ".LoadingModList");
    private static final ClassDesc REGISTRATION =
            ClassDesc.of("net.neoforged.fml.loading.mixin.DeferredMixinConfigRegistration");
    private static final String ADD = "addMixinConfig";
    private static final MethodTypeDesc ADD_TYPE =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String, ConstantDescs.CD_String);

    /**
     * The result of patching one class.
     *
     * @param bytes the class file to define ({@code original} when nothing changed)
     * @param changed what was replaced
     * @param missing the checked shapes that were not found
     */
    record Result(byte[] bytes, List<String> changed, List<String> missing) {
        Result {
            changed = List.copyOf(changed);
            missing = List.copyOf(missing);
        }
    }

    private Fml4SharedMixinPatch() {}

    /** {@code reference} as it is, or, when it is FML's {@code loader} module, reading {@code LoadingModList} patched. */
    static ModuleReference patching(
            ModuleReference reference, ClassHierarchyResolver hierarchy, Consumer<Result> report) {
        if (!reference.descriptor().packages().contains(PACKAGE)) {
            return reference;
        }
        return PatchedClassReference.of(reference, CLASS_FILE, bytes -> {
            Result result = apply(bytes, hierarchy);
            report.accept(result);
            return result.bytes();
        });
    }

    /** What of the checked shape {@code loadingModList} (the class file of FML's {@code LoadingModList}) does not have. */
    static List<String> missing(byte[] loadingModList) {
        ClassModel model = ClassFile.of().parse(loadingModList);
        int calls = registrationCalls(model);
        if (calls == 1) {
            return List.of();
        }
        return List.of("LoadingModList with one call of DeferredMixinConfigRegistration." + ADD
                + "(String, String) (found " + calls + ")");
    }

    /** {@code LoadingModList} with its call of FML's Mixin registration replaced, or unchanged when the shape differs. */
    static Result apply(byte[] original, ClassHierarchyResolver hierarchy) {
        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        ClassModel model = classFile.parse(original);
        if (!model.thisClass().asSymbol().equals(LOADING_MOD_LIST)) {
            return new Result(original, List.of(), List.of());
        }
        List<String> missing = missing(original);
        if (!missing.isEmpty()) {
            return new Result(original, List.of(), missing);
        }
        List<String> changed = new ArrayList<>();
        ClassTransform transform = (builder, element) -> {
            if (element instanceof MethodModel method && calls(method)) {
                changed.add(method.methodName().stringValue() + ": DeferredMixinConfigRegistration." + ADD
                        + " -> NeoForgeMixinConfigs." + HOST_METHOD);
                builder.transformMethod(method, MethodTransform.transformingCode((code, codeElement) -> {
                    if (codeElement instanceof InvokeInstruction call && isRegistration(call)) {
                        code.invokestatic(HOST, HOST_METHOD, ADD_TYPE);
                    } else {
                        code.with(codeElement);
                    }
                }));
            } else {
                builder.with(element);
            }
        };
        byte[] bytes = classFile.transformClass(model, transform);
        return new Result(bytes, changed, List.of());
    }

    private static int registrationCalls(ClassModel model) {
        int calls = 0;
        for (MethodModel method : model.methods()) {
            CodeModel code = method.code().orElse(null);
            if (code == null) {
                continue;
            }
            for (var element : code) {
                if (element instanceof InvokeInstruction call && isRegistration(call)) {
                    calls++;
                }
            }
        }
        return calls;
    }

    private static boolean calls(MethodModel method) {
        return method.code().stream()
                .flatMap(code -> code.elementStream())
                .anyMatch(element -> element instanceof InvokeInstruction call && isRegistration(call));
    }

    private static boolean isRegistration(InvokeInstruction call) {
        return call.opcode() == Opcode.INVOKESTATIC
                && call.owner().asSymbol().equals(REGISTRATION)
                && call.name().equalsString(ADD)
                && call.typeSymbol().equals(ADD_TYPE);
    }
}
