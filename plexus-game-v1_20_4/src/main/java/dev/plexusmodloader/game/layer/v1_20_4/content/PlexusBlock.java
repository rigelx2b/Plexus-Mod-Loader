package dev.plexusmodloader.game.layer.v1_20_4.content;

import dev.plexusmodloader.api.content.block.BlockBehavior;
import dev.plexusmodloader.api.content.block.BlockDefinition;
import dev.plexusmodloader.api.content.block.BlockShape;
import dev.plexusmodloader.api.content.block.BlockUse;
import dev.plexusmodloader.api.content.block.state.BlockProperty;
import dev.plexusmodloader.api.content.block.state.BlockStateView;
import dev.plexusmodloader.game.layer.v1_20_4.Views;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A mod block: a game block whose state properties come from the {@link BlockDefinition} and whose
 * callbacks forward to the mod's {@link BlockBehavior}.
 */
class PlexusBlock extends Block {

    /**
     * The definition of the block being constructed. {@code Block}'s constructor calls {@link
     * #createBlockStateDefinition} before this class's fields are assigned, so the properties are
     * handed over through this thread-local.
     */
    private static final ThreadLocal<BlockDefinition> CONSTRUCTING = new ThreadLocal<>();

    private final BlockDefinition definition;
    private final Map<BlockProperty<?>, Property<?>> gameProperties;

    /** Outline and collision per state, when the definition gives shapes; computed once. */
    private final Map<BlockState, VoxelShape> shapes;

    private final Map<BlockState, VoxelShape> collisionShapes;

    PlexusBlock(Properties properties, BlockDefinition definition) {
        super(beginConstruction(properties, definition));
        CONSTRUCTING.remove();
        this.definition = definition;
        Map<BlockProperty<?>, Property<?>> byProperty = new LinkedHashMap<>();
        for (BlockProperty<?> property : definition.properties()) {
            byProperty.put(property, getStateDefinition().getProperty(property.name()));
        }
        this.gameProperties = Map.copyOf(byProperty);
        BlockState defaults = getStateDefinition().any();
        for (BlockProperty<?> property : definition.properties()) {
            defaults = withDefault(defaults, property);
        }
        registerDefaultState(defaults);
        this.shapes = shapes(definition.shape());
        this.collisionShapes = definition.collisionShape().isPresent() ? shapes(definition.collisionShape()) : shapes;
    }

    private Map<BlockState, VoxelShape> shapes(Optional<Function<BlockStateView, BlockShape>> byState) {
        if (byState.isEmpty()) {
            return Map.of();
        }
        Map<BlockState, VoxelShape> computed = new IdentityHashMap<>();
        for (BlockState state : getStateDefinition().getPossibleStates()) {
            BlockShape shape;
            try {
                shape = byState.get().apply(BlockStates.view(state));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "PLEXUS-CONTENT-006 the shape function of a mod block failed for " + state, e);
            }
            if (shape == null) {
                throw new IllegalStateException(
                        "PLEXUS-CONTENT-006 the shape function of a mod block returned null for " + state);
            }
            computed.put(state, toGame(shape));
        }
        return computed;
    }

    static VoxelShape toGame(BlockShape shape) {
        VoxelShape result = Shapes.empty();
        for (BlockShape.Box box : shape.boxes()) {
            result = Shapes.or(
                    result, Block.box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()));
        }
        return result.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = shapes.get(state);
        return shape != null ? shape : super.getShape(state, level, pos, context);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!definition.collision()) {
            return Shapes.empty();
        }
        VoxelShape shape = collisionShapes.get(state);
        return shape != null ? shape : super.getCollisionShape(state, level, pos, context);
    }

    private static Properties beginConstruction(Properties properties, BlockDefinition definition) {
        CONSTRUCTING.set(definition);
        return properties;
    }

    private static <T> BlockState withDefault(BlockState state, BlockProperty<T> property) {
        return BlockStates.with(state, property, property.defaultValue());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        BlockDefinition constructing = CONSTRUCTING.get();
        if (constructing != null) {
            for (BlockProperty<?> property : constructing.properties()) {
                builder.add(BlockStates.create(property));
            }
        }
    }

    BlockDefinition definition() {
        return definition;
    }

    BlockBehavior behavior() {
        return definition.behavior();
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state != null && definition.placementFacing().isPresent()) {
            state = BlockStates.with(
                    state,
                    definition.placementFacing().get(),
                    BlockStates.fromGame(context.getHorizontalDirection().getOpposite()));
        }
        return state;
    }

    /**
     * A right-click on the block. The game tries the main hand first and then the off hand; the mod's behavior runs
     * once, for the main hand, as it does when the game asks a block to use a click without an item.
     */
    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(
            BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) {
            return super.use(state, level, pos, player, hand, hit);
        }
        return Interactions.toGame(
                behavior().onUse(new BlockUse(Views.world(level), Views.pos(pos), Views.player(player))),
                () -> super.use(state, level, pos, player, hand, hit));
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        behavior().onSteppedOn(Views.world(level), Views.pos(pos), Views.entity(entity));
        super.stepOn(level, pos, state, entity);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (placer != null) {
            behavior().onPlaced(Views.world(level), Views.pos(pos), Views.entity(placer));
        }
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return definition.randomTicks();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        behavior().onRandomTick(Views.world(level), Views.pos(pos));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(
            BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos fromPos, boolean moved) {
        super.neighborChanged(state, level, pos, neighbor, fromPos, moved);
        behavior().onNeighborChanged(Views.world(level), Views.pos(pos));
    }

    @Override
    public String toString() {
        return "PlexusBlock" + gameProperties.keySet();
    }
}
