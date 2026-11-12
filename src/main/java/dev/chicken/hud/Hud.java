package dev.chicken.hud;

import dev.chicken.ChickenClient;
import dev.chicken.events.Render2DEvent;
import dev.chicken.gui.Anim;
import dev.chicken.gui.Logo;
import dev.chicken.gui.Theme;
import dev.chicken.render.Color;
import dev.chicken.render.Renderer2D;
import dev.chicken.systems.Module;
import dev.chicken.systems.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.lwjgl.nanovg.NanoVG;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client HUD: a glassy watermark with the cloud mark, live FPS and latency, plus an
 * animated module list. Everything is drawn with NanoVG, so it is smooth at every GUI scale.
 */
public final class Hud {
    private static final Anim watermarkSlide = new Anim(-26.0);
    private static final Anim watermarkFade = new Anim(0.0);
    private static final Map<String, Anim> listAnimation = new HashMap<>();
    private static long lastFrameNanos;
    private static float glowPhase;

    private Hud() {
    }

    /** Called inside an open NanoVG frame. */
    public static void render(Render2DEvent event) {
        Renderer2D r = event.renderer;
        if (r == null || !r.isReady()) return;

        float delta = 0.0f;
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) delta = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;

        glowPhase += delta;

        watermarkSlide.set(0.0);
        watermarkFade.set(1.0);
        watermarkSlide.update(delta);
        watermarkFade.update(delta);

        drawWatermark(r, event.screenWidth, event.screenHeight, delta);
        drawModuleList(r, event.screenWidth, delta);
    }

    // ------------------------------------------------------------------ watermark

    private static void drawWatermark(Renderer2D r, int screenWidth, int screenHeight, float delta) {
        float alpha = Anim.clamp01(watermarkFade.f());
        if (alpha <= 0.001f) return;

        Minecraft mc = Minecraft.getInstance();

        String brand = "CHICKEN";
        String brandAccent = "CLIENT";
        String version = "v" + ChickenClient.VERSION;
        String fps = mc.getFps() + " FPS";
        String ping = latencyText(mc);

        float brandSize = 12.5f;
        float smallSize = 9.0f;

        float brandWidth = r.textWidth(brand, brandSize, Renderer2D.Font.Display);
        float accentWidth = r.textWidth(brandAccent, brandSize, Renderer2D.Font.Display);
        float versionWidth = r.textWidth(version, smallSize - 0.5f, Renderer2D.Font.Medium);
        float fpsWidth = r.textWidth(fps, smallSize, Renderer2D.Font.Medium);
        float pingWidth = r.textWidth(ping, smallSize, Renderer2D.Font.Medium);

        float logo = 18.0f;
        float padding = 11.0f;
        float gap = 9.0f;
        float divider = 11.0f;

        float width = padding + logo + gap + brandWidth + 4.5f + accentWidth + gap + versionWidth
            + divider + fpsWidth + 8.0f + pingWidth + padding;
        float height = 34.0f;

        float x = 10.0f + watermarkSlide.f();
        float y = 10.0f;

        r.push();
        r.alpha(alpha);

        // Soft accent bloom behind the card, slowly breathing.
        float pulse = 0.55f + 0.45f * (float) Math.sin(glowPhase * 1.4f);
        r.glow(x, y, width, height, 12.0f, 16.0f, Theme.withAlpha(Theme.ACCENT, (int) (16 + 14 * pulse)));

        // Glass body
        r.roundRectGradient(x, y, width, height, 12.0f, Theme.WINDOW_TOP, Theme.WINDOW);
        r.roundRectStroke(x, y, width, height, 12.0f, 1.0f, Theme.STROKE);
        r.roundRectGradientH(x + 0.5f, y + height - 2.0f, width - 1.0f, 1.5f, 0.75f,
            Theme.withAlpha(Theme.ACCENT, 0), Theme.withAlpha(Theme.ACCENT, 150));

        // Logo
        Logo.glowCloud(r, x + padding, y + (height - logo) * 0.5f, logo);

        // Brand
        float textX = x + padding + logo + gap;
        float midY = y + height * 0.5f + 0.5f;
        r.text(brand, textX, midY, brandSize, Renderer2D.Font.Display, Theme.TEXT);
        r.text(brandAccent, textX + brandWidth + 4.5f, midY, brandSize, Renderer2D.Font.Display,
            Theme.ACCENT_LIGHT);

        // Version chip
        float chipX = textX + brandWidth + 4.5f + accentWidth + gap;
        r.roundRect(chipX, midY - 6.5f, versionWidth + 7.0f, 13.0f, 6.5f, Theme.ACCENT_FAINT);
        r.text(version, chipX + 3.5f, midY, smallSize - 0.5f, Renderer2D.Font.Medium,
            Theme.withAlpha(Theme.ACCENT_LIGHT, 210));

        // FPS + ping
        float infoX = chipX + versionWidth + 7.0f + divider;
        r.line(infoX - 6.0f, midY - 7.0f, infoX - 6.0f, midY + 7.0f, 1.0f, Theme.STROKE);
        r.text(fps, infoX, midY, smallSize, Renderer2D.Font.Medium, Theme.TEXT_DIM);
        r.text(ping, infoX + fpsWidth + 8.0f, midY, smallSize, Renderer2D.Font.Medium,
            Theme.withAlpha(Theme.GREEN, 220));

        r.pop();
    }

    private static String latencyText(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null) return "-- MS";

        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        if (info == null) return "-- MS";

        return info.getLatency() + " MS";
    }

    // ------------------------------------------------------------------ module list

    private static void drawModuleList(Renderer2D r, int screenWidth, float delta) {
        List<Module> active = new ArrayList<>();
        for (Module module : Modules.get().getAll()) {
            if (module.isActive()) active.add(module);
        }

        active.sort((a, b) -> Float.compare(
            r.textWidth(b.title(), 9.5f, Renderer2D.Font.SemiBold),
            r.textWidth(a.title(), 9.5f, Renderer2D.Font.SemiBold)));

        float right = screenWidth - 10.0f;
        float y = 10.0f;
        float rowHeight = 19.0f;
        float gap = 4.0f;

        listAnimation.keySet().removeIf(name -> Modules.get().get(name) == null
            || !Modules.get().get(name).isActive());

        int index = 0;
        for (Module module : active) {
            Anim anim = listAnimation.computeIfAbsent(module.name, key -> new Anim(0.0));
            anim.speed = 16.0 + index * 1.5;
            anim.set(1.0);
            float appear = Anim.clamp01(anim.update(delta));

            float textWidth = r.textWidth(module.title(), 9.5f, Renderer2D.Font.SemiBold);
            float width = textWidth + 20.0f;
            float slide = (1.0f - Anim.easeOutCubic(appear)) * 24.0f;

            float x = right - width + slide;
            if (appear <= 0.002f) {
                index++;
                y += (rowHeight + gap) * 0.0f;
                continue;
            }

            r.push();
            r.alpha(appear);

            r.roundRect(x, y, width, rowHeight, 6.0f, Theme.withAlpha(Theme.WINDOW, 225));
            r.roundRectStroke(x, y, width, rowHeight, 6.0f, 1.0f, Theme.withAlpha(Theme.STROKE, 26));

            // Accent edge on the right
            r.roundRectGradient(x + width - 2.5f, y + 4.0f, 2.5f, rowHeight - 8.0f, 1.25f,
                Theme.ACCENT_LIGHT, Theme.ACCENT_DEEP);

            r.textRight(module.title(), x + width - 9.0f, y + rowHeight * 0.5f + 0.5f, 9.5f,
                Renderer2D.Font.SemiBold, Theme.TEXT);

            r.circle(x + 9.0f, y + rowHeight * 0.5f, 2.2f, Theme.ACCENT);
            r.pop();

            y += rowHeight + gap;
            index++;
        }
    }

    /** Small utility used by the GUI to render a "HUD" preview row. */
    public static void drawPill(Renderer2D r, float x, float y, String text, Color color) {
        float width = r.textWidth(text, 9.0f, Renderer2D.Font.Medium) + 14.0f;
        r.roundRect(x, y, width, 16.0f, 8.0f, Theme.ACCENT_FAINT);
        r.text(text, x + 7.0f, y + 8.5f, 9.0f, Renderer2D.Font.Medium, color);
    }
}
