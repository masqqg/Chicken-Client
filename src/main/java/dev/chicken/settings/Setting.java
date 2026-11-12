package dev.chicken.settings;

import com.google.gson.JsonElement;

/**
 * A single, serialisable value owned by a {@link SettingGroup}.
 */
public abstract class Setting<T> {
    public final String name;
    public final String description;
    public final T defaultValue;

    protected T value;

    protected Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
    }

    protected java.util.function.BooleanSupplier visible = () -> true;

    public boolean isVisible() {
        return visible.getAsBoolean();
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }

    public void reset() {
        value = defaultValue;
    }

    /** The name shown in the GUI. */
    public String title() {
        StringBuilder sb = new StringBuilder();

        for (String part : name.split("-")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return sb.toString();
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement json);

    /** Fluent builder shared by every setting type. */
    public abstract static class Builder<B extends Builder<B, T>, T> {
        protected String name = "setting";
        protected String description = "";
        protected T defaultValue;

        @SuppressWarnings("unchecked")
        public B name(String name) {
            this.name = name;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B description(String description) {
            this.description = description;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B defaultValue(T defaultValue) {
            this.defaultValue = defaultValue;
            return (B) this;
        }

        @SuppressWarnings("unchecked")
        public B visible(java.util.function.BooleanSupplier visible) {
            this.visible = visible;
            return (B) this;
        }

        protected java.util.function.BooleanSupplier visible = () -> true;

        protected void apply(Setting<T> setting) {
            setting.visible = visible;
        }

        public abstract Setting<T> build();
    }
}
