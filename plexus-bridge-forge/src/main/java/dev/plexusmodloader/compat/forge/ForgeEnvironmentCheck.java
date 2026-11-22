package dev.plexusmodloader.compat.forge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Forge on NeoForge (ADR-0051): whether Forge's launch state can be set as the host sets it ({@code ForgeEnvironment} in
 * the host). MinecraftForge's launch sets the side and the game directory in private static fields of its {@code
 * FMLLoader}; that launch does not run on NeoForge, so the host writes the same two fields from NeoForge's side and game
 * directory before FML starts. This check reads Forge's {@code fmlloader} and {@code mergetool-api} jars for what that
 * relies on: Forge's {@code Dist} has the constants of {@link #SIDES}; {@code FMLLoader} has the private static,
 * non-final fields {@code dist}, {@code gamePath} and {@code production} (F-N6), and {@code getDist()}, {@code
 * getGamePath()} and {@code isProduction()} only return them; and {@code FMLEnvironment} copies {@code
 * FMLLoader.getDist()} into its {@code dist} field and reads {@code FMLLoader.isProduction()} for its public {@code
 * production} field when it is initialised.
 * When any of it differs (another Forge release), every Forge mod is left out ({@code PLEXUS-COMPAT-229}).
 *
 * <p>Thread: stateless.
 */
final class ForgeEnvironmentCheck {

    /** The sides NeoForge launches with, by name; Forge's {@code Dist} has the same names (both {@code mergetool} APIs). */
    static final List<String> SIDES = List.of("CLIENT", "DEDICATED_SERVER");

    static final String FML_LOADER = "net/minecraftforge/fml/loading/FMLLoader";
    static final String DIST_FIELD = "dist";
    static final String GAME_PATH_FIELD = "gamePath";
    static final String PRODUCTION_FIELD = "production";

    private static final String DIST = "net/minecraftforge/api/distmarker/Dist";
    private static final String FML_ENVIRONMENT = "net/minecraftforge/fml/loading/FMLEnvironment";
    private static final String DIST_DESCRIPTOR = "L" + DIST + ";";
    private static final String PATH_DESCRIPTOR = "Ljava/nio/file/Path;";

    private ForgeEnvironmentCheck() {}

    /**
     * What of the above Forge's {@code fmlloader} jar and {@code mergetool-api} jar do not have; empty when the host can
     * set Forge's side and game directory.
     *
     * @throws IOException when a jar or one of its classes cannot be read
     */
    static List<String> missing(Path fmlloader, Path mergetoolApi) throws IOException {
        List<String> missing = new ArrayList<>();
        try (JarFile loaderJar = new JarFile(fmlloader.toFile());
                JarFile distJar = new JarFile(mergetoolApi.toFile())) {
            Optional<ClassModel> dist = read(distJar, DIST);
            if (dist.isEmpty()) {
                missing.add("missing class " + DIST);
            } else {
                for (String side : SIDES) {
                    if (!field(dist.get(), side, DIST_DESCRIPTOR, Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC))) {
                        missing.add(DIST + ": missing public static field " + side);
                    }
                }
            }
            Optional<ClassModel> loader = read(loaderJar, FML_LOADER);
            if (loader.isEmpty()) {
                missing.add("missing class " + FML_LOADER);
            } else {
                checkState(loader.get(), DIST_FIELD, DIST_DESCRIPTOR, "getDist", missing);
                checkState(loader.get(), GAME_PATH_FIELD, PATH_DESCRIPTOR, "getGamePath", missing);
                checkState(loader.get(), PRODUCTION_FIELD, "Z", "isProduction", missing);
            }
            Optional<ClassModel> environment = read(loaderJar, FML_ENVIRONMENT);
            if (environment.isEmpty()) {
                missing.add("missing class " + FML_ENVIRONMENT);
            } else {
                if (!field(
                        environment.get(), DIST_FIELD, DIST_DESCRIPTOR, Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC))) {
                    missing.add(
                            FML_ENVIRONMENT + ": missing public static field " + DIST_FIELD + " " + DIST_DESCRIPTOR);
                }
                if (!copiesDist(environment.get())) {
                    missing.add(FML_ENVIRONMENT + ": <clinit> does not set dist from FMLLoader.getDist()");
                }
                if (!field(environment.get(), PRODUCTION_FIELD, "Z", Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC))) {
                    missing.add(FML_ENVIRONMENT + ": missing public static field " + PRODUCTION_FIELD + " Z");
                }
                if (!callsIsProduction(environment.get())) {
                    missing.add(FML_ENVIRONMENT + ": <clinit> does not read FMLLoader.isProduction()");
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot parse class file in Forge's fmlloader or mergetool-api jar: " + e, e);
        }
        return List.copyOf(missing);
    }

    /** Adds to {@code missing} what {@code FMLLoader}'s state field {@code field} and its {@code getter} lack. */
    private static void checkState(
            ClassModel loader, String field, String descriptor, String getter, List<String> missing) {
        if (!field(loader, field, descriptor, Set.of(AccessFlag.PRIVATE, AccessFlag.STATIC))
                || flagged(loader, field, AccessFlag.FINAL)) {
            missing.add(FML_LOADER + ": missing private static non-final field " + field + " " + descriptor);
        }
        Optional<MethodModel> method = method(loader, getter, "()" + descriptor);
        if (method.isEmpty()
                || !method.get().flags().flags().containsAll(Set.of(AccessFlag.PUBLIC, AccessFlag.STATIC))) {
            missing.add(FML_LOADER + ": missing public static method " + getter + "()" + descriptor);
        } else if (!returnsField(loader, method.get(), field)) {
            missing.add(FML_LOADER + ": " + getter + "() does not only return " + field);
        }
    }

    private static Optional<ClassModel> read(JarFile jar, String internalName) throws IOException {
        JarEntry entry = jar.getJarEntry(internalName + ".class");
        if (entry == null) {
            return Optional.empty();
        }
        try (InputStream input = jar.getInputStream(entry)) {
            return Optional.of(ClassFile.of().parse(input.readAllBytes()));
        }
    }

    private static boolean field(ClassModel model, String name, String descriptor, Set<AccessFlag> flags) {
        return model.fields().stream()
                .anyMatch(field -> field.fieldName().equalsString(name)
                        && field.fieldType().equalsString(descriptor)
                        && field.flags().flags().containsAll(flags));
    }

    private static boolean flagged(ClassModel model, String name, AccessFlag flag) {
        return model.fields().stream()
                .filter(field -> field.fieldName().equalsString(name))
                .map(FieldModel::flags)
                .anyMatch(flags -> flags.has(flag));
    }

    /** Whether {@code method} of {@code model} is {@code getstatic <field>} and a return of it. */
    private static boolean returnsField(ClassModel model, MethodModel method, String field) {
        List<Instruction> code = instructions(method);
        return code.size() == 2
                && code.get(0) instanceof FieldInstruction get
                && get.opcode() == Opcode.GETSTATIC
                && get.owner().asInternalName().equals(model.thisClass().asInternalName())
                && get.name().equalsString(field)
                && (code.get(1).opcode() == Opcode.ARETURN || code.get(1).opcode() == Opcode.IRETURN);
    }

    /** Whether {@code FMLEnvironment}'s {@code <clinit>} has {@code invokestatic FMLLoader.getDist(); putstatic dist}. */
    private static boolean copiesDist(ClassModel environment) {
        Optional<MethodModel> clinit = method(environment, "<clinit>", "()V");
        if (clinit.isEmpty()) {
            return false;
        }
        List<Instruction> code = instructions(clinit.get());
        for (int i = 0; i + 1 < code.size(); i++) {
            if (code.get(i) instanceof InvokeInstruction invoke
                    && invoke.opcode() == Opcode.INVOKESTATIC
                    && invoke.owner().asInternalName().equals(FML_LOADER)
                    && invoke.name().equalsString("getDist")
                    && invoke.type().equalsString("()" + DIST_DESCRIPTOR)
                    && code.get(i + 1) instanceof FieldInstruction put
                    && put.opcode() == Opcode.PUTSTATIC
                    && put.owner().asInternalName().equals(FML_ENVIRONMENT)
                    && put.name().equalsString(DIST_FIELD)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code FMLEnvironment}'s {@code <clinit>} calls {@code FMLLoader.isProduction()}. */
    private static boolean callsIsProduction(ClassModel environment) {
        return method(environment, "<clinit>", "()V").stream()
                .flatMap(clinit -> instructions(clinit).stream())
                .anyMatch(instruction -> instruction instanceof InvokeInstruction invoke
                        && invoke.opcode() == Opcode.INVOKESTATIC
                        && invoke.owner().asInternalName().equals(FML_LOADER)
                        && invoke.name().equalsString("isProduction")
                        && invoke.type().equalsString("()Z"));
    }

    private static Optional<MethodModel> method(ClassModel model, String name, String descriptor) {
        return model.methods().stream()
                .filter(method -> method.methodName().equalsString(name)
                        && method.methodType().equalsString(descriptor))
                .findFirst();
    }

    private static List<Instruction> instructions(MethodModel method) {
        List<Instruction> instructions = new ArrayList<>();
        Optional<CodeModel> code = method.code();
        if (code.isPresent()) {
            for (CodeElement element : code.get()) {
                if (element instanceof Instruction instruction) {
                    instructions.add(instruction);
                }
            }
        }
        return instructions;
    }
}
