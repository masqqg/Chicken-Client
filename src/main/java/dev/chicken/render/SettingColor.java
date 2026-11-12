package dev.chicken.render;

import dev.chicken.settings.Setting;

/** A colour that lives in a {@link Setting}. */
public class SettingColor extends Color {
    public SettingColor() {
        super(255, 138, 61, 255);
    }

    public SettingColor(int r, int g, int b, int a) {
        super(r, g, b, a);
    }

    public SettingColor(int r, int g, int b) {
        super(r, g, b);
    }

    public SettingColor copy() {
        return new SettingColor(r, g, b, a);
    }
}
