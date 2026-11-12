package dev.chicken.settings;

import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.List;

public class EnumSetting<T extends Enum<T>> extends Setting<T> {
    private final Class<T> enumClass;

    private EnumSetting(String name, String description, T defaultValue, Class<T> enumClass) {
        super(name, description, defaultValue);
        this.enumClass = enumClass;
    }

    public List<T> values() {
        return Arrays.asList(enumClass.getEnumConstants());
    }

    /** Cycles to the next enum value (right click in the GUI). */
    public void cycle(boolean backwards) {
        List<T> all = values();
        int i = all.indexOf(value);
        int next = Math.floorMod(i + (backwards ? -1 : 1), all.size());
        value = all.get(next);
    }

    public String title(T v) {
        String raw = v.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder();

        for (String part : raw.split(" ")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return sb.toString();
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value.name());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void fromJson(com.google.gson.JsonElement json) {
        if (!json.isJsonPrimitive()) return;

        for (T v : enumClass.getEnumConstants()) {
            if (v.name().equalsIgnoreCase(json.getAsString())) {
                value = v;
                return;
            }
        }
    }

    public static class Builder<T extends Enum<T>> extends Setting.Builder<Builder<T>, T> {
        private Class<T> enumClass;

        public Builder<T> enumClass(Class<T> enumClass) {
            this.enumClass = enumClass;
            return this;
        }

        @Override
        public EnumSetting<T> build() {
            T def = defaultValue;
            if (def == null && enumClass != null) def = enumClass.getEnumConstants()[0];
            EnumSetting<T> setting = new EnumSetting<>(name, description, def, enumClass);
            apply(setting);
            return setting;
        }
    }
}
