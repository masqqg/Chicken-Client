package dev.chicken.gui;

/** Frame-rate independent smoothing used across the interface. */
public final class Anim {
    public double value;
    public double target;
    public double speed = 14.0;

    public Anim(double value) {
        this.value = value;
        this.target = value;
    }

    public Anim() {
        this(0.0);
    }

    public void set(double target) {
        this.target = target;
    }

    public void snap(double value) {
        this.value = value;
        this.target = value;
    }

    public float update(double deltaSeconds) {
        double factor = 1.0 - Math.exp(-speed * Math.max(0.0, Math.min(0.1, deltaSeconds)));
        value += (target - value) * factor;

        if (Math.abs(target - value) < 0.0005) value = target;
        return (float) value;
    }

    public float f() {
        return (float) value;
    }

    public boolean settled() {
        return Math.abs(target - value) < 0.001;
    }

    // ------------------------------------------------------------------ easing

    public static float easeOutCubic(float t) {
        float f = 1.0f - t;
        return 1.0f - f * f * f;
    }

    public static float easeInOutCubic(float t) {
        return t < 0.5f ? 4.0f * t * t * t : 1.0f - (float) Math.pow(-2.0 * t + 2.0, 3.0) / 2.0f;
    }

    public static float easeOutQuint(float t) {
        return 1.0f - (float) Math.pow(1.0 - t, 5.0);
    }

    public static float easeOutBack(float t) {
        float c = 1.70158f;
        float d = t - 1.0f;
        return 1.0f + (c + 1.0f) * d * d * d + c * d * d;
    }

    public static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }
}
