package dev.chicken.events;

import dev.chicken.render.Renderer2D;
import net.minecraft.client.gui.GuiGraphics;

/** Posted once per frame while the in-game HUD is drawn. */
public final class Render2DEvent {
    public final GuiGraphics drawContext;
    public final Renderer2D renderer;
    public final int screenWidth;
    public final int screenHeight;
    public final float tickDelta;

    public Render2DEvent(GuiGraphics drawContext, Renderer2D renderer, int screenWidth, int screenHeight, float tickDelta) {
        this.drawContext = drawContext;
        this.renderer = renderer;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.tickDelta = tickDelta;
    }
}
