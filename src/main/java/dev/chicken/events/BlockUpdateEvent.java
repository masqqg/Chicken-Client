package dev.chicken.events;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Posted when a single block in the world changes. */
public final class BlockUpdateEvent {
    public final BlockPos pos;
    public final BlockState newState;

    public BlockUpdateEvent(BlockPos pos, BlockState newState) {
        this.pos = pos;
        this.newState = newState;
    }
}
