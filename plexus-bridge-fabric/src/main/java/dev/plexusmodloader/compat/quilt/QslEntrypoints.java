package dev.plexusmodloader.compat.quilt;

import dev.plexusmodloader.api.runtime.GameSide;
import dev.plexusmodloader.compat.bridge.BridgedEntrypoint;
import dev.plexusmodloader.compat.bridge.CompatibilityFinding;
import dev.plexusmodloader.compat.bridge.ModJarContents;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import org.objectweb.asm.ClassReader;

/**
 * QSL entrypoints ({@code init}, {@code client_init}, {@code server_init}) whose class implements the matching Fabric
 * initializer itself, which Plexus Mod Loader runs as the Fabric phase ({@code PLEXUS-COMPAT-311}). QSL has no release
 * for Minecraft 26.x, so without this the entrypoints do not run ({@code 303}) and a {@code quilt_base} dependency
 * cannot be met ({@code 308}). An entrypoint whose class implements QSL's own interface, or reaches the Fabric one
 * only through a superclass, keeps {@code 303}; a mod that uses QSL classes is still left out by the link check
 * ({@code 111}).
 */
final class QslEntrypoints {

    /** The QSL module that runs QSL's entrypoints. */
    static final String QUILT_BASE = "quilt_base";

    /** QSL entrypoint key → Fabric phase and the Fabric interface the class must implement. */
    private static final Map<String, Phase> PHASES = Map.of(
            "init",
            new Phase(
                    "common", "net/fabricmc/api/ModInitializer", "org/quiltmc/qsl/base/api/entrypoint/ModInitializer"),
            "client_init",
            new Phase(
                    "client",
                    "net/fabricmc/api/ClientModInitializer",
                    "org/quiltmc/qsl/base/api/entrypoint/client/ClientModInitializer"),
            "server_init",
            new Phase(
                    "server",
                    "net/fabricmc/api/DedicatedServerModInitializer",
                    "org/quiltmc/qsl/base/api/entrypoint/server/DedicatedServerModInitializer"));

    /** The Fabric initializer of each QSL key, as the runtime checks it ({@code QuiltLoaderRuntime}). */
    private static final Map<String, Class<?>> FABRIC_INTERFACES = Map.of(
            "init", ModInitializer.class,
            "client_init", ClientModInitializer.class,
            "server_init", DedicatedServerModInitializer.class);

    private record Phase(String name, String fabricInterface, String qslInterface) {}

    private record Declared(String key, String value, Phase phase) {}

    private final List<Declared> runnable;
    private final List<Declared> notRunnable;
    private final GameSide side;

    private QslEntrypoints(List<Declared> runnable, List<Declared> notRunnable, GameSide side) {
        this.runnable = List.copyOf(runnable);
        this.notRunnable = List.copyOf(notRunnable);
        this.side = side;
    }

    /** Sorts the mod's QSL entrypoints by whether their class implements the Fabric interface. */
    static QslEntrypoints of(QuiltModDescription mod, GameSide side, ModJarContents jar) {
        List<Declared> runnable = new ArrayList<>();
        List<Declared> notRunnable = new ArrayList<>();
        mod.entrypoints().forEach((key, declarations) -> {
            Phase phase = PHASES.get(key);
            if (phase == null) {
                return;
            }
            for (QuiltModDescription.Entrypoint declaration : declarations) {
                Declared declared = new Declared(key, declaration.value(), phase);
                List<String> interfaces = declaration.adapter().equals("default")
                        ? directInterfaces(jar, declaration.value())
                        : List.of();
                // A class that also implements QSL's own initializer is QSL's to run (it needs QSL to load at all).
                boolean implementsFabric =
                        interfaces.contains(phase.fabricInterface()) && !interfaces.contains(phase.qslInterface());
                (implementsFabric ? runnable : notRunnable).add(declared);
            }
        });
        return new QslEntrypoints(runnable, notRunnable, side);
    }

    /** The Fabric initializer the class of a QSL entrypoint {@code key} implements when the bridge runs it (311). */
    static Optional<Class<?>> fabricInterface(String key) {
        return Optional.ofNullable(FABRIC_INTERFACES.get(key));
    }

    /**
     * Whether a required {@code quilt_base} dependency is met by running the QSL entrypoints: the mod declares some and
     * every one of them can run. Mods without QSL entrypoints, or with ones only QSL can run, keep {@code 308}.
     */
    boolean meetsQuiltBase(QuiltModDescription mod) {
        boolean requiresBase = mod.depends().stream()
                .anyMatch(dependency -> dependency.bridged().modId().equals(QUILT_BASE));
        return requiresBase && !runnable.isEmpty() && notRunnable.isEmpty();
    }

    /**
     * Replaces the reader's {@code 303} with one for the entrypoints that still cannot run (if any), drops the {@code
     * 308} of a met {@code quilt_base}, and adds {@code 311} naming what runs.
     */
    void adjustFindings(List<CompatibilityFinding> findings, boolean quiltBaseMet) {
        if (runnable.isEmpty()) {
            return;
        }
        boolean had303 = findings.removeIf(finding -> finding.code().equals("PLEXUS-COMPAT-303"));
        if (quiltBaseMet) {
            findings.removeIf(finding -> finding.code().equals("PLEXUS-COMPAT-308")
                    && finding.detail().contains("'" + QUILT_BASE + "'"));
        }
        if (had303 && !notRunnable.isEmpty()) {
            findings.add(
                    new CompatibilityFinding(
                            "PLEXUS-COMPAT-303",
                            false,
                            "entrypoints "
                                    + notRunnable.stream().map(Declared::value).toList(),
                            "QSL runs these entrypoints, but QSL is not installed or has no release for this Minecraft version; Quilt Loader does not run them without QSL, and Plexus does not run them because their class does not implement the Fabric initializer."));
        }
        List<String> run = runnable.stream()
                .map(declared -> declared.key() + " " + declared.value())
                .toList();
        findings.add(new CompatibilityFinding(
                "PLEXUS-COMPAT-311",
                false,
                "QSL entrypoints " + run,
                "run as Fabric entrypoints (main, client, server) because their class implements the Fabric"
                        + "; QSL, which would run them, is not installed or has no release for this Minecraft version"
                        + (quiltBaseMet ? ". The dependency on 'quilt_base' counts as met for this mod" : "")));
    }

    /** {@code entrypoints} with the runnable QSL entrypoints of this side added after them. */
    List<BridgedEntrypoint> withRunnable(List<BridgedEntrypoint> entrypoints) {
        List<BridgedEntrypoint> all = new ArrayList<>(entrypoints);
        for (Declared declared : runnable) {
            String phase = declared.phase().name();
            boolean applies = phase.equals("common")
                    || (phase.equals("client") && side == GameSide.CLIENT)
                    || (phase.equals("server") && side == GameSide.DEDICATED_SERVER);
            BridgedEntrypoint entrypoint = new BridgedEntrypoint(phase, declared.value(), "default");
            if (applies && !all.contains(entrypoint)) {
                all.add(entrypoint);
            }
        }
        return all;
    }

    /** The interfaces the class declares itself, or none when the jar lacks it or it cannot be read. */
    private static List<String> directInterfaces(ModJarContents jar, String className) {
        Optional<byte[]> bytes;
        try {
            bytes = jar.read(className.replace('.', '/') + ".class");
        } catch (UncheckedIOException e) {
            return List.of();
        }
        if (bytes.isEmpty()) {
            return List.of();
        }
        try {
            return Arrays.asList(new ClassReader(bytes.get()).getInterfaces());
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            // ASM rejects a malformed or newer class file this way; such an entrypoint is left to QSL (303).
            return List.of();
        }
    }
}
