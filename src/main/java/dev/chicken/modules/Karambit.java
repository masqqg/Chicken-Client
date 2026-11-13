package dev.chicken.modules;

import dev.chicken.events.Render3DEvent;
import dev.chicken.events.TickEvent;
import dev.chicken.events.EventHandler;
import dev.chicken.karambit.GeoModel;
import dev.chicken.render.Color;
import dev.chicken.render.SettingColor;
import dev.chicken.settings.BoolSetting;
import dev.chicken.settings.ColorSetting;
import dev.chicken.settings.DoubleSetting;
import dev.chicken.settings.EnumSetting;
import dev.chicken.settings.IntSetting;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import dev.chicken.systems.Categories;
import dev.chicken.systems.Module;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * Red Wings — a first person karambit cosmetic.
 *
 * <p>The knife is a Blockbench model animated through the client's GeckoLib-backed animation
 * runtime, with a pair of glowing red wing trails behind the blade.</p>
 */
public class Karambit extends Module {
    public enum Animation {
        Idle,
        Inspect
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Animation> animation = sgGeneral.add(new EnumSetting.Builder<Animation>()
        .name("animation")
        .description("Which Blockbench animation the knife plays.")
        .defaultValue(Animation.Idle)
        .enumClass(Animation.class)
        .build());

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Size of the knife on screen.")
        .defaultValue(1.0)
        .range(0.4, 2.5)
        .sliderRange(0.4, 2.0)
        .build());

    private final Setting<Double> offsetX = sgRender.add(new DoubleSetting.Builder()
        .name("offset-x")
        .description("Horizontal screen offset.")
        .defaultValue(0.52)
        .range(-1.5, 1.5)
        .sliderRange(-1.0, 1.0)
        .build());

    private final Setting<Double> offsetY = sgRender.add(new DoubleSetting.Builder()
        .name("offset-y")
        .description("Vertical screen offset.")
        .defaultValue(-0.42)
        .range(-1.5, 1.5)
        .sliderRange(-1.0, 1.0)
        .build());

    private final Setting<Double> offsetZ = sgRender.add(new DoubleSetting.Builder()
        .name("offset-z")
        .description("Distance in front of the camera.")
        .defaultValue(-0.9)
        .range(-3.0, -0.2)
        .sliderRange(-2.0, -0.3)
        .build());

    private final Setting<Boolean> wings = sgRender.add(new BoolSetting.Builder()
        .name("red-wings")
        .description("Glowing red wing trails behind the blade.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> wingLength = sgRender.add(new IntSetting.Builder()
        .name("wing-trail")
        .description("How many trail segments the wings leave behind.")
        .defaultValue(9)
        .range(3, 18)
        .sliderRange(3, 16)
        .build());

    private final Setting<SettingColor> wingColor = sgRender.add(new ColorSetting.Builder()
        .name("wing-color")
        .description("Trail colour.")
        .defaultValue(new SettingColor(226, 34, 48, 255))
        .build());

    private final Setting<Boolean> glow = sgRender.add(new BoolSetting.Builder()
        .name("glow")
        .description("Adds a soft red rim glow around the knife.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> cosmicSky = sgRender.add(new BoolSetting.Builder()
        .name("cosmic-sky")
        .description("Round ripples that spread north, east, south and west when you jump.")
        .defaultValue(true)
        .build());

    private final Setting<Double> rippleDistance = sgRender.add(new DoubleSetting.Builder()
        .name("ripple-distance")
        .description("How many blocks away from you the four ripples appear.")
        .defaultValue(5.0)
        .range(1.0, 12.0)
        .sliderRange(2.0, 8.0)
        .build());

    /** How long one jump ripple takes to reach its full size. */
    private static final long RIPPLE_MILLIS = 1300L;

    private GeoModel model;
    private float time;
    private final float[] trail = new float[64];

    private boolean wasOnGround = true;
    private long rippleStart;
    private double rippleX;
    private double rippleY;
    private double rippleZ;

    public Karambit() {
        super(Categories.VISUALS, "karambit", "Red Wings karambit with animated wings in first person.");
    }

    @Override
    protected void onActivate() {
        if (model == null) {
            model = GeoModel.load(
                "/assets/chicken-client/geo/karambit.geo.json",
                "/assets/chicken-client/animations/karambit.animation.json"
            );
        }

        time = 0.0f;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        time += 1.0f / 20.0f;

        // Keep the wing trail history in sync with the animation.
        System.arraycopy(trail, 0, trail, 1, trail.length - 1);
        trail[0] = (float) Math.sin(time * 1.6) * 0.06f;

        // A jump starts the cosmic sky ripples, anchored where the jump left the ground.
        boolean grounded = mc.player.onGround();
        if (cosmicSky.get() && wasOnGround && !grounded && mc.player.getDeltaMovement().y > 0.0) {
            rippleStart = System.currentTimeMillis();
            rippleX = mc.player.getX();
            rippleY = mc.player.getY();
            rippleZ = mc.player.getZ();
        }

        wasOnGround = grounded;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        // Ripples live in the world, so they show in third person as well.
        if (cosmicSky.get()) renderCosmicSky(event);

        if (mc.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) return;
        if (model == null || !model.hasClip(clipName())) return;

        PoseStack matrices = event.renderer.matrices();

        matrices.pushPose();
        matrices.translate(offsetX.get(), offsetY.get(), offsetZ.get());

        if (glow.get()) {
            renderGlow(event, matrices);
        }

        model.render(matrices, event.renderer.consumers(), clipName(), time, scale.get().floatValue(),
            new Color(255, 255, 255, 255), 1.0f);

        if (wings.get()) {
            renderWings(event, matrices);
        }

        matrices.popPose();
    }

    private String clipName() {
        return animation.get() == Animation.Inspect ? "inspect" : "idle";
    }

    /**
     * The cosmic sky: three rings race out from four points five blocks to the north, east, south
     * and west of the jump, expanding, thinning and fading as they go.
     */
    private void renderCosmicSky(Render3DEvent event) {
        if (rippleStart == 0L) return;

        float progress = (System.currentTimeMillis() - rippleStart) / (float) RIPPLE_MILLIS;
        if (progress < 0.0f || progress >= 1.0f) return;

        double distance = rippleDistance.get();
        Color base = wingColor.get();
        double groundY = rippleY + 0.05;

        for (int wave = 0; wave < 3; wave++) {
            float t = progress - wave * 0.18f;
            if (t <= 0.0f || t >= 1.0f) continue;

            float radius = 0.35f + t * 2.1f;
            float thickness = 0.30f * (1.0f - t * 0.5f);
            Color color = new Color(base.r, base.g, base.b, (int) ((1.0f - t) * 0.7f * 255.0f));

            event.renderer.ring(rippleX + distance, groundY, rippleZ, radius, thickness, color);
            event.renderer.ring(rippleX - distance, groundY, rippleZ, radius, thickness, color);
            event.renderer.ring(rippleX, groundY, rippleZ + distance, radius, thickness, color);
            event.renderer.ring(rippleX, groundY, rippleZ - distance, radius, thickness, color);
        }
    }

    @Override
    protected void onDeactivate() {
        rippleStart = 0L;
    }

    private void renderGlow(Render3DEvent event, PoseStack matrices) {
        Color accent = wingColor.get();
        float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.4);

        matrices.pushPose();
        matrices.scale(1.08f, 1.08f, 1.08f);
        model.render(matrices, event.renderer.consumers(), clipName(), time, scale.get().floatValue(),
            accent, 0.10f + 0.06f * pulse);
        matrices.popPose();
    }

    /** Two fading ribbons behind the blade, drawn as thin quads in view space. */
    private void renderWings(Render3DEvent event, PoseStack matrices) {
        VertexConsumer buffer = event.renderer.consumers().getBuffer(RenderTypes.debugQuads());
        Color base = wingColor.get();
        int segments = Math.max(3, wingLength.get());
        float s = scale.get().floatValue() * 0.06f;

        for (int side = -1; side <= 1; side += 2) {
            for (int i = 0; i < segments; i++) {
                float t = i / (float) segments;
                float alpha = (1.0f - t) * 0.55f;
                float sway = trail[Math.min(i, trail.length - 1)] * (1.0f + i * 0.35f);

                float x = side * (0.16f + 0.30f * t + sway * 0.4f);
                float y = 0.10f - 0.34f * t + (float) Math.sin(time * 2.0 + i * 0.5) * 0.02f;
                float z = 0.04f + 0.10f * t;

                float w = 0.055f * (1.0f - t * 0.6f) * s * 8.0f;
                float h = 0.012f * s * 8.0f;

                int r = base.r;
                int g = (int) (base.g * (0.4f + 0.6f * (1.0f - t)));
                int b = (int) (base.b * (0.4f + 0.6f * (1.0f - t)));
                int a = (int) (alpha * 255.0f);

                PoseStack.Pose pose = matrices.last();
                quad(buffer, pose, x - w, y - h, z, x + w, y - h, z, x + w, y + h, z, x - w, y + h, z, r, g, b, a);
            }
        }
    }

    private void quad(VertexConsumer buffer, PoseStack.Pose pose,
                      float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, float x4, float y4, float z4,
                      int r, int g, int b, int a) {
        buffer.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        buffer.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        buffer.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        buffer.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }
}
