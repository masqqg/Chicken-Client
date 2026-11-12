package dev.chicken.karambit;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Bridges animation curves to GeckoLib when the mod is installed.
 *
 * <p>GeckoLib is an optional companion, not a hard requirement: the karambit cosmetic must not
 * stop the client from starting on setups that do not ship GeckoLib. The GeckoLib classes are only
 * touched from inside the nested {@link GeckoBackend}, which is linked lazily on first use and only
 * when the mod is actually present.</p>
 */
final class GeckoCurves {
    private static Boolean available;

    private GeckoCurves() {
    }

    static float ease(String geckoName, float t, float fallback) {
        if (!isAvailable()) return fallback;

        try {
            return GeckoBackend.ease(geckoName, t);
        } catch (Throwable throwable) {
            // Missing/broken GeckoLib build: stay usable with the built-in curves.
            available = Boolean.FALSE;
            return fallback;
        }
    }

    private static boolean isAvailable() {
        if (available == null) {
            try {
                available = FabricLoader.getInstance().isModLoaded("geckolib");
            } catch (Throwable throwable) {
                available = Boolean.FALSE;
            }
        }

        return available;
    }

    /** Only linked when GeckoLib is present. */
    private static final class GeckoBackend {
        static float ease(String geckoName, float t) {
            return (float) software.bernie.geckolib.animation.object.EasingType
                .fromString(geckoName)
                .buildTransformer(1.0)
                .applyAsDouble(t);
        }
    }
}
