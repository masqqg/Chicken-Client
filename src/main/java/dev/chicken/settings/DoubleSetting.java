package dev.chicken.settings;

import com.google.gson.JsonPrimitive;

public class DoubleSetting extends Setting<Double> {
    public final double min;
    public final double max;
    public final double sliderMin;
    public final double sliderMax;

    private DoubleSetting(String name, String description, double defaultValue, double min, double max, double sliderMin, double sliderMax) {
        super(name, description, defaultValue);
        this.min = min;
        this.max = max;
        this.sliderMin = Math.max(min, sliderMin);
        this.sliderMax = Math.min(max, sliderMax);
    }

    @Override
    public void set(Double value) {
        super.set(value == null ? defaultValue : Math.max(min, Math.min(max, value)));
    }

    @Override
    public com.google.gson.JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(com.google.gson.JsonElement json) {
        if (json.isJsonPrimitive()) set(json.getAsDouble());
    }

    public static class Builder extends Setting.Builder<Builder, Double> {
        private double min = -Double.MAX_VALUE;
        private double max = Double.MAX_VALUE;
        private double sliderMin = -Double.MAX_VALUE;
        private double sliderMax = Double.MAX_VALUE;

        public Builder range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Builder sliderRange(double min, double max) {
            this.sliderMin = min;
            this.sliderMax = max;
            return this;
        }

        public Builder min(double min) {
            this.min = min;
            return this;
        }

        public Builder max(double max) {
            this.max = max;
            return this;
        }

        public Builder sliderMin(double min) {
            this.sliderMin = min;
            return this;
        }

        public Builder sliderMax(double max) {
            this.sliderMax = max;
            return this;
        }

        @Override
        public DoubleSetting build() {
            DoubleSetting setting = new DoubleSetting(name, description,
                defaultValue == null ? 0.0 : defaultValue, min, max, sliderMin, sliderMax);
            apply(setting);
            return setting;
        }
    }
}
