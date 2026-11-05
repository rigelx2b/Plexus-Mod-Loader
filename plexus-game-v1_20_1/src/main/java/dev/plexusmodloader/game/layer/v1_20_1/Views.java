package dev.plexusmodloader.game.layer.v1_20_1;

import dev.plexusmodloader.api.content.EntityDataKey;
import dev.plexusmodloader.api.content.attribute.AttributeOperation;
import dev.plexusmodloader.api.content.block.entity.BlockEntityView;
import dev.plexusmodloader.api.content.block.state.BlockProperty;
import dev.plexusmodloader.api.content.block.state.BlockStateView;
import dev.plexusmodloader.api.content.rule.GameRuleKey;
import dev.plexusmodloader.api.data.DataTree;
import dev.plexusmodloader.api.entity.EntityView;
import dev.plexusmodloader.api.entity.LivingEntityView;
import dev.plexusmodloader.api.entity.MobView;
import dev.plexusmodloader.api.entity.PlayerView;
import dev.plexusmodloader.api.inventory.InventoryView;
import dev.plexusmodloader.api.inventory.ItemStackRef;
import dev.plexusmodloader.api.resource.ResourceId;
import dev.plexusmodloader.api.world.BlockPos;
import dev.plexusmodloader.api.world.StorageOptions;
import dev.plexusmodloader.api.world.StoredData;
import dev.plexusmodloader.api.world.Vector3;
import dev.plexusmodloader.api.world.WorldView;
import dev.plexusmodloader.game.layer.v1_20_1.content.BlockStates;
import dev.plexusmodloader.game.layer.v1_20_1.content.Inventories;
import dev.plexusmodloader.game.layer.v1_20_1.content.ItemComponents;
import dev.plexusmodloader.game.layer.v1_20_1.content.ModBlockEntity;
import dev.plexusmodloader.game.layer.v1_20_1.entity.EntityInstaller;
import dev.plexusmodloader.game.layer.v1_20_1.entity.PlexusMob;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Converts between game objects and the Native API's views and value types. */
public final class Views {

    private Views() {}

    public static WorldView world(Level level) {
        return new LevelView(level);
    }

    public static PlayerView player(Player player) {
        return new PlayerEntityView(player);
    }

    /** A view of {@code entity}: a {@link PlayerView}, a {@link LivingEntityView}, or a plain {@link EntityView}. */
    public static EntityView entity(Entity entity) {
        return switch (entity) {
            case Player player -> player(player);
            case Mob mob -> new MobEntityView(mob);
            case LivingEntity living -> new LivingView(living);
            default -> new GenericEntityView(entity);
        };
    }

    /** The game player behind a view created by this layer. */
    public static Player gamePlayer(PlayerView view) {
        if (view instanceof PlayerEntityView player) {
            return player.player;
        }
        throw new IllegalArgumentException("Not a player view created by Plexus Mod Loader: " + view);
    }

    public static BlockPos pos(net.minecraft.core.BlockPos pos) {
        return new BlockPos(pos.getX(), pos.getY(), pos.getZ());
    }

    public static net.minecraft.core.BlockPos pos(BlockPos pos) {
        return new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static ResourceId id(ResourceLocation id) {
        return ResourceId.of(id.getNamespace(), id.getPath());
    }

    public static ResourceLocation id(ResourceId id) {
        return new ResourceLocation(id.namespace(), id.path());
    }

    public static ResourceId blockId(Block block) {
        return id(BuiltInRegistries.BLOCK.getKey(block));
    }

    /** The registered block with this ID; {@link IllegalArgumentException} when there is none. */
    public static Block block(ResourceId id) {
        ResourceLocation identifier = id(id);
        if (!BuiltInRegistries.BLOCK.containsKey(identifier)) {
            throw new IllegalArgumentException("No block " + id + " is registered");
        }
        return BuiltInRegistries.BLOCK.get(identifier);
    }

    /** The registered item with this ID; {@link IllegalArgumentException} when there is none. */
    public static Item item(ResourceId id) {
        ResourceLocation identifier = id(id);
        if (!BuiltInRegistries.ITEM.containsKey(identifier)) {
            throw new IllegalArgumentException("No item " + id + " is registered");
        }
        return BuiltInRegistries.ITEM.get(identifier);
    }

    /** The registered status effect with this ID. */
    static MobEffect effect(ResourceId id) {
        return BuiltInRegistries.MOB_EFFECT
                .getOptional(id(id))
                .orElseThrow(() -> new IllegalArgumentException("No effect " + id + " is registered"));
    }

    /**
     * The registered attribute with this ID. 1.20.1 names the game's attributes with a group ({@code
     * minecraft:generic.max_health}, {@code minecraft:player.block_interaction_range}); 26.x names them plainly, which
     * is what the Native API uses, so a {@code minecraft:} ID that is not registered as is looked up under a group.
     */
    static Attribute attribute(ResourceId id) {
        java.util.Optional<Attribute> found = BuiltInRegistries.ATTRIBUTE.getOptional(id(id));
        if (found.isEmpty() && id.namespace().equals("minecraft")) {
            for (String group : List.of("generic.", "player.", "zombie.")) {
                found = BuiltInRegistries.ATTRIBUTE.getOptional(id(ResourceId.minecraft(group + id.path())));
                if (found.isPresent()) {
                    break;
                }
            }
        }
        return found.orElseThrow(() -> new IllegalArgumentException("No attribute " + id + " is registered"));
    }

    /**
     * The UUID that stands for a modifier ID: 1.20.1 identifies attribute modifiers by UUID, so the Native API's
     * modifier ID maps to a fixed name-based UUID, which also survives saving and loading.
     */
    static UUID modifierUuid(ResourceId modifier) {
        return UUID.nameUUIDFromBytes(("plexus:attribute_modifier/" + modifier).getBytes(StandardCharsets.UTF_8));
    }

    private static AttributeModifier.Operation operation(AttributeOperation operation) {
        return switch (operation) {
            case ADD_VALUE -> AttributeModifier.Operation.ADDITION;
            case ADD_MULTIPLIED_BASE -> AttributeModifier.Operation.MULTIPLY_BASE;
            case ADD_MULTIPLIED_TOTAL -> AttributeModifier.Operation.MULTIPLY_TOTAL;
        };
    }

    private static ServerLevel serverLevel(Level level) {
        if (level instanceof ServerLevel server) {
            return server;
        }
        throw new IllegalStateException("Only available on the server side");
    }

    private record LevelView(Level level) implements WorldView {

        @Override
        public ResourceId dimension() {
            return id(level.dimension().location());
        }

        @Override
        public boolean isClientSide() {
            return level.isClientSide();
        }

        @Override
        public ResourceId blockAt(BlockPos pos) {
            return blockId(level.getBlockState(pos(pos)).getBlock());
        }

        @Override
        public boolean setBlock(BlockPos pos, ResourceId block) {
            Block gameBlock = block(block);
            if (level.isClientSide()) {
                throw new IllegalStateException("The client copy of the world cannot be changed");
            }
            return level.setBlock(pos(pos), gameBlock.defaultBlockState(), Block.UPDATE_ALL);
        }

        @Override
        public long gameTime() {
            return level.getGameTime();
        }

        @Override
        public List<PlayerView> players() {
            return level.players().stream().map(Views::player).toList();
        }

        @Override
        public BlockStateView stateAt(BlockPos pos) {
            return BlockStates.view(level.getBlockState(pos(pos)));
        }

        @Override
        public <T> boolean setProperty(BlockPos pos, BlockProperty<T> property, T value) {
            if (level.isClientSide()) {
                throw new IllegalStateException("The client copy of the world cannot be changed");
            }
            net.minecraft.core.BlockPos gamePos = pos(pos);
            BlockState current = level.getBlockState(gamePos);
            BlockState changed = BlockStates.with(current, property, value);
            return changed != current && level.setBlock(gamePos, changed, Block.UPDATE_ALL);
        }

        @Override
        public Optional<BlockEntityView> blockEntityAt(BlockPos pos) {
            return level.getBlockEntity(pos(pos)) instanceof ModBlockEntity entity
                    ? Optional.of(entity.view())
                    : Optional.empty();
        }

        @Override
        public StoredData storage(ResourceId id) {
            return WorldServices.storage(serverLevel(level), id);
        }

        @Override
        public StoredData storage(ResourceId id, StorageOptions options) {
            if (!level.isClientSide()) {
                return WorldServices.storage(serverLevel(level), id, options);
            }
            DataTree copy = SyncChannel.storage(dimension(), id).orElseGet(DataTree::create);
            return new StoredData() {
                @Override
                public DataTree data() {
                    return copy;
                }

                @Override
                public void markChanged() {
                    throw new IllegalStateException("World storage is changed on the server");
                }
            };
        }

        @Override
        public void schedule(int ticks, Runnable task) {
            WorldServices.schedule(serverLevel(level), ticks, task);
        }

        @Override
        public EntityView spawnEntity(ResourceId type, double x, double y, double z) {
            ServerLevel server = serverLevel(level);
            ResourceLocation identifier = id(type);
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) {
                throw new IllegalArgumentException("No entity type " + type + " is registered");
            }
            EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(identifier);
            Entity entity = entityType.create(server);
            if (entity == null) {
                throw new IllegalArgumentException("Entity type " + type + " cannot be spawned");
            }
            entity.moveTo(x, y, z, 0.0f, 0.0f);
            server.addFreshEntity(entity);
            return entity(entity);
        }

        @Override
        public List<EntityView> entitiesNear(double x, double y, double z, double radius) {
            AABB box = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
            double squared = radius * radius;
            return level.getEntitiesOfClass(Entity.class, box).stream()
                    .filter(entity -> entity.distanceToSqr(x, y, z) <= squared)
                    .map(Views::entity)
                    .toList();
        }

        @Override
        public void playSound(BlockPos pos, ResourceId sound, float volume, float pitch) {
            SoundEvent event = BuiltInRegistries.SOUND_EVENT
                    .getOptional(id(sound))
                    .orElseThrow(() -> new IllegalArgumentException("No sound event " + sound + " is registered"));
            if (level.isClientSide()) {
                level.playLocalSound(
                        pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5, event, SoundSource.BLOCKS, volume, pitch, false);
            } else {
                level.playSound(null, pos(pos), event, SoundSource.BLOCKS, volume, pitch);
            }
        }

        @Override
        public void spawnParticles(ResourceId particle, double x, double y, double z, int count, double spread) {
            ServerLevel server = serverLevel(level);
            if (!(BuiltInRegistries.PARTICLE_TYPE.getOptional(id(particle)).orElse(null)
                    instanceof SimpleParticleType simple)) {
                throw new IllegalArgumentException(particle + " is not a particle without options");
            }
            server.sendParticles(simple, x, y, z, count, spread, spread, spread, 0.0);
        }

        @Override
        public Optional<WorldView> otherDimension(ResourceId dimension) {
            ServerLevel other =
                    serverLevel(level).getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id(dimension)));
            return Optional.ofNullable(other).map(Views::world);
        }

        @Override
        public <T> T gameRule(GameRuleKey<T> rule) {
            return EntityInstaller.value(serverLevel(level), rule);
        }

        @Override
        public <T> void setGameRule(GameRuleKey<T> rule, T value) {
            EntityInstaller.set(serverLevel(level), rule, value);
        }

        @Override
        public boolean setChunkForced(int chunkX, int chunkZ, boolean forced) {
            return serverLevel(level).setChunkForced(chunkX, chunkZ, forced);
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return level.hasChunk(chunkX, chunkZ);
        }

        @Override
        public ResourceId biomeAt(BlockPos pos) {
            return level.getBiome(pos(pos))
                    .unwrapKey()
                    .map(key -> id(key.location()))
                    .orElse(ResourceId.minecraft("plains"));
        }

        @Override
        public dev.plexusmodloader.api.tag.TagView tags() {
            return GameTags.VIEW;
        }

        @Override
        public boolean isBlockIn(BlockPos pos, dev.plexusmodloader.api.tag.TagKey tag) {
            return GameTags.isIn(level.getBlockState(pos(pos)).getBlock(), tag);
        }

        @Override
        public int emittedLight(BlockPos pos) {
            return level.getBlockState(pos(pos)).getLightEmission();
        }

        @Override
        public List<ResourceId> naturalSpawns(
                BlockPos pos, dev.plexusmodloader.api.content.entity.MobCategory category) {
            return level
                    .getBiome(pos(pos))
                    .value()
                    .getMobSettings()
                    .getMobs(EntityInstaller.category(category))
                    .unwrap()
                    .stream()
                    .map(weighted -> id(BuiltInRegistries.ENTITY_TYPE.getKey(weighted.type)))
                    .toList();
        }
    }

    private static class GenericEntityView implements EntityView {

        final Entity entity;

        GenericEntityView(Entity entity) {
            this.entity = entity;
        }

        @Override
        public UUID uuid() {
            return entity.getUUID();
        }

        @Override
        public ResourceId type() {
            return id(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }

        @Override
        public boolean isIn(dev.plexusmodloader.api.tag.TagKey tag) {
            return GameTags.isIn(entity.getType(), tag);
        }

        @Override
        public String name() {
            return entity.getName().getString();
        }

        @Override
        public double x() {
            return entity.getX();
        }

        @Override
        public double y() {
            return entity.getY();
        }

        @Override
        public double z() {
            return entity.getZ();
        }

        @Override
        public BlockPos blockPos() {
            return pos(entity.blockPosition());
        }

        @Override
        public WorldView world() {
            return Views.world(entity.level());
        }

        @Override
        public boolean isAlive() {
            return entity.isAlive();
        }

        @Override
        public void remove() {
            serverLevel(entity.level());
            entity.discard();
        }

        @Override
        public void teleport(double x, double y, double z) {
            serverLevel(entity.level());
            entity.teleportTo(x, y, z);
        }

        @Override
        public EntityView changeDimension(ResourceId dimension, double x, double y, double z) {
            ServerLevel target = serverLevel(entity.level())
                    .getServer()
                    .getLevel(ResourceKey.create(Registries.DIMENSION, id(dimension)));
            if (target == null) {
                throw new IllegalArgumentException("No loaded dimension " + dimension);
            }
            if (entity instanceof ServerPlayer player) {
                // A player moves in place; its object stays the same.
                player.teleportTo(target, x, y, z, entity.getYRot(), entity.getXRot());
                return entity(player);
            }
            if (target == entity.level()) {
                entity.teleportTo(x, y, z);
                return this;
            }
            // 1.20.1 moves any other entity between dimensions by making a new one, as Entity.teleportTo(ServerLevel,
            // ...) does, but that method does not return it, and the new entity is not found by UUID while its chunk is
            // not loaded.
            Entity moved = entity.getType().create(target);
            if (moved == null) {
                throw new IllegalStateException(type() + " could not change dimension");
            }
            entity.unRide();
            moved.restoreFrom(entity);
            moved.moveTo(x, y, z, entity.getYRot(), entity.getXRot());
            moved.setYHeadRot(entity.getYRot());
            entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
            target.addDuringTeleport(moved);
            return entity(moved);
        }

        @Override
        public Vector3 velocity() {
            Vec3 movement = entity.getDeltaMovement();
            return new Vector3(movement.x, movement.y, movement.z);
        }

        @Override
        public void setVelocity(double x, double y, double z) {
            serverLevel(entity.level());
            entity.setDeltaMovement(x, y, z);
            entity.hurtMarked = true;
        }

        @Override
        public boolean isOnGround() {
            return entity.onGround();
        }

        @Override
        public float yaw() {
            return entity.getYRot();
        }

        @Override
        public float pitch() {
            return entity.getXRot();
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            serverLevel(entity.level());
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            entity.setYHeadRot(yaw);
            if (entity instanceof ServerPlayer player && player.connection != null) {
                // Players turn on their client; tell it.
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch);
            }
        }

        @Override
        public boolean hasNoGravity() {
            return entity.isNoGravity();
        }

        @Override
        public void setNoGravity(boolean noGravity) {
            serverLevel(entity.level());
            entity.setNoGravity(noGravity);
        }

        @Override
        public Optional<EntityView> vehicle() {
            return Optional.ofNullable(entity.getVehicle()).map(Views::entity);
        }

        @Override
        public List<EntityView> passengers() {
            return entity.getPassengers().stream().map(Views::entity).toList();
        }

        @Override
        public boolean startRiding(EntityView vehicle) {
            serverLevel(entity.level());
            if (!(vehicle instanceof GenericEntityView other)) {
                throw new IllegalArgumentException("Not an entity view created by Plexus Mod Loader: " + vehicle);
            }
            return entity.startRiding(other.entity);
        }

        @Override
        public void stopRiding() {
            entity.stopRiding();
        }

        @Override
        public StoredData data(EntityDataKey key) {
            return EntityData.of(entity, key);
        }

        @Override
        public String toString() {
            return type() + " " + name();
        }
    }

    private static class LivingView extends GenericEntityView implements LivingEntityView {

        final LivingEntity living;

        LivingView(LivingEntity living) {
            super(living);
            this.living = living;
        }

        @Override
        public float health() {
            return living.getHealth();
        }

        @Override
        public float maxHealth() {
            return living.getMaxHealth();
        }

        @Override
        public void setHealth(float health) {
            serverLevel(living.level());
            living.setHealth(health);
        }

        @Override
        public boolean hurt(float amount) {
            ServerLevel server = serverLevel(living.level());
            return living.hurt(server.damageSources().generic(), amount);
        }

        @Override
        public boolean hurt(ResourceId damageType, float amount) {
            ServerLevel server = serverLevel(living.level());
            Holder<DamageType> type = server.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolder(ResourceKey.create(Registries.DAMAGE_TYPE, id(damageType)))
                    .orElseThrow(() -> new IllegalArgumentException("No damage type " + damageType));
            return living.hurt(new DamageSource(type), amount);
        }

        @Override
        public void addEffect(ResourceId effect, int ticks, int amplifier) {
            MobEffect gameEffect = effect(effect);
            serverLevel(living.level());
            living.addEffect(new MobEffectInstance(gameEffect, ticks, amplifier));
        }

        @Override
        public boolean hasEffect(ResourceId effect) {
            return living.hasEffect(effect(effect));
        }

        @Override
        public OptionalDouble attribute(ResourceId attribute) {
            AttributeInstance instance = living.getAttribute(Views.attribute(attribute));
            return instance == null ? OptionalDouble.empty() : OptionalDouble.of(instance.getValue());
        }

        @Override
        public OptionalDouble attributeBase(ResourceId attribute) {
            AttributeInstance instance = living.getAttribute(Views.attribute(attribute));
            return instance == null ? OptionalDouble.empty() : OptionalDouble.of(instance.getBaseValue());
        }

        @Override
        public void setAttributeBase(ResourceId attribute, double value) {
            AttributeInstance instance = carriedAttribute(attribute);
            serverLevel(living.level());
            instance.setBaseValue(value);
        }

        @Override
        public void addAttributeModifier(
                ResourceId attribute,
                ResourceId modifier,
                double amount,
                AttributeOperation operation,
                boolean persistent) {
            AttributeInstance instance = carriedAttribute(attribute);
            serverLevel(living.level());
            UUID modifierId = modifierUuid(modifier);
            AttributeModifier gameModifier =
                    new AttributeModifier(modifierId, modifier.toString(), amount, operation(operation));
            instance.removeModifier(modifierId);
            if (persistent) {
                instance.addPermanentModifier(gameModifier);
            } else {
                instance.addTransientModifier(gameModifier);
            }
        }

        @Override
        public boolean removeAttributeModifier(ResourceId attribute, ResourceId modifier) {
            Attribute gameAttribute = Views.attribute(attribute);
            serverLevel(living.level());
            AttributeInstance instance = living.getAttribute(gameAttribute);
            UUID modifierId = modifierUuid(modifier);
            if (instance == null || instance.getModifier(modifierId) == null) {
                return false;
            }
            instance.removeModifier(modifierId);
            return true;
        }

        private AttributeInstance carriedAttribute(ResourceId attribute) {
            AttributeInstance instance = living.getAttribute(Views.attribute(attribute));
            if (instance == null) {
                throw new IllegalArgumentException(type() + " does not carry the attribute " + attribute);
            }
            return instance;
        }

        @Override
        public EntityView launchProjectile(ResourceId type, float speed, float inaccuracy) {
            ServerLevel server = serverLevel(living.level());
            ResourceLocation identifier = id(type);
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) {
                throw new IllegalArgumentException("No entity type " + type + " is registered");
            }
            if (!(BuiltInRegistries.ENTITY_TYPE.get(identifier).create(server) instanceof Projectile projectile)) {
                throw new IllegalArgumentException(type + " is not a projectile");
            }
            projectile.setOwner(living);
            projectile.setPos(living.getX(), living.getEyeY() - 0.1, living.getZ());
            projectile.shootFromRotation(living, living.getXRot(), living.getYRot(), 0.0f, speed, inaccuracy);
            server.addFreshEntity(projectile);
            return entity(projectile);
        }
    }

    private static final class MobEntityView extends LivingView implements MobView {

        private final Mob mob;

        MobEntityView(Mob mob) {
            super(mob);
            this.mob = mob;
        }

        @Override
        public boolean navigateTo(double x, double y, double z, double speed) {
            serverLevel(mob.level());
            return mob.getNavigation().moveTo(x, y, z, speed);
        }

        @Override
        public boolean isNavigating() {
            return !mob.getNavigation().isDone();
        }

        @Override
        public void stopNavigating() {
            mob.getNavigation().stop();
        }

        @Override
        public void lookAt(double x, double y, double z) {
            mob.getLookControl().setLookAt(x, y, z);
        }

        @Override
        public Optional<LivingEntityView> target() {
            return Optional.ofNullable(mob.getTarget()).map(target -> (LivingEntityView) entity(target));
        }

        @Override
        public void playAnimation(String name) {
            serverLevel(mob.level());
            modMob().playAnimation(name);
        }

        @Override
        public void stopAnimation(String name) {
            serverLevel(mob.level());
            modMob().stopAnimation(name);
        }

        private PlexusMob modMob() {
            if (mob instanceof PlexusMob modMob) {
                return modMob;
            }
            throw new IllegalArgumentException(mob + " is not a mod mob, so it has no model animations");
        }

        @Override
        public void setTarget(LivingEntityView target) {
            serverLevel(mob.level());
            if (target == null) {
                mob.setTarget(null);
            } else if (target instanceof LivingView living) {
                mob.setTarget(living.living);
            } else {
                throw new IllegalArgumentException("Not an entity view created by Plexus Mod Loader: " + target);
            }
        }
    }

    private static final class PlayerEntityView extends LivingView implements PlayerView {

        private final Player player;

        PlayerEntityView(Player player) {
            super(player);
            this.player = player;
        }

        @Override
        public void sendMessage(String text) {
            player.displayClientMessage(Component.literal(text), false);
        }

        @Override
        public void give(ResourceId item, int count) {
            Item gameItem = item(item);
            if (count < 1) {
                throw new IllegalArgumentException("count must be >= 1: " + count);
            }
            if (player.level().isClientSide()) {
                throw new IllegalStateException("Items can only be given on the server");
            }
            int left = count;
            while (left > 0) {
                ItemStack stack = new ItemStack(gameItem, Math.min(left, gameItem.getMaxStackSize()));
                left -= stack.getCount();
                if (!player.getInventory().add(stack)) {
                    player.drop(stack, false);
                }
            }
        }

        @Override
        public int countItem(ResourceId item) {
            Item gameItem = item(item);
            Inventory inventory = player.getInventory();
            int total = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.getItem() == gameItem) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        @Override
        public ResourceId mainHandItem() {
            return id(BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()));
        }

        @Override
        public ItemStackRef mainHandStack() {
            return ItemComponents.ref(
                    player.getMainHandItem(), () -> player.getInventory().setChanged());
        }

        @Override
        public InventoryView inventory() {
            return Inventories.view(player.getInventory());
        }

        @Override
        public boolean openMenu(BlockPos pos) {
            if (player.level().isClientSide()) {
                throw new IllegalStateException("Menus are opened on the server");
            }
            if (!(player.level().getBlockEntity(pos(pos)) instanceof MenuProvider provider)) {
                return false;
            }
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(provider);
            } else {
                // Game test mock players have no connection; give them the menu directly.
                AbstractContainerMenu menu = provider.createMenu(0, player.getInventory(), player);
                if (menu == null) {
                    return false;
                }
                player.containerMenu = menu;
            }
            return true;
        }

        private Advancement advancement(ResourceId id) {
            ServerPlayer serverPlayer = serverPlayer();
            Advancement holder =
                    serverPlayer.level().getServer().getAdvancements().getAdvancement(id(id));
            if (holder == null) {
                throw new IllegalArgumentException("No advancement " + id + " is loaded");
            }
            return holder;
        }

        private ServerPlayer serverPlayer() {
            if (player instanceof ServerPlayer serverPlayer) {
                return serverPlayer;
            }
            throw new IllegalStateException("Advancements belong to server players");
        }

        @Override
        public boolean hasPermission(dev.plexusmodloader.api.permission.PermissionNode node) {
            if (player.level().isClientSide()) {
                throw new IllegalStateException("Permissions are checked on the server");
            }
            return PermissionChecks.check(player, node);
        }

        @Override
        public boolean hasAdvancement(ResourceId advancement) {
            Advancement holder = advancement(advancement);
            return serverPlayer().getAdvancements().getOrStartProgress(holder).isDone();
        }

        @Override
        public boolean grantAdvancement(ResourceId advancement) {
            Advancement holder = advancement(advancement);
            PlayerAdvancements progress = serverPlayer().getAdvancements();
            boolean changed = false;
            for (String criterion : progress.getOrStartProgress(holder).getRemainingCriteria()) {
                changed |= progress.award(holder, criterion);
            }
            return changed;
        }

        @Override
        public boolean revokeAdvancement(ResourceId advancement) {
            Advancement holder = advancement(advancement);
            PlayerAdvancements progress = serverPlayer().getAdvancements();
            boolean changed = false;
            for (String criterion : progress.getOrStartProgress(holder).getCompletedCriteria()) {
                changed |= progress.revoke(holder, criterion);
            }
            return changed;
        }
    }
}
