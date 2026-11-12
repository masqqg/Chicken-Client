package dev.chicken.settings;

import com.google.gson.JsonPrimitive;
import dev.chicken.render.SettingColor;

public class ColorSetting extends Setting<SettingColor> {
    private ColorSetting(String name, String description, SettingColor defaultValue) {
        super(name, description, defaultValue);
    }

    @Override
    public void set(SettingColor value) {
        super.set(value == null ? defaultValue : value);
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value.toArgb());
    }

    @Override
    public void fromJson(com.google.gson.JsonElement json) {
        if (json.isJsonPrimitive()) {
            int argb = json.getAsInt();
            int a = argb >>> 24;
            int r = argb >> 16 & 0xFF;
            int g = argb >> 8 & 0xFF;
            int b = argb & 0xFF;
            value.set(r, g, b, a);
        }
    }

    public static class Builder extends Setting.Builder<Builder, SettingColor> {
        @Override
        public ColorSetting build() {
            ColorSetting setting = new ColorSetting(name, description,
                defaultValue == null ? new SettingColor(255, 138, 61, 255) : defaultValue);
            apply(setting);
            return setting;
        }
    }
}
