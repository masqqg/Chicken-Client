package dev.chicken.util;

import net.minecraft.client.Minecraft;

/** The player's configured render distance in chunks. */
public final class RenderDistance {
    private RenderDistance() {
    }

    public static int get() {
        return Minecraft.getInstance().options.renderDistance().get();
    }
}
