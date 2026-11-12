package dev.chicken.gui;

import dev.chicken.render.Color;
import dev.chicken.render.Renderer2D;

/** Small vector glyphs. No textures, no pixel art. */
public final class Icons {
    private Icons() {
    }

    public static void draw(Renderer2D r, String name, float x, float y, float size, Color color) {
        switch (name) {
            case "globe" -> globe(r, x, y, size, color);
            case "layers" -> layers(r, x, y, size, color);
            case "knife" -> knife(r, x, y, size, color);
            case "chevron" -> chevron(r, x, y, size, color);
            default -> dot(r, x, y, size, color);
        }
    }

    public static void dot(Renderer2D r, float x, float y, float size, Color color) {
        r.circle(x + size * 0.5f, y + size * 0.5f, size * 0.22f, color);
    }

    public static void globe(Renderer2D r, float x, float y, float size, Color color) {
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;
        float radius = size * 0.34f;

        r.roundRectStroke(cx - radius, cy - radius, radius * 2, radius * 2, radius, size * 0.09f, color);
        r.line(cx, cy - radius, cx, cy + radius, size * 0.07f, color);
        r.line(cx - radius + size * 0.06f, cy, cx + radius - size * 0.06f, cy, size * 0.07f, color);
    }

    public static void layers(Renderer2D r, float x, float y, float size, Color color) {
        float w = size * 0.62f;
        float x0 = x + (size - w) * 0.5f;
        float h = size * 0.12f;

        for (int i = 0; i < 3; i++) {
            float y0 = y + size * 0.24f + i * size * 0.2f;
            r.roundRectStroke(x0, y0, w, h, h * 0.5f, size * 0.07f, color);
        }
    }

    public static void knife(Renderer2D r, float x, float y, float size, Color color) {
        float x0 = x + size * 0.5f;
        float y0 = y + size * 0.16f;

        // Blade: a long curved sliver, then the handle.
        r.line(x0, y0, x0 - size * 0.16f, y0 + size * 0.34f, size * 0.13f, color);
        r.line(x0 - size * 0.16f, y0 + size * 0.34f, x0 - size * 0.02f, y0 + size * 0.62f, size * 0.13f, color);
    }

    public static void chevron(Renderer2D r, float x, float y, float size, Color color) {
        float cx = x + size * 0.5f;
        float cy = y + size * 0.5f;

        r.line(cx - size * 0.14f, cy - size * 0.2f, cx + size * 0.12f, cy, size * 0.1f, color);
        r.line(cx + size * 0.12f, cy, cx - size * 0.14f, cy + size * 0.2f, size * 0.1f, color);
    }

    public static void check(Renderer2D r, float x, float y, float size, Color color) {
        r.line(x + size * 0.24f, y + size * 0.52f, x + size * 0.44f, y + size * 0.72f, size * 0.12f, color);
        r.line(x + size * 0.44f, y + size * 0.72f, x + size * 0.78f, y + size * 0.3f, size * 0.12f, color);
    }

    public static void search(Renderer2D r, float x, float y, float size, Color color) {
        float cx = x + size * 0.44f;
        float cy = y + size * 0.44f;
        float radius = size * 0.24f;

        r.roundRectStroke(cx - radius, cy - radius, radius * 2, radius * 2, radius, size * 0.09f, color);
        r.line(cx + radius * 0.7f, cy + radius * 0.7f, x + size * 0.8f, y + size * 0.8f, size * 0.09f, color);
    }
}
