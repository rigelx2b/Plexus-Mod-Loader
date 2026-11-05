package dev.plexusmodloader.game.layer.v1_20_4.content;

import dev.plexusmodloader.api.content.block.entity.BlockEntityDefinition;
import dev.plexusmodloader.api.content.block.entity.BlockEntityView;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** A mod block entity without an inventory. */
final class PlexusBlockEntity extends BlockEntity implements ModBlockEntity {

    private final BlockEntityData data;

    PlexusBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, BlockEntityDefinition definition) {
        super(type, pos, state);
        this.data = new BlockEntityData(this, definition);
    }

    @Override
    public BlockEntityView view() {
        return data.view();
    }

    @Override
    protected void saveAdditional(CompoundTag output) {
        super.saveAdditional(output);
        data.save(output);
    }

    @Override
    public void load(CompoundTag input) {
        super.load(input);
        data.load(input);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return data.syncToClients() ? ClientboundBlockEntityDataPacket.create(this) : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return data.syncToClients() ? saveWithoutMetadata() : super.getUpdateTag();
    }
}
