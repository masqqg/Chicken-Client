package dev.chicken.events;

import net.minecraft.world.level.chunk.LevelChunk;

/** Posted when a full chunk arrives from the server. */
public final class ChunkDataEvent {
    private final LevelChunk chunk;

    public ChunkDataEvent(LevelChunk chunk) {
        this.chunk = chunk;
    }

    public LevelChunk chunk() {
        return chunk;
    }
}
