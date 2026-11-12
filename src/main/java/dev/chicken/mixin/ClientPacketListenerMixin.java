package dev.chicken.mixin;

import dev.chicken.events.BlockUpdateEvent;
import dev.chicken.events.ChunkDataEvent;
import dev.chicken.systems.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Turns the vanilla chunk and block packets into client events. */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
    private void chicken$onChunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo info) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        Modules.get().post(new ChunkDataEvent(level.getChunk(packet.getX(), packet.getZ())));
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void chicken$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo info) {
        Modules.get().post(new BlockUpdateEvent(packet.getPos(), packet.getBlockState()));
    }
}
