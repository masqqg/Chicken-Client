package dev.chicken.util;

import dev.chicken.ChickenClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Chat feedback with the client prefix. */
public final class Chat {
    private Chat() {
    }

    public static void info(String format, Object... args) {
        send(ChatFormatting.GOLD, format, args);
    }

    public static void warning(String format, Object... args) {
        send(ChatFormatting.RED, format, args);
    }

    public static void error(String format, Object... args) {
        send(ChatFormatting.DARK_RED, format, args);
    }

    private static void send(ChatFormatting color, String format, Object... args) {
        String message = args.length == 0 ? format : String.format(format, args);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            ChickenClient.LOG.info(message);
            return;
        }

        mc.player.displayClientMessage(
            Component.literal("[Chicken] ").withStyle(color).append(Component.literal(message).withStyle(ChatFormatting.WHITE)),
            false
        );
    }
}
