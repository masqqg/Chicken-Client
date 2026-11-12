package dev.chicken.settings;

import com.google.gson.JsonPrimitive;

public class StringSetting extends Setting<String> {
    public final int maxLength;

    private StringSetting(String name, String description, String defaultValue, int maxLength) {
        super(name, description, defaultValue);
        this.maxLength = maxLength;
    }

    @Override
    public void set(String value) {
        if (value != null && value.length() > maxLength) value = value.substring(0, maxLength);
        super.set(value == null ? "" : value);
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(com.google.gson.JsonElement json) {
        if (json.isJsonPrimitive()) set(json.getAsString());
    }

    public static class Builder extends Setting.Builder<Builder, String> {
        private int maxLength = 64;

        public Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }

        @Override
        public StringSetting build() {
            StringSetting setting = new StringSetting(name, description,
                defaultValue == null ? "" : defaultValue, maxLength);
            apply(setting);
            return setting;
        }
    }
}
