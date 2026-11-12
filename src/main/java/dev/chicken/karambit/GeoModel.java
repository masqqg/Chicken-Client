package dev.chicken.karambit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.chicken.ChickenClient;
import dev.chicken.render.Color;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.joml.Quaternionf;
import software.bernie.geckolib.animation.object.EasingType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Blockbench geometry + animation model, rendered by the client.
 *
 * <p>Geometry and animation come straight out of Blockbench (the usual {@code .geo.json} and
 * {@code .animation.json} exports). Bone animation curves are evaluated through GeckoLib's
 * {@link EasingType} implementations so the model animates the same way it does in game.</p>
 */
public final class GeoModel {
    private final List<Bone> bones = new ArrayList<>();
    private final Map<String, Clip> clips = new HashMap<>();

    private GeoModel() {
    }

    public static GeoModel load(String geometryPath, String animationPath) {
        GeoModel model = new GeoModel();

        try (InputStream in = GeoModel.class.getResourceAsStream(geometryPath)) {
            if (in != null) {
                model.readGeometry(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } else {
                ChickenClient.LOG.error("Missing karambit geometry {}", geometryPath);
            }
        } catch (Exception e) {
            ChickenClient.LOG.error("Could not read karambit geometry", e);
        }

        try (InputStream in = GeoModel.class.getResourceAsStream(animationPath)) {
            if (in != null) {
                model.readAnimations(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            }
        } catch (Exception e) {
            ChickenClient.LOG.error("Could not read karambit animations", e);
        }

        return model;
    }

    // ------------------------------------------------------------------ parsing

    private void readGeometry(JsonObject root) {
        JsonArray array = root.has("minecraft:geometry")
            ? root.getAsJsonArray("minecraft:geometry")
            : new JsonArray();

        for (JsonElement element : array) {
            JsonObject geometry = element.getAsJsonObject();
            if (!geometry.has("bones")) continue;

            for (JsonElement boneElement : geometry.getAsJsonArray("bones")) {
                JsonObject boneObject = boneElement.getAsJsonObject();
                Bone bone = new Bone(boneObject.get("name").getAsString());
                bone.parent = boneObject.has("parent") ? boneObject.get("parent").getAsString() : null;

                if (boneObject.has("pivot")) {
                    bone.pivot = vector(boneObject.getAsJsonArray("pivot"));
                }

                if (boneObject.has("rotation")) {
                    bone.rotation = vector(boneObject.getAsJsonArray("rotation"));
                }

                if (boneObject.has("cubes")) {
                    for (JsonElement cubeElement : boneObject.getAsJsonArray("cubes")) {
                        JsonObject cubeObject = cubeElement.getAsJsonObject();
                        Cube cube = new Cube();
                        cube.origin = vector(cubeObject.getAsJsonArray("origin"));
                        cube.size = vector(cubeObject.getAsJsonArray("size"));

                        if (cubeObject.has("rotation")) cube.rotation = vector(cubeObject.getAsJsonArray("rotation"));
                        if (cubeObject.has("pivot")) cube.pivot = vector(cubeObject.getAsJsonArray("pivot"));

                        cube.color = defaultColor(bone.name);
                        if (cubeObject.has("color")) {
                            try {
                                cube.color = Color.ofRgb(Integer.parseInt(cubeObject.get("color").getAsString().replace("#", ""), 16));
                            } catch (Exception ignored) {
                            }
                        }

                        bone.cubes.add(cube);
                    }
                }

                bones.add(bone);
            }
        }
    }

    private void readAnimations(JsonObject root) {
        if (!root.has("animations")) return;

        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("animations").entrySet()) {
            JsonObject animation = entry.getValue().getAsJsonObject();
            Clip clip = new Clip(animation.has("animation_length") ? animation.get("animation_length").getAsFloat() : 1.0f);
            clip.loop = !animation.has("loop") || animation.get("loop").getAsBoolean();
            clip.easing = animation.has("easing")
                ? EasingType.fromString(animation.get("easing").getAsString())
                : EasingType.EASE_IN_OUT_SINE;

            if (animation.has("bones")) {
                for (Map.Entry<String, JsonElement> boneEntry : animation.getAsJsonObject("bones").entrySet()) {
                    JsonObject boneAnimation = boneEntry.getValue().getAsJsonObject();
                    Channel channel = new Channel();

                    if (boneAnimation.has("rotation")) {
                        readKeyframes(boneAnimation.get("rotation"), channel.rotation);
                    }

                    if (boneAnimation.has("position")) {
                        readKeyframes(boneAnimation.get("position"), channel.position);
                    }

                    clip.channels.put(boneEntry.getKey(), channel);
                }
            }

            clips.put(entry.getKey(), clip);
        }
    }

    private void readKeyframes(JsonElement element, List<Keyframe> target) {
        if (element.isJsonArray()) {
            target.add(new Keyframe(0.0f, vector(element.getAsJsonArray())));
            return;
        }

        if (!element.isJsonObject()) return;

        JsonObject object = element.getAsJsonObject();
        if (object.has("vector")) {
            target.add(new Keyframe(0.0f, vector(object.getAsJsonArray("vector"))));
            return;
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            try {
                float time = Float.parseFloat(entry.getKey());
                JsonArray keyframe = entry.getValue().isJsonObject()
                    ? entry.getValue().getAsJsonObject().getAsJsonArray("vector")
                    : entry.getValue().getAsJsonArray();
                target.add(new Keyframe(time, vector(keyframe)));
            } catch (Exception ignored) {
            }
        }

        target.sort((a, b) -> Float.compare(a.time, b.time));
    }

    private static float[] vector(JsonArray array) {
        return new float[]{
            array.get(0).getAsFloat(),
            array.get(1).getAsFloat(),
            array.get(2).getAsFloat()
        };
    }

    private static Color defaultColor(String bone) {
        String name = bone.toLowerCase(java.util.Locale.ROOT);

        if (name.contains("blade")) return new Color(206, 214, 226, 255);
        if (name.contains("edge")) return new Color(255, 96, 84, 255);
        if (name.contains("wing")) return new Color(214, 42, 54, 255);
        if (name.contains("ring")) return new Color(52, 52, 62, 255);
        return new Color(34, 34, 42, 255);
    }

    // ------------------------------------------------------------------ rendering

    public boolean hasClip(String name) {
        return clips.containsKey(name);
    }

    public void render(PoseStack matrices, MultiBufferSource consumers, String clipName, float time,
                       float scale, Color tint, float alpha) {
        Clip clip = clips.get(clipName);

        matrices.pushPose();
        matrices.scale(scale, scale, scale);

        for (Bone bone : bones) {
            float[] rotation = bone.rotation;
            float[] position = new float[]{0.0f, 0.0f, 0.0f};

            if (clip != null) {
                Channel channel = clip.channels.get(bone.name);
                if (channel != null) {
                    float t = clip.sampleTime(time);
                    rotation = add(rotation, channel.sampleRotation(t, clip.easing));
                    position = channel.samplePosition(t, clip.easing);
                }
            }

            matrices.pushPose();
            matrices.translate(bone.pivot[0] / 16.0f, bone.pivot[1] / 16.0f, bone.pivot[2] / 16.0f);
            matrices.translate(position[0] / 16.0f, position[1] / 16.0f, position[2] / 16.0f);

            if (rotation[0] != 0.0f) matrices.mulPose(new Quaternionf().rotateX((float) Math.toRadians(rotation[0])));
            if (rotation[1] != 0.0f) matrices.mulPose(new Quaternionf().rotateY((float) Math.toRadians(rotation[1])));
            if (rotation[2] != 0.0f) matrices.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(rotation[2])));
            matrices.translate(-bone.pivot[0] / 16.0f, -bone.pivot[1] / 16.0f, -bone.pivot[2] / 16.0f);

            for (Cube cube : bone.cubes) {
                renderCube(matrices, consumers, cube, tint, alpha);
            }

            matrices.popPose();
        }

        matrices.popPose();
    }

    private void renderCube(PoseStack matrices, MultiBufferSource consumers, Cube cube, Color tint, float alpha) {
        VertexConsumer buffer = consumers.getBuffer(RenderTypes.debugQuads());
        PoseStack.Pose pose = matrices.last();

        float x1 = cube.origin[0] / 16.0f;
        float y1 = cube.origin[1] / 16.0f;
        float z1 = cube.origin[2] / 16.0f;
        float x2 = x1 + cube.size[0] / 16.0f;
        float y2 = y1 + cube.size[1] / 16.0f;
        float z2 = z1 + cube.size[2] / 16.0f;

        int r = (int) ((cube.color.r * tint.r) / 255.0f);
        int g = (int) ((cube.color.g * tint.g) / 255.0f);
        int b = (int) ((cube.color.b * tint.b) / 255.0f);
        int a = (int) (alpha * 255.0f);

        quad(buffer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, b, a);
        quad(buffer, pose, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, b, a);
        quad(buffer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, r, g, b, a);
        quad(buffer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, r, g, b, a);
        quad(buffer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, r, g, b, a);
        quad(buffer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, r, g, b, a);
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

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    // ------------------------------------------------------------------ model types

    private static final class Bone {
        final String name;
        final List<Cube> cubes = new ArrayList<>();
        String parent;
        float[] pivot = {0.0f, 0.0f, 0.0f};
        float[] rotation = {0.0f, 0.0f, 0.0f};

        Bone(String name) {
            this.name = name;
        }
    }

    private static final class Cube {
        float[] origin = {0.0f, 0.0f, 0.0f};
        float[] size = {1.0f, 1.0f, 1.0f};
        float[] rotation = {0.0f, 0.0f, 0.0f};
        float[] pivot = {0.0f, 0.0f, 0.0f};
        Color color = new Color(255, 255, 255, 255);
    }

    private static final class Keyframe {
        final float time;
        final float[] value;

        Keyframe(float time, float[] value) {
            this.time = time;
            this.value = value;
        }
    }

    private static final class Channel {
        final List<Keyframe> rotation = new ArrayList<>();
        final List<Keyframe> position = new ArrayList<>();

        float[] sampleRotation(float time, EasingType easing) {
            return sample(rotation, time, easing);
        }

        float[] samplePosition(float time, EasingType easing) {
            return sample(position, time, easing);
        }

        private static float[] sample(List<Keyframe> frames, float time, EasingType easing) {
            if (frames.isEmpty()) return new float[]{0.0f, 0.0f, 0.0f};
            if (frames.size() == 1) return frames.get(0).value;

            if (time <= frames.get(0).time) return frames.get(0).value;
            if (time >= frames.get(frames.size() - 1).time) return frames.get(frames.size() - 1).value;

            for (int i = 0; i < frames.size() - 1; i++) {
                Keyframe from = frames.get(i);
                Keyframe to = frames.get(i + 1);

                if (time < from.time || time > to.time) continue;

                float span = Math.max(0.0001f, to.time - from.time);
                double raw = (time - from.time) / span;
                double eased = easing.buildTransformer(1.0).applyAsDouble(raw);

                return new float[]{
                    (float) (from.value[0] + (to.value[0] - from.value[0]) * eased),
                    (float) (from.value[1] + (to.value[1] - from.value[1]) * eased),
                    (float) (from.value[2] + (to.value[2] - from.value[2]) * eased)
                };
            }

            return frames.get(frames.size() - 1).value;
        }
    }

    private static final class Clip {
        final float length;
        final Map<String, Channel> channels = new HashMap<>();
        boolean loop = true;
        EasingType easing = EasingType.EASE_IN_OUT_SINE;

        Clip(float length) {
            this.length = Math.max(0.1f, length);
        }

        float sampleTime(float time) {
            return loop ? time % length : Math.min(time, length);
        }
    }
}
