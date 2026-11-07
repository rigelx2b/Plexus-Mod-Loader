package dev.plexusmodloader.game.layer.v1_20_1.gametest;

import dev.plexusmodloader.game.layer.v1_20_1.GameHooks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the headless game test server on Minecraft 1.20.1, whose jars have no {@code
 * net.minecraft.gametest.Main} (26.x and 1.21.5+ do). It takes the arguments that Main takes and the verification
 * tasks pass: {@code --universe <dir>}, {@code --tests <selector>[,<selector>...]} and {@code --packs <dir>}; a selector
 * is a test name, or {@code <namespace>:*} for every test of a namespace.
 *
 * <p>Like the game's own Main it bootstraps the game, creates the world {@value #WORLD} in the universe folder, and
 * hands the tests to {@code GameTestServer}, which runs them, saves the world and stops. The game layer registers the
 * built-in {@code minecraft:always_pass} test and the mods' tests while the registries freeze in the bootstrap
 * ({@code GameTests}), and its generated data pack carries the test structures. Mods that Plexus Mod Loader hosts
 * from other loaders are initialised after the bootstrap and before the server is made, where the dedicated
 * server's Main does it.
 */
public final class GameTestMain {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");
    /** The world's folder name under the universe folder; the verification tasks read it. */
    public static final String WORLD = "gametestworld";

    private static final BlockPos SPAWN = new BlockPos(0, -60, 0);
    private static final String MODS_HOOKS = "dev.plexusmodloader.game.version.v1_20_1.Minecraft1_20_1Hooks";

    private GameTestMain() {}

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        SharedConstants.tryDetectVersion();
        // Bootstrap freezes the registries; the game layer registers mod content and tests on the way.
        Bootstrap.bootStrap();
        Bootstrap.validate();
        initialiseMods();
        freezeRegistriesIfPending();

        Path universe = Path.of(arguments.universe).toAbsolutePath();
        Files.createDirectories(universe);
        deleteRecursively(universe.resolve(WORLD));
        LevelStorageSource storage = LevelStorageSource.createDefault(universe);
        LevelStorageSource.LevelStorageAccess access = storage.createAccess(WORLD);
        PackRepository packs = packRepository(access, arguments.packs);
        List<TestFunction> tests = select(arguments.selectors);
        if (tests.isEmpty()) {
            access.close();
            throw new IllegalArgumentException(
                    "No game test matches " + arguments.selectors + "; registered: " + names());
        }
        LOGGER.info("[Plexus Mod Loader] Running {} game test(s): {}", tests.size(), names(tests));
        MinecraftServer.spin(thread ->
                GameTestServer.create(thread, access, packs, GameTestRunner.groupTestsIntoBatches(tests), SPAWN));
    }

    /**
     * Freezes the registries now if the game has not: Fabric API's registry sync takes the freeze out of {@code
     * Bootstrap} so that Fabric mods register first, and its {@code MinecraftServer.runServer} mixin does it just
     * before the server sets up, which is after the tests are chosen here. {@code bootStrap} is what that mixin calls;
     * the registry sync cancels its second {@code createContents}, and the game layer's freeze hook runs once.
     */
    private static void freezeRegistriesIfPending() {
        if (!GameHooks.registriesFreezeHookRan()) {
            BuiltInRegistries.bootStrap();
        }
    }

    /** The pack sources of the game's own repository for this world, plus the folder of {@code --packs}. */
    private static PackRepository packRepository(LevelStorageSource.LevelStorageAccess access, String packsDirectory) {
        if (packsDirectory == null) {
            return ServerPacksSource.createPackRepository(access);
        }
        return new PackRepository(
                new ServerPacksSource(),
                new FolderRepositorySource(
                        Path.of(packsDirectory).toAbsolutePath(), PackType.SERVER_DATA, PackSource.WORLD));
    }

    /** Runs the mod initialization that the version adapter bound, as the dedicated server's Main would. */
    private static void initialiseMods() throws ReflectiveOperationException {
        Class<?> hooks = Class.forName(MODS_HOOKS, true, GameTestMain.class.getClassLoader());
        hooks.getMethod("onModInitialization").invoke(null);
    }

    /** The registered functions that match {@code selectors}. */
    private static List<TestFunction> select(List<String> selectors) {
        Collection<TestFunction> functions = GameTestRegistry.getAllTestFunctions();
        List<TestFunction> selected = new ArrayList<>();
        for (String selector : selectors) {
            for (TestFunction function : functions) {
                if (matches(function.getTestName(), selector)) {
                    selected.add(function);
                }
            }
        }
        return List.copyOf(selected);
    }

    private static boolean matches(String name, String selector) {
        if (selector.equals("*") || selector.equals("*:*")) {
            return true;
        }
        if (selector.endsWith(":*")) {
            String namespace = selector.substring(0, selector.length() - 2);
            return name.startsWith(namespace + ":");
        }
        return name.equals(selector);
    }

    private static List<String> names() {
        return names(GameTestRegistry.getAllTestFunctions());
    }

    private static List<String> names(Collection<TestFunction> tests) {
        return tests.stream().map(TestFunction::getTestName).sorted().toList();
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /** The command line, read the way the game's Main reads it. */
    private record Arguments(String universe, List<String> selectors, String packs) {

        static Arguments parse(String[] args) {
            String universe = "universe";
            String packs = null;
            List<String> selectors = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--universe" -> universe = value(args, ++index, "--universe");
                    case "--packs" -> packs = value(args, ++index, "--packs");
                    case "--tests" -> {
                        for (String selector : value(args, ++index, "--tests").split(",")) {
                            if (!selector.isBlank()) {
                                selectors.add(selector.strip());
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown game test argument: " + args[index]);
                }
            }
            if (selectors.isEmpty()) {
                throw new IllegalArgumentException("--tests <selector> is required");
            }
            return new Arguments(universe, List.copyOf(selectors), packs);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[index];
        }
    }
}
