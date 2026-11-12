package dev.chicken.mixin;

import dev.chicken.events.PacketEvent;
import dev.chicken.systems.Modules;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Feeds every packet that reaches the client into the client's event bus. */
@Mixin(Connection.class)
public class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void chicken$onPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo info) {
        Modules.get().post(new PacketEvent.Receive(packet));
    }
}
