package dev.chicken.karambit;

/**
 * Blockbench easing names, with a self-contained fallback implementation.
 *
 * <p>When GeckoLib is installed the curve is evaluated through GeckoLib's own {@code EasingType}
 * so the karambit animates exactly like it does in Blockbench; otherwise the formulas below are
 * used, which keeps the cosmetic working on setups without GeckoLib.</p>
 */
public enum Easing {
    LINEAR("linear"),
    STEP("step"),
    EASE_IN_SINE("easeInSine"),
    EASE_OUT_SINE("easeOutSine"),
    EASE_IN_OUT_SINE("easeInOutSine"),
    EASE_IN_QUAD("easeInQuad"),
    EASE_OUT_QUAD("easeOutQuad"),
    EASE_IN_OUT_QUAD("easeInOutQuad"),
    EASE_IN_CUBIC("easeInCubic"),
    EASE_OUT_CUBIC("easeOutCubic"),
    EASE_IN_OUT_CUBIC("easeInOutCubic"),
    EASE_IN_QUART("easeInQuart"),
    EASE_OUT_QUART("easeOutQuart"),
    EASE_IN_OUT_QUART("easeInOutQuart"),
    EASE_IN_QUINT("easeInQuint"),
    EASE_OUT_QUINT("easeOutQuint"),
    EASE_IN_OUT_QUINT("easeInOutQuint"),
    EASE_IN_EXPO("easeInExpo"),
    EASE_OUT_EXPO("easeOutExpo"),
    EASE_IN_OUT_EXPO("easeInOutExpo"),
    EASE_IN_CIRC("easeInCirc"),
    EASE_OUT_CIRC("easeOutCirc"),
    EASE_IN_OUT_CIRC("easeInOutCirc"),
    EASE_IN_BACK("easeInBack"),
    EASE_OUT_BACK("easeOutBack"),
    EASE_IN_OUT_BACK("easeInOutBack"),
    EASE_IN_ELASTIC("easeInElastic"),
    EASE_OUT_ELASTIC("easeOutElastic"),
    EASE_IN_OUT_ELASTIC("easeInOutElastic"),
    EASE_IN_BOUNCE("easeInBounce"),
    EASE_OUT_BOUNCE("easeOutBounce"),
    EASE_IN_OUT_BOUNCE("easeInOutBounce");

    private static final double BACK_C1 = 1.70158;
    private static final double BACK_C3 = BACK_C1 + 1.0;
    private static final double ELASTIC_C4 = (2.0 * Math.PI) / 3.0;
    private static final double ELASTIC_C5 = (2.0 * Math.PI) / 4.5;

    private final String geckoName;

    Easing(String geckoName) {
        this.geckoName = geckoName;
    }

    /** The name GeckoLib uses for this curve. */
    public String geckoName() {
        return geckoName;
    }

    /** Eases {@code t} (0..1), preferring GeckoLib when it is installed. */
    public float ease(float t) {
        return GeckoCurves.ease(geckoName, t, fallback(t));
    }

    public static Easing fromString(String name) {
        if (name != null) {
            for (Easing easing : values()) {
                if (easing.geckoName.equalsIgnoreCase(name)) return easing;
            }
        }

        return EASE_IN_OUT_SINE;
    }

    // ------------------------------------------------------------------ fallback maths

    private float fallback(float t) {
        double x = Math.max(0.0, Math.min(1.0, t));

        return (float) switch (this) {
            case LINEAR -> x;
            case STEP -> x >= 1.0 ? 1.0 : 0.0;
            case EASE_IN_SINE -> 1.0 - Math.cos((x * Math.PI) / 2.0);
            case EASE_OUT_SINE -> Math.sin((x * Math.PI) / 2.0);
            case EASE_IN_OUT_SINE -> -(Math.cos(Math.PI * x) - 1.0) / 2.0;
            case EASE_IN_QUAD -> x * x;
            case EASE_OUT_QUAD -> 1.0 - (1.0 - x) * (1.0 - x);
            case EASE_IN_OUT_QUAD -> x < 0.5 ? 2.0 * x * x : 1.0 - Math.pow(-2.0 * x + 2.0, 2.0) / 2.0;
            case EASE_IN_CUBIC -> x * x * x;
            case EASE_OUT_CUBIC -> 1.0 - Math.pow(1.0 - x, 3.0);
            case EASE_IN_OUT_CUBIC -> x < 0.5 ? 4.0 * x * x * x : 1.0 - Math.pow(-2.0 * x + 2.0, 3.0) / 2.0;
            case EASE_IN_QUART -> Math.pow(x, 4.0);
            case EASE_OUT_QUART -> 1.0 - Math.pow(1.0 - x, 4.0);
            case EASE_IN_OUT_QUART -> x < 0.5 ? 8.0 * Math.pow(x, 4.0) : 1.0 - Math.pow(-2.0 * x + 2.0, 4.0) / 2.0;
            case EASE_IN_QUINT -> Math.pow(x, 5.0);
            case EASE_OUT_QUINT -> 1.0 - Math.pow(1.0 - x, 5.0);
            case EASE_IN_OUT_QUINT -> x < 0.5 ? 16.0 * Math.pow(x, 5.0) : 1.0 - Math.pow(-2.0 * x + 2.0, 5.0) / 2.0;
            case EASE_IN_EXPO -> x <= 0.0 ? 0.0 : Math.pow(2.0, 10.0 * x - 10.0);
            case EASE_OUT_EXPO -> x >= 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * x);
            case EASE_IN_OUT_EXPO -> {
                if (x <= 0.0) yield 0.0;
                if (x >= 1.0) yield 1.0;
                yield x < 0.5
                    ? Math.pow(2.0, 20.0 * x - 10.0) / 2.0
                    : (2.0 - Math.pow(2.0, -20.0 * x + 10.0)) / 2.0;
            }
            case EASE_IN_CIRC -> 1.0 - Math.sqrt(1.0 - Math.min(1.0, x * x));
            case EASE_OUT_CIRC -> Math.sqrt(1.0 - Math.pow(x - 1.0, 2.0));
            case EASE_IN_OUT_CIRC -> x < 0.5
                ? (1.0 - Math.sqrt(1.0 - Math.pow(2.0 * x, 2.0))) / 2.0
                : (Math.sqrt(1.0 - Math.pow(-2.0 * x + 2.0, 2.0)) + 1.0) / 2.0;
            case EASE_IN_BACK -> BACK_C3 * x * x * x - BACK_C1 * x * x;
            case EASE_OUT_BACK -> 1.0 + BACK_C3 * Math.pow(x - 1.0, 3.0) + BACK_C1 * Math.pow(x - 1.0, 2.0);
            case EASE_IN_OUT_BACK -> {
                double c2 = BACK_C1 * 1.525;
                yield x < 0.5
                    ? (Math.pow(2.0 * x, 2.0) * ((c2 + 1.0) * 2.0 * x - c2)) / 2.0
                    : (Math.pow(2.0 * x - 2.0, 2.0) * ((c2 + 1.0) * (x * 2.0 - 2.0) + c2) + 2.0) / 2.0;
            }
            case EASE_IN_ELASTIC -> {
                if (x <= 0.0) yield 0.0;
                if (x >= 1.0) yield 1.0;
                yield -Math.pow(2.0, 10.0 * x - 10.0) * Math.sin((x * 10.0 - 10.75) * ELASTIC_C4);
            }
            case EASE_OUT_ELASTIC -> {
                if (x <= 0.0) yield 0.0;
                if (x >= 1.0) yield 1.0;
                yield Math.pow(2.0, -10.0 * x) * Math.sin((x * 10.0 - 0.75) * ELASTIC_C4) + 1.0;
            }
            case EASE_IN_OUT_ELASTIC -> {
                if (x <= 0.0) yield 0.0;
                if (x >= 1.0) yield 1.0;
                yield x < 0.5
                    ? -(Math.pow(2.0, 20.0 * x - 10.0) * Math.sin((20.0 * x - 11.125) * ELASTIC_C5)) / 2.0
                    : (Math.pow(2.0, -20.0 * x + 10.0) * Math.sin((20.0 * x - 11.125) * ELASTIC_C5)) / 2.0 + 1.0;
            }
            case EASE_IN_BOUNCE -> 1.0 - bounceOut(1.0 - x);
            case EASE_OUT_BOUNCE -> bounceOut(x);
            case EASE_IN_OUT_BOUNCE -> x < 0.5
                ? (1.0 - bounceOut(1.0 - 2.0 * x)) / 2.0
                : (1.0 + bounceOut(2.0 * x - 1.0)) / 2.0;
        };
    }

    private static double bounceOut(double x) {
        double n1 = 7.5625;
        double d1 = 2.75;

        if (x < 1.0 / d1) return n1 * x * x;
        if (x < 2.0 / d1) {
            double y = x - 1.5 / d1;
            return n1 * y * y + 0.75;
        }
        if (x < 2.5 / d1) {
            double y = x - 2.25 / d1;
            return n1 * y * y + 0.9375;
        }

        double y = x - 2.625 / d1;
        return n1 * y * y + 0.984375;
    }
}
