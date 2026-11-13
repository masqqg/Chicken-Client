package dev.chicken.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The client's own world-space ESP renderer. Boxes and tracer lines are emitted straight
 * into the vanilla buffer builders, so no other client's renderer is involved.
 */
public final class Renderer3D {
    private final PoseStack matrices;
    private final MultiBufferSource consumers;
    private final Vec3 camera;

    private VertexConsumer lineBuffer;
    private VertexConsumer sideBuffer;

    public Renderer3D(PoseStack matrices, MultiBufferSource consumers, Vec3 camera) {
        this.matrices = matrices;
        this.consumers = consumers;
        this.camera = camera;
    }

    public void box(AABB box, Color side, Color line, ShapeMode mode, int excludeDir) {
        box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, side, line, mode, excludeDir);
    }

    public void box(double x1, double y1, double z1, double x2, double y2, double z2,
                    Color side, Color line, ShapeMode mode, int excludeDir) {
        if (mode == ShapeMode.Sides || mode == ShapeMode.Both) {
            sides(x1, y1, z1, x2, y2, z2, side);
        }

        if (mode == ShapeMode.Lines || mode == ShapeMode.Both) {
            outline(x1, y1, z1, x2, y2, z2, line);
        }
    }

    public void line(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
        VertexConsumer buffer = lines();
        PoseStack.Pose pose = matrices.last();

        emitLine(buffer, pose, x1, y1, z1, x2, y2, z2, color);
    }

    /**
     * A flat ring on the world, used by the karambit's jump ripples. Drawn as a band of quads so
     * the colour and its alpha are actually visible on the ground.
     */
    public void ring(double cx, double cy, double cz, float radius, float thickness, Color color) {
        if (radius <= 0.0f || thickness <= 0.0f) return;

        VertexConsumer buffer = sides();
        PoseStack.Pose pose = matrices.last();
        int segments = 56;
        float inner = Math.max(0.0f, radius - thickness * 0.5f);
        float outer = radius + thickness * 0.5f;

        for (int i = 0; i < segments; i++) {
            double a0 = (Math.PI * 2.0) * i / segments;
            double a1 = (Math.PI * 2.0) * (i + 1) / segments;

            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);

            flatQuad(buffer, pose,
                cx + c0 * inner, cy, cz + s0 * inner,
                cx + c0 * outer, cy, cz + s0 * outer,
                cx + c1 * outer, cy, cz + s1 * outer,
                cx + c1 * inner, cy, cz + s1 * inner,
                color);
        }
    }

    private void flatQuad(VertexConsumer buffer, PoseStack.Pose pose,
                          double x1, double y1, double z1, double x2, double y2, double z2,
                          double x3, double y3, double z3, double x4, double y4, double z4, Color c) {
        buffer.addVertex(pose, (float) (x1 - camera.x), (float) (y1 - camera.y), (float) (z1 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x2 - camera.x), (float) (y2 - camera.y), (float) (z2 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x3 - camera.x), (float) (y3 - camera.y), (float) (z3 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x4 - camera.x), (float) (y4 - camera.y), (float) (z4 - camera.z)).setColor(c.r, c.g, c.b, c.a);
    }

    private VertexConsumer lines() {
        if (lineBuffer == null) lineBuffer = consumers.getBuffer(RenderTypes.lines());
        return lineBuffer;
    }

    private VertexConsumer sides() {
        if (sideBuffer == null) sideBuffer = consumers.getBuffer(RenderTypes.debugFilledBox());
        return sideBuffer;
    }

    private void emitLine(VertexConsumer buffer, PoseStack.Pose pose,
                          double x1, double y1, double z1, double x2, double y2, double z2, Color c) {
        float dx = (float) (x2 - x1);
        float dy = (float) (y2 - y1);
        float dz = (float) (z2 - z1);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0f) return;

        dx /= length;
        dy /= length;
        dz /= length;

        vertex(buffer, pose, x1, y1, z1, c, dx, dy, dz);
        vertex(buffer, pose, x2, y2, z2, c, dx, dy, dz);
    }

    private void vertex(VertexConsumer buffer, PoseStack.Pose pose, double x, double y, double z, Color c,
                        float nx, float ny, float nz) {
        buffer.addVertex(pose, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z))
            .setColor(c.r, c.g, c.b, c.a)
            .setNormal(pose, nx, ny, nz);
    }

    private void outline(double x1, double y1, double z1, double x2, double y2, double z2, Color c) {
        VertexConsumer buffer = lines();
        PoseStack.Pose pose = matrices.last();

        // Bottom ring
        emitLine(buffer, pose, x1, y1, z1, x2, y1, z1, c);
        emitLine(buffer, pose, x2, y1, z1, x2, y1, z2, c);
        emitLine(buffer, pose, x2, y1, z2, x1, y1, z2, c);
        emitLine(buffer, pose, x1, y1, z2, x1, y1, z1, c);

        // Top ring
        emitLine(buffer, pose, x1, y2, z1, x2, y2, z1, c);
        emitLine(buffer, pose, x2, y2, z1, x2, y2, z2, c);
        emitLine(buffer, pose, x2, y2, z2, x1, y2, z2, c);
        emitLine(buffer, pose, x1, y2, z2, x1, y2, z1, c);

        // Verticals
        emitLine(buffer, pose, x1, y1, z1, x1, y2, z1, c);
        emitLine(buffer, pose, x2, y1, z1, x2, y2, z1, c);
        emitLine(buffer, pose, x2, y1, z2, x2, y2, z2, c);
        emitLine(buffer, pose, x1, y1, z2, x1, y2, z2, c);
    }

    private void sides(double x1, double y1, double z1, double x2, double y2, double z2, Color c) {
        VertexConsumer buffer = sides();
        PoseStack.Pose pose = matrices.last();

        // down / up
        quad(buffer, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, c);
        quad(buffer, pose, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, c);
        // north / south
        quad(buffer, pose, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, c);
        quad(buffer, pose, x2, y1, z2, x2, y2, z2, x1, y2, z2, x1, y1, z2, c);
        // west / east
        quad(buffer, pose, x1, y1, z2, x1, y2, z2, x1, y2, z1, x1, y1, z1, c);
        quad(buffer, pose, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, c);
    }

    private void quad(VertexConsumer buffer, PoseStack.Pose pose,
                      double x1, double y1, double z1, double x2, double y2, double z2,
                      double x3, double y3, double z3, double x4, double y4, double z4, Color c) {
        buffer.addVertex(pose, (float) (x1 - camera.x), (float) (y1 - camera.y), (float) (z1 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x2 - camera.x), (float) (y2 - camera.y), (float) (z2 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x3 - camera.x), (float) (y3 - camera.y), (float) (z3 - camera.z)).setColor(c.r, c.g, c.b, c.a);
        buffer.addVertex(pose, (float) (x4 - camera.x), (float) (y4 - camera.y), (float) (z4 - camera.z)).setColor(c.r, c.g, c.b, c.a);
    }

    public PoseStack matrices() {
        return matrices;
    }

    public MultiBufferSource consumers() {
        return consumers;
    }

    public Vec3 camera() {
        return camera;
    }
}
