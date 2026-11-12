package dev.chicken.settings;

import com.google.gson.JsonPrimitive;

public class BoolSetting extends Setting<Boolean> {
    private BoolSetting(String name, String description, boolean defaultValue) {
        super(name, description, defaultValue);
    }

    public void toggle() {
        value = !value;
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(com.google.gson.JsonElement json) {
        if (json.isJsonPrimitive()) value = json.getAsBoolean();
    }

    public static class Builder extends Setting.Builder<Builder, Boolean> {
        @Override
        public BoolSetting build() {
            BoolSetting setting = new BoolSetting(name, description, defaultValue != null && defaultValue);
            apply(setting);
            return setting;
        }
    }
}
