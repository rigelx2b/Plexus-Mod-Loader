package dev.plexusmodloader.api.devtools.gametest;

import dev.plexusmodloader.api.entity.EntityView;
import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.stability.ApiStability;
import dev.plexusmodloader.api.stability.StabilityLevel;
import dev.plexusmodloader.api.world.BlockPos;
import dev.plexusmodloader.api.world.Direction;
import dev.plexusmodloader.api.world.InteractionResult;
import dev.plexusmodloader.api.world.WorldView;

/**
 * A running game test. Positions are relative to the test area; {@code (0, 0, 0)} is its lowest
 * corner and the area is at least 1×1×1 blocks plus room above.
 *
 * <p>Assertions throw an exception the game reports as a test failure with the position and message.
 */
@ApiStability(StabilityLevel.PREVIEW)
public interface GameTestContext {

    /** The world the test runs in. */
    WorldView world();

    /** Converts a test-relative position into a world position. */
    BlockPos absolute(BlockPos relative);

    /** Places the default state of {@code block} at a test-relative position. */
    void setBlock(BlockPos relative, ResourceId block);

    /** Fails unless the block at a test-relative position is {@code expected}. */
    void assertBlock(BlockPos relative, ResourceId expected);

    /** Fails with {@code message} unless {@code condition} holds. */
    void assertTrue(boolean condition, String message);

    /** Creates a survival-mode player in the test world that is not connected to any client. */
    PlayerView createMockPlayer();

    /**
     * Creates a survival-mode server player that is not connected and not in the world, for what
     * needs one, such as advancements.
     */
    PlayerView createMockServerPlayer();

    /**
     * Creates a survival-mode player that joins the server like a client over an in-memory connection
     * ({@code PlayerEvents.JOINED} fires) and reports that it finished loading, so it can take damage.
     * It stands where the game puts new players. Interactions work as for {@link
     * #createMockServerPlayer()}.
     *
     * <p>While connected it affects the whole test world like a real player: mobs far from every player
     * despawn and mobs spawn near it, which disturbs other tests running at the same time. Call {@link
     * #disconnect} before the test function returns; players still connected when the test ends are
     * disconnected then.
     */
    PlayerView createConnectedMockPlayer();

    /**
     * Respawns a player from {@link #createConnectedMockPlayer()} as its client asks from the death
     * screen (or after the End credits): the game builds a new player object, {@code
     * PlayerEvents.COPY} and {@code RESPAWNED} fire, and the new player reports that it loaded.
     *
     * @param returnedFromEnd {@code true} to respawn as when leaving the End (everything is kept);
     *     {@code false} to respawn after death
     * @return the new player
     * @throws IllegalArgumentException when {@code player} is not a connected mock player of this test
     * @throws IllegalStateException when respawning after death and the player is alive
     */
    PlayerView respawn(PlayerView player, boolean returnedFromEnd);

    /**
     * Disconnects a player from {@link #createConnectedMockPlayer()} ({@code PlayerEvents.LEAVING}
     * fires); does nothing for a player that already left.
     *
     * @throws IllegalArgumentException when {@code player} is not a connected mock player of this test
     */
    void disconnect(PlayerView player);

    /** Makes {@code player} use (right-click) the block at a test-relative position with an empty hand. */
    void useBlock(BlockPos relative, PlayerView player);

    /**
     * Makes a server player (from {@link #createMockServerPlayer()}) right-click a face of the block at
     * a test-relative position with its main hand, the way the server handles the click from a
     * connected client: interaction events first, then the held item and the block. Unlike {@link
     * #useBlock}, a held block item is placed.
     *
     * @return the result the game reached, mapped onto the Native API's result
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    InteractionResult interactBlock(PlayerView player, BlockPos relative, Direction face);

    /**
     * Makes a server player use the item in its main hand in the air, the way the server handles the
     * click from a connected client (interaction events, then the item).
     *
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    InteractionResult interactItem(PlayerView player);

    /**
     * Makes a server player right-click {@code target} with its main hand, the way the server
     * handles the click from a connected client (interaction events, then the entity and the item).
     *
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    InteractionResult interactEntity(PlayerView player, EntityView target);

    /**
     * Makes a server player attack {@code target} with its main hand, as a left-click does
     * (interaction events, then damage and knockback).
     *
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    void attackEntity(PlayerView player, EntityView target);

    /**
     * Makes a server player start digging the block at a test-relative position, as pressing the
     * left mouse button on it does (the {@code ATTACK_BLOCK} event, then the game starts breaking
     * it; a block that breaks at once, such as a torch, is gone afterwards). The player needs a
     * connection, so use a player from {@link #createConnectedMockPlayer()} and {@link #disconnect}
     * it before the test function returns.
     *
     * @throws IllegalArgumentException when {@code player} is not a connected mock player of this test
     */
    void attackBlock(PlayerView player, BlockPos relative, Direction face);

    /**
     * Builds a vanilla creative inventory tab as a client does and lists its items in order, including the
     * items mods list in it ({@code ItemDefinition.Builder.creativeTab}). Server-side, so it needs no window.
     */
    java.util.List<ResourceId> creativeTabItems(dev.plexusmodloader.api.content.CreativeTab tab);

    /**
     * Runs one tick of a player from {@link #createConnectedMockPlayer()} the way the game ticks a
     * player that is in a world (the level's tick and the connection's tick of the player, so equipment
     * attributes update), so {@code PlayerEvents.TICK} fires once. Call it a few times and
     * {@link #disconnect} within the same test function to keep the player from joining the world's
     * own ticks, which would make far-away mobs of other tests despawn.
     *
     * @throws IllegalArgumentException when {@code player} is not a connected mock player of this test
     */
    void tickPlayer(PlayerView player);

    /**
     * Makes a server player send a chat message as a typed message reaches the server ({@code
     * ChatEvents.MESSAGE} fires, then the server delivers it to everyone). Works for a player from {@link
     * #createMockServerPlayer()} too, as the message goes through the server's player list and needs no connection.
     *
     * @param player the sender
     * @param text what the player types
     * @return the text the server went on to deliver (as clients show it after the sender's name), or empty when a
     *     listener cancelled the message
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    java.util.Optional<String> sendChat(PlayerView player, String text);

    /**
     * Sends a system message (as command feedback or a server announcement is sent) to a server player and returns the
     * text the player would have received after {@code ChatEvents.SYSTEM_MESSAGE} listeners ran, or empty when a listener
     * cancelled it. The message is not delivered to the player's connection.
     *
     * @param overlay {@code true} to send it as an action bar message
     */
    java.util.Optional<String> sendSystemMessage(PlayerView player, String text, boolean overlay);

    /**
     * Puts an elytra on a server player, lifts it off the ground, and asks the game to start gliding as the jump key
     * pressed in the air does ({@code PlayerEvents.ALLOW_FALL_FLYING} fires). The player is not connected, so nothing
     * moves it afterwards.
     *
     * @return {@code true} when the player is gliding
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    boolean startGliding(PlayerView player);

    /**
     * Creates a villager of a profession and level, as the game does when a villager first needs its offers, and
     * returns the offers it drew from the pool (vanilla trades and the ones mods added with {@code TradeRegistrar}).
     * The pool is drawn at random, so a trade added to it appears in some calls and not in others; call it in a loop.
     * The villager is not added to the world.
     *
     * @param profession the profession, for example {@code minecraft:farmer}
     * @param level the villager level, 1 to 5
     * @throws IllegalArgumentException when the profession is not registered
     */
    java.util.List<dev.plexusmodloader.api.trade.TradeOffer> villagerOffers(ResourceId profession, int level);

    /**
     * Creates a wandering trader and returns the offers it drew from its common and rare pools. Random, like {@link
     * #villagerOffers}. The trader is not added to the world.
     */
    java.util.List<dev.plexusmodloader.api.trade.TradeOffer> wanderingTraderOffers();

    /**
     * Wakes a sleeping server player as leaving the bed does ({@code EntityEvents.STOPPING_SLEEP} fires); does nothing
     * for a player who is not asleep. To put a player to bed, use {@link #useBlock} on the bed.
     *
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    void wakeUp(PlayerView player);

    /**
     * Makes a server player break the block at a test-relative position at once, the way the server
     * finishes a player's digging: block events, the game's checks, drops in survival mode.
     *
     * @return whether the block was broken
     * @throws IllegalArgumentException when {@code player} is not a server player
     */
    boolean breakBlock(PlayerView player, BlockPos relative);

    /**
     * Runs a command as the server at the test area's origin.
     *
     * @return the command's result value; 0 when it failed
     */
    int runCommand(String command);

    /** Runs {@code action} after {@code ticks} more ticks, on the server thread. */
    void runAfter(int ticks, Runnable action);

    /** Marks the test passed. */
    void succeed();

    /** Marks the test failed. */
    void fail(String message);
}
