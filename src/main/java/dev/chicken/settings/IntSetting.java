package dev.chicken.settings;

import com.google.gson.JsonPrimitive;

public class IntSetting extends Setting<Integer> {
    public final int min;
    public final int max;
    public final int sliderMin;
    public final int sliderMax;

    private IntSetting(String name, String description, int defaultValue, int min, int max, int sliderMin, int sliderMax) {
        super(name, description, defaultValue);
        this.min = min;
        this.max = max;
        this.sliderMin = Math.max(min, sliderMin);
        this.sliderMax = Math.min(max, sliderMax);
    }

    @Override
    public void set(Integer value) {
        super.set(value == null ? defaultValue : Math.max(min, Math.min(max, value)));
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(com.google.gson.JsonElement json) {
        if (json.isJsonPrimitive()) set(json.getAsInt());
    }

    public static class Builder extends Setting.Builder<Builder, Integer> {
        private int min = Integer.MIN_VALUE;
        private int max = Integer.MAX_VALUE;
        private int sliderMin = Integer.MIN_VALUE;
        private int sliderMax = Integer.MAX_VALUE;

        public Builder range(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Builder sliderRange(int min, int max) {
            this.sliderMin = min;
            this.sliderMax = max;
            return this;
        }

        public Builder min(int min) {
            this.min = min;
            return this;
        }

        public Builder max(int max) {
            this.max = max;
            return this;
        }

        public Builder sliderMin(int min) {
            this.sliderMin = min;
            return this;
        }

        public Builder sliderMax(int max) {
            this.sliderMax = max;
            return this;
        }

        @Override
        public IntSetting build() {
            IntSetting setting = new IntSetting(name, description, defaultValue == null ? 0 : defaultValue,
                min, max, sliderMin, sliderMax);
            apply(setting);
            return setting;
        }
    }
}
