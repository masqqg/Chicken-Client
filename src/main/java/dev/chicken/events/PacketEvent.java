package dev.chicken.events;

import net.minecraft.network.protocol.Packet;

/** Posted for every packet that leaves or reaches the client. */
public abstract class PacketEvent {
    public final Packet<?> packet;

    protected PacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public static final class Receive extends PacketEvent {
        public Receive(Packet<?> packet) {
            super(packet);
        }
    }

    public static final class Send extends PacketEvent {
        public Send(Packet<?> packet) {
            super(packet);
        }
    }
}
