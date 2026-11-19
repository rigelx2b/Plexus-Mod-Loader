package dev.plexusmodloader.runtime.compat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forge on NeoForge, F-N2 and F-N3 ({@code docs/plans/FORGE_ON_NEOFORGE.md}, ADR-0051): whether a MinecraftForge mod uses
 * only the Forge API that runs when NeoForge hosts the game. Such a mod is handed to NeoForge's FML, which constructs it
 * with Forge's own {@code FMLModContainer}, gives it Forge's setup and load complete events and the Forge game events
 * NeoForge's are passed on as ({@code ForgeEventPairs} in the Forge bridge); any other mod stays left out ({@code
 * PLEXUS-COMPAT-229}) with what it uses beyond that, so that no mod runs with listeners that are never called.
 *
 * <p>The check reads every class of the jar (not of the jars nested in it) and collects each {@code net/minecraftforge/}
 * class its constant pool names, in class entries, descriptors, signatures and annotations alike.
 *
 * <p>Thread: stateless.
 */
public final class ForgeOnNeoForgeScope {

    /**
     * The Forge classes that run: the mod annotation and loading context (F-N2), the lifecycle events passed on (F-N2,
     * F-N3), the game events of the Forge bridge's {@code ForgeEventPairs.GAME_EVENTS} with the interfaces and types
     * their members use (F-N3), and registration with a registry key (F-N4: {@code DeferredRegister}, its {@code
     * RegistryObject}s, Forge's {@code RegisterEvent}, which NeoForge's is passed on as, and the keys of {@code
     * ForgeRegistries.Keys}, whose outer class {@code ForgeRegistries} is named too, though none of its members may be).
     * {@code IForgeRegistry} and the {@code RegisterEvent} helper (which needs Forge's {@code ModLoadingContext}) are
     * not here: their names in a mod's descriptors leave it out. Also Forge's own game event bus ({@code
     * MinecraftForge.EVENT_BUS}, an {@code EventBusMigrationHelper} that registers a mod's listeners on the static
     * buses of the events passed on) and Forge's paths ({@code FMLPaths}, from Forge's {@code fmlloader} jar, which the
     * host sets to the game's paths).
     */
    private static final Set<String> CLASSES = Set.of(
            "net/minecraftforge/fml/common/Mod",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext",
            "net/minecraftforge/fml/event/lifecycle/FMLCommonSetupEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLDedicatedServerSetupEvent",
            "net/minecraftforge/fml/event/lifecycle/FMLLoadCompleteEvent",
            "net/minecraftforge/fml/LogicalSide",
            "net/minecraftforge/event/TickEvent",
            "net/minecraftforge/event/TickEvent$ServerTickEvent",
            "net/minecraftforge/event/TickEvent$ServerTickEvent$Pre",
            "net/minecraftforge/event/TickEvent$ServerTickEvent$Post",
            "net/minecraftforge/event/TickEvent$LevelTickEvent",
            "net/minecraftforge/event/TickEvent$LevelTickEvent$Pre",
            "net/minecraftforge/event/TickEvent$LevelTickEvent$Post",
            "net/minecraftforge/event/RegisterCommandsEvent",
            "net/minecraftforge/event/server/ServerLifecycleEvent",
            "net/minecraftforge/event/server/ServerStartingEvent",
            "net/minecraftforge/event/server/ServerStartedEvent",
            "net/minecraftforge/event/server/ServerStoppingEvent",
            "net/minecraftforge/event/server/ServerStoppedEvent",
            "net/minecraftforge/event/entity/EntityEvent",
            "net/minecraftforge/event/entity/EntityJoinLevelEvent",
            "net/minecraftforge/registries/DeferredRegister",
            "net/minecraftforge/registries/RegistryObject",
            "net/minecraftforge/registries/RegisterEvent",
            "net/minecraftforge/registries/ForgeRegistries",
            "net/minecraftforge/registries/ForgeRegistries$Keys",
            "net/minecraftforge/common/MinecraftForge",
            "net/minecraftforge/common/EventBusMigrationHelper",
            "net/minecraftforge/fml/loading/FMLPaths");

    /**
     * {@code ForgeRegistries} is named by the {@code InnerClasses} attribute of every class that uses {@code
     * ForgeRegistries.Keys}; its own members are Forge registries, which NeoForge's game does not have, so every member
     * reference with this owner is left out.
     */
    private static final String FORGE_REGISTRIES = "net/minecraftforge/registries/ForgeRegistries";

    /** Forge's event bus 7 API, which runs as Forge's own library and needs nothing of Forge's patches. */
    private static final String EVENT_BUS_API = "net/minecraftforge/eventbus/api/";

    /**
     * Members of allowed classes that need Forge's loader state, which NeoForge's game does not have, and the bus of an
     * event interface whose other events are not passed on ({@code ServerLifecycleEvent} has {@code
     * ServerAboutToStartEvent} too). For registration (F-N4): new registries and optional tag defaults (both need Forge's
     * registries), a {@code RegistryObject} made outside a {@code DeferredRegister} (filled by Forge's object holders,
     * which do not run), Forge's registry of the registry event ({@code null} here), and the keys of registries only
     * Forge has, for which NeoForge sends no registry event.
     */
    private static final Set<String> MEMBERS = Set.of(
            "net/minecraftforge/event/server/ServerLifecycleEvent.BUS",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.get",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.getContainer",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.registerConfig",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.registerExtensionPoint",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.registerDisplayTest",
            "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.extension",
            "net/minecraftforge/registries/DeferredRegister.makeRegistry",
            "net/minecraftforge/registries/DeferredRegister.createOptionalTagKey",
            "net/minecraftforge/registries/DeferredRegister.addOptionalTagDefaults",
            "net/minecraftforge/registries/RegistryObject.create",
            "net/minecraftforge/registries/RegistryObject.createOptional",
            "net/minecraftforge/registries/RegisterEvent.getForgeRegistry",
            "net/minecraftforge/common/MinecraftForge.initialize",
            "net/minecraftforge/common/MinecraftForge.registerConfigScreen",
            "net/minecraftforge/fml/loading/FMLPaths.setup",
            "net/minecraftforge/fml/loading/FMLPaths.loadAbsolutePaths",
            "net/minecraftforge/registries/ForgeRegistries$Keys.ENTITY_DATA_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.BIOME_MODIFIER_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.STRUCTURE_MODIFIER_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.FLUID_TYPES",
            "net/minecraftforge/registries/ForgeRegistries$Keys.HOLDER_SET_TYPES",
            "net/minecraftforge/registries/ForgeRegistries$Keys.DISPLAY_CONTEXTS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.CONDITION_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.INGREDIENT_SERIALIZERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.BIOME_MODIFIERS",
            "net/minecraftforge/registries/ForgeRegistries$Keys.STRUCTURE_MODIFIERS");

    /**
     * Forge's lifecycle events queue {@code enqueueWork} work for Forge's loading stages, which do not run: denied on
     * every class of this package, as the compiler names the class of the receiver.
     */
    private static final String LIFECYCLE_EVENTS = "net/minecraftforge/fml/event/lifecycle/";

    /** Files that make Forge transform classes or install more jars, which F-N2 does not do. */
    private static final List<String> FILES =
            List.of("META-INF/coremods.json", "META-INF/accesstransformer.cfg", "META-INF/jarjar/metadata.json");

    private static final Pattern FORGE_CLASS = Pattern.compile("net/minecraftforge/[A-Za-z0-9_/$]+");

    private ForgeOnNeoForgeScope() {}

    /**
     * What {@code jar} uses beyond F-N2's Forge API: Forge classes and members (as {@code owner} or {@code
     * owner.member}) and the class-transforming files it has. Empty when the mod runs on NeoForge. Its Mixin
     * configurations are not named: FML applies them, and the link check's Mixin check decides whether they fit
     * ({@code ForgeOnNeoForgeMixins}).
     *
     * @throws IOException when the jar or one of its classes cannot be read
     */
    public static List<String> unsupported(Path jar) throws IOException {
        Set<String> found = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            for (String name : FILES) {
                if (file.getEntry(name) != null) {
                    found.add(name);
                }
            }
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream input = file.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                collect(bytes, found);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException(jar.getFileName() + ": " + e.getMessage(), e);
        }
        return List.copyOf(found);
    }

    /** Whether {@code jar}'s manifest names Mixin configurations ({@code MixinConfigs}). */
    static boolean hasMixinConfigs(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest != null && manifest.getMainAttributes().getValue("MixinConfigs") != null;
        }
    }

    /** Adds what {@code classBytes} uses beyond F-N2's Forge API to {@code found}. */
    static void collect(byte[] classBytes, Set<String> found) {
        ClassModel model = ClassFile.of().parse(classBytes);
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof Utf8Entry utf8) {
                Matcher matcher = FORGE_CLASS.matcher(utf8.stringValue());
                while (matcher.find()) {
                    String owner = matcher.group();
                    if (!allowed(owner)) {
                        found.add(owner);
                    }
                }
            } else if (entry instanceof MemberRefEntry member) {
                String reference =
                        member.owner().asInternalName() + "." + member.name().stringValue();
                if (MEMBERS.contains(reference)
                        || member.owner().asInternalName().equals(FORGE_REGISTRIES)
                        || (reference.startsWith(LIFECYCLE_EVENTS) && reference.endsWith(".enqueueWork"))) {
                    found.add(reference);
                }
            }
        }
    }

    /** Whether a Forge mod handed to NeoForge may name {@code forgeClass} (an internal name). */
    public static boolean allowed(String forgeClass) {
        return CLASSES.contains(forgeClass) || forgeClass.startsWith(EVENT_BUS_API);
    }

    /** {@code jars} split by {@link #unsupported}. */
    public record Split(List<Path> runnable, List<Left> leftOut) {
        public Split {
            runnable = List.copyOf(runnable);
            leftOut = List.copyOf(leftOut);
        }

        /** This split with those of {@code reasons}' jars that were runnable left out with their reasons. */
        public Split without(Map<Path, List<String>> reasons) {
            List<Path> kept = new ArrayList<>();
            List<Left> left = new ArrayList<>(leftOut);
            for (Path jar : runnable) {
                List<String> why = reasons.get(jar);
                if (why != null) {
                    left.add(new Left(jar, why));
                } else {
                    kept.add(jar);
                }
            }
            return new Split(kept, left);
        }

        /** This split with those of {@code declined} that were runnable left out with {@code reason}. */
        public Split without(List<Path> declined, String reason) {
            List<Path> kept = new ArrayList<>();
            List<Left> left = new ArrayList<>(leftOut);
            for (Path jar : runnable) {
                if (declined.contains(jar)) {
                    left.add(new Left(jar, List.of(reason)));
                } else {
                    kept.add(jar);
                }
            }
            return new Split(kept, left);
        }
    }

    /** A Forge mod jar that does not run on NeoForge, with what it uses beyond F-N2's Forge API. */
    public record Left(Path jar, List<String> unsupported) {
        public Left {
            unsupported = List.copyOf(unsupported);
        }
    }

    /**
     * The Minecraft versions F-N2 was run on. Elsewhere every Forge mod is left out: the host's reader and language
     * loader use FML internals checked against FML 11 of NeoForge 26.2 only.
     */
    static final Set<String> VERIFIED_MINECRAFT = Set.of("26.1", "26.1.1", "26.1.2", "26.2", "26.3");

    /**
     * What a Forge mod with Mixin configurations is left out with when Fabric or Quilt mods share NeoForge's Mixin
     * (ADR-0026 30), which Forge mods' configurations have not been run with.
     */
    public static final String SHARED_MIXIN =
            "MixinConfigs (with Fabric or Quilt mods, whose Mixin NeoForge's mods share)";

    /** What a jar the NeoForge host declined after reading its {@code mods.toml} is reported with. */
    public static final String DECLINED_BY_HOST = "META-INF/mods.toml";

    /**
     * Splits {@code jars} into those that run on NeoForge for Minecraft {@code gameVersion} and those left out; a jar
     * that cannot be read is left out, and on a version F-N2 was not run on ({@link #VERIFIED_MINECRAFT}) every jar is,
     * with {@code minecraft <version>}. With {@code sharedMixin} (Fabric or Quilt mods in the game), a jar whose manifest
     * names Mixin configurations is left out with {@link #SHARED_MIXIN}.
     */
    public static Split split(List<Path> jars, String gameVersion, boolean sharedMixin) {
        List<Path> runnable = new ArrayList<>();
        List<Left> leftOut = new ArrayList<>();
        if (!VERIFIED_MINECRAFT.contains(gameVersion)) {
            jars.forEach(jar -> leftOut.add(new Left(jar, List.of("minecraft " + gameVersion))));
            return new Split(runnable, leftOut);
        }
        for (Path jar : jars) {
            try {
                List<String> unsupported = new ArrayList<>(unsupported(jar));
                if (sharedMixin && hasMixinConfigs(jar)) {
                    unsupported.add(SHARED_MIXIN);
                }
                if (unsupported.isEmpty()) {
                    runnable.add(jar);
                } else {
                    leftOut.add(new Left(jar, unsupported));
                }
            } catch (IOException e) {
                leftOut.add(new Left(jar, List.of(e.toString())));
            }
        }
        return new Split(runnable, leftOut);
    }
}
