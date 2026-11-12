package dev.chicken.render;

/** Simple mutable RGBA colour used by the renderers and the settings. */
public class Color {
    public int r, g, b, a;

    public Color(int r, int g, int b, int a) {
        set(r, g, b, a);
    }

    public Color(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public void set(int r, int g, int b, int a) {
        this.r = clamp(r);
        this.g = clamp(g);
        this.b = clamp(b);
        this.a = clamp(a);
    }

    public void set(Color other) {
        set(other.r, other.g, other.b, other.a);
    }

    public Color copy() {
        return new Color(r, g, b, a);
    }

    public Color withAlpha(int alpha) {
        return new Color(r, g, b, alpha);
    }

    /** 0xAARRGGBB, the layout GuiGraphics expects. */
    public int toArgb() {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    public int toArgb(int alpha) {
        return (alpha & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    /** 0xRRGGBB -> Color. */
    public static Color ofRgb(int rgb) {
        return new Color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 255);
    }

    public static Color ofArgb(int argb) {
        return new Color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24);
    }

    public static Color lerp(Color from, Color to, float t) {
        return new Color(
            (int) (from.r + (to.r - from.r) * t),
            (int) (from.g + (to.g - from.g) * t),
            (int) (from.b + (to.b - from.b) * t),
            (int) (from.a + (to.a - from.a) * t)
        );
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
