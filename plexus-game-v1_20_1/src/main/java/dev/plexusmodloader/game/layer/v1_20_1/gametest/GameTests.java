package dev.plexusmodloader.game.layer.v1_20_1.gametest;

import dev.plexusmodloader.api.devtools.gametest.TestArea;
import dev.plexusmodloader.game.version.layer.NativeDeclarations.GameTestDeclaration;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;

/**
 * Registers mod game tests as the game's test functions. Minecraft 1.20.1 keeps its tests in {@code
 * GameTestRegistry} (annotated methods turned into {@link TestFunction}s), where 26.x has a test registry and
 * data-driven test instances; its {@code TestFunction}s name a structure template, which for a Plexus Mod Loader test
 * is the empty structure {@code GeneratedDataPack} writes for the test's area.
 */
public final class GameTests {

    /** The batch every mod test runs in; tests of one batch run side by side. */
    static final String BATCH = "defaultBatch";

    /** The built-in test the verification tasks select: it passes at once. */
    public static final String ALWAYS_PASS = "minecraft:always_pass";

    private GameTests() {}

    /** The name of the empty structure for {@code area}; {@code GeneratedDataPack} writes its file. */
    public static String structureName(TestArea area) {
        return "plexus:empty_" + area.width() + "x" + area.height() + "x" + area.depth();
    }

    public static void install(List<GameTestDeclaration> tests) {
        // getAllTestFunctions() is the registry's own mutable list; GameTestRegistry has no method that adds a
        // ready-made TestFunction (javap on 1.20.1: register(Class) and register(Method) only).
        Collection<TestFunction> registry = GameTestRegistry.getAllTestFunctions();
        registry.add(function(ALWAYS_PASS, TestArea.SINGLE_BLOCK, 100, GameTestHelper::succeed));
        for (GameTestDeclaration test : tests) {
            Consumer<GameTestHelper> run = helper -> test.test().run(new GameTestContextView(helper));
            registry.add(function(test.id().toString(), test.area(), test.maxTicks(), run));
        }
    }

    private static TestFunction function(String name, TestArea area, int maxTicks, Consumer<GameTestHelper> run) {
        return new TestFunction(BATCH, name, structureName(area), Rotation.NONE, maxTicks, 1L, true, run);
    }
}
