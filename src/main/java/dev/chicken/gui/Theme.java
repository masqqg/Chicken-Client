package dev.chicken.gui;

import dev.chicken.render.Color;

/** Dark orange theme of the client. */
public final class Theme {
    private Theme() {
    }

    // Accent ramp
    public static final Color ACCENT = new Color(255, 138, 61, 255);
    public static final Color ACCENT_LIGHT = new Color(255, 176, 103, 255);
    public static final Color ACCENT_DEEP = new Color(224, 90, 22, 255);
    public static final Color ACCENT_SOFT = new Color(255, 138, 61, 40);
    public static final Color ACCENT_FAINT = new Color(255, 138, 61, 16);

    // Surfaces
    public static final Color BACKDROP = new Color(8, 8, 11, 165);
    public static final Color WINDOW = new Color(15, 15, 19, 242);
    public static final Color WINDOW_TOP = new Color(22, 22, 28, 246);
    public static final Color PANEL = new Color(24, 24, 30, 255);
    public static final Color PANEL_HOVER = new Color(33, 33, 41, 255);
    public static final Color PANEL_ACTIVE = new Color(42, 32, 26, 255);
    public static final Color SIDEBAR = new Color(12, 12, 16, 210);
    public static final Color STROKE = new Color(255, 255, 255, 20);
    public static final Color STROKE_STRONG = new Color(255, 255, 255, 38);
    public static final Color TRACK = new Color(255, 255, 255, 22);

    // Text
    public static final Color TEXT = new Color(244, 244, 247, 255);
    public static final Color TEXT_DIM = new Color(158, 158, 170, 255);
    public static final Color TEXT_FAINT = new Color(112, 112, 124, 255);

    // Misc
    public static final Color RED = new Color(255, 86, 86, 255);
    public static final Color GREEN = new Color(96, 220, 150, 255);
    public static final Color SHADOW = new Color(0, 0, 0, 170);

    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.r, color.g, color.b, alpha);
    }
}
