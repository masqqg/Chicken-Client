package dev.chicken.gui;

import dev.chicken.render.Color;
import dev.chicken.render.Renderer2D;

/** The Chicken cloud mark, drawn as vectors so it stays sharp at any size. */
public final class Logo {
    private Logo() {
    }

    /** Draws the cloud centred in a square of {@code size} at {@code x}/{@code y}. */
    public static void cloud(Renderer2D r, float x, float y, float size, Color color) {
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;
        float s = size / 24.0f;

        r.circle(cx - 5.2f * s, cy + 2.0f * s, 4.6f * s, color);
        r.circle(cx + 0.4f * s, cy - 2.2f * s, 6.2f * s, color);
        r.circle(cx + 6.0f * s, cy + 2.4f * s, 4.2f * s, color);
        r.roundRect(cx - 9.6f * s, cy + 1.4f * s, 19.4f * s, 6.0f * s, 3.0f * s, color);
    }

    /** Cloud with a soft orange glow, used by the watermark and the sidebar. */
    public static void glowCloud(Renderer2D r, float x, float y, float size) {
        r.push();
        r.glow(x - size * 0.22f, y - size * 0.22f, size * 1.44f, size * 1.44f, size * 0.4f, size * 0.5f,
            Theme.withAlpha(Theme.ACCENT, 34));
        r.pop();

        cloud(r, x + size * 0.06f, y + size * 0.1f, size * 0.88f, Theme.withAlpha(Theme.ACCENT_DEEP, 90));
        cloud(r, x, y, size, Theme.ACCENT);
        cloud(r, x + size * 0.03f, y + size * 0.03f, size * 0.82f, Theme.ACCENT_LIGHT);
    }
}
