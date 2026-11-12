package dev.chicken.render;

import dev.chicken.ChickenClient;
import net.minecraft.client.Minecraft;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/**
 * The client's vector renderer: NanoVG over OpenGL.
 *
 * <p>Everything the client draws itself (HUD, click GUI, module overlays) goes through
 * this class, so the whole interface is resolution independent, anti-aliased and free of
 * the pixel look of the vanilla font.</p>
 */
public final class Renderer2D {
    public enum Font {
        Regular("Inter-Regular.ttf"),
        Medium("Inter-Medium.ttf"),
        SemiBold("Inter-SemiBold.ttf"),
        Bold("Inter-Bold.ttf"),
        Display("Sora-SemiBold.ttf");

        private final String file;

        Font(String file) {
            this.file = file;
        }
    }

    private static final Renderer2D INSTANCE = new Renderer2D();

    private long ctx;
    private boolean failed;
    private boolean inFrame;

    private final int[] fontHandles = new int[Font.values().length];
    private final NVGColor colorA = NVGColor.create();
    private final NVGColor colorB = NVGColor.create();
    private final NVGPaint paint = NVGPaint.create();
    private final float[] bounds = new float[4];

    private Renderer2D() {
    }

    public static Renderer2D get() {
        return INSTANCE;
    }

    public boolean isReady() {
        return ctx != 0L;
    }

    /**
     * Starts a NanoVG frame. Width and height are GUI scaled units, exactly the space the
     * client draws in.
     */
    public void begin(float width, float height) {
        if (!ensureContext()) return;

        Minecraft mc = Minecraft.getInstance();
        float pixelRatio = width <= 0.0f ? 1.0f : (float) mc.getWindow().getWidth() / width;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        NanoVG.nvgBeginFrame(ctx, width, height, pixelRatio);
        inFrame = true;
    }

    public void end() {
        if (!inFrame) return;
        inFrame = false;

        NanoVG.nvgEndFrame(ctx);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private boolean ensureContext() {
        if (ctx != 0L) return true;
        if (failed) return false;

        try {
            if (Files.exists(ChickenClient.FOLDER.resolve("disable-hud"))) {
                failed = true;
                ChickenClient.LOG.warn("HUD disabled by config/chicken-client/disable-hud");
                return false;
            }

            // Antialiasing only: NVG_STENCIL_STROKES takes exotic stencil paths that some
            // mobile GL translators (ANGLE/MobileGlues) do not handle reliably.
            ctx = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS);
            if (ctx == 0L) ctx = NanoVGGL3.nvgCreate(0);

            if (ctx == 0L) {
                failed = true;
                ChickenClient.LOG.error("NanoVG could not create a GL3 context; the client GUI will be disabled");
                return false;
            }

            for (Font font : Font.values()) {
                fontHandles[font.ordinal()] = loadFont(font);
            }

            ChickenClient.LOG.info("NanoVG HUD ready ({} fonts loaded)", fontHandles.length);
            return true;
        } catch (Throwable t) {
            failed = true;
            ChickenClient.LOG.error("NanoVG initialisation failed; the client GUI will be disabled", t);
            return false;
        }
    }

    private int loadFont(Font font) {
        String path = "/assets/" + ChickenClient.MOD_ID + "/fonts/" + font.file;

        try (InputStream in = Renderer2D.class.getResourceAsStream(path)) {
            if (in == null) {
                ChickenClient.LOG.error("Missing font {}", path);
                return -1;
            }

            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();

            // freeData = true, NanoVG owns the buffer from here on.
            return NanoVG.nvgCreateFontMem(ctx, font.name().toLowerCase(), buffer, true);
        } catch (Exception e) {
            ChickenClient.LOG.error("Could not load font {}", path, e);
            return -1;
        }
    }

    // ------------------------------------------------------------------ colour

    private NVGColor nvgColor(Color c) {
        return NanoVG.nvgRGBA((byte) c.r, (byte) c.g, (byte) c.b, (byte) c.a, colorA);
    }

    // ------------------------------------------------------------------ shapes

    public void rect(float x, float y, float w, float h, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRect(ctx, x, y, w, h);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    public void roundRect(float x, float y, float w, float h, float radius, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    /** Vertical gradient, top to bottom. */
    public void roundRectGradient(float x, float y, float w, float h, float radius, Color top, Color bottom) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx, x, y, x, y + h,
            nvgColor(top), NanoVG.nvgRGBA((byte) bottom.r, (byte) bottom.g, (byte) bottom.b, (byte) bottom.a, colorB), paint));
        NanoVG.nvgFill(ctx);
    }

    /** Horizontal gradient, left to right. */
    public void roundRectGradientH(float x, float y, float w, float h, float radius, Color left, Color right) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx, x, y, x + w, y,
            nvgColor(left), NanoVG.nvgRGBA((byte) right.r, (byte) right.g, (byte) right.b, (byte) right.a, colorB), paint));
        NanoVG.nvgFill(ctx);
    }

    public void roundRectStroke(float x, float y, float w, float h, float radius, float width, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgStrokeColor(ctx, nvgColor(c));
        NanoVG.nvgStrokeWidth(ctx, width);
        NanoVG.nvgStroke(ctx);
    }

    public void circle(float cx, float cy, float r, Color c) {
        if (!inFrame || r <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgCircle(ctx, cx, cy, r);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    public void line(float x1, float y1, float x2, float y2, float width, Color c) {
        if (!inFrame) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgMoveTo(ctx, x1, y1);
        NanoVG.nvgLineTo(ctx, x2, y2);
        NanoVG.nvgStrokeColor(ctx, nvgColor(c));
        NanoVG.nvgStrokeWidth(ctx, width);
        NanoVG.nvgStroke(ctx);
    }

    /** Soft outer glow around a rounded rectangle. */
    public void glow(float x, float y, float w, float h, float radius, float feather, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x - feather * 0.5f, y - feather * 0.5f, w + feather, h + feather, radius + feather * 0.5f);
        NanoVG.nvgFillPaint(ctx, NanoVG.nvgBoxGradient(ctx,
            x - feather * 0.5f, y - feather * 0.5f, w + feather, h + feather,
            radius + feather * 0.5f, feather,
            NanoVG.nvgRGBA((byte) c.r, (byte) c.g, (byte) c.b, (byte) c.a, colorA),
            NanoVG.nvgRGBA((byte) c.r, (byte) c.g, (byte) c.b, (byte) 0, colorB),
            paint));
        NanoVG.nvgFill(ctx);
    }

    /** A filled triangle, used by the radar arrow. */
    public void triangle(float x1, float y1, float x2, float y2, float x3, float y3, Color c) {
        if (!inFrame) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgMoveTo(ctx, x1, y1);
        NanoVG.nvgLineTo(ctx, x2, y2);
        NanoVG.nvgLineTo(ctx, x3, y3);
        NanoVG.nvgClosePath(ctx);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    public void push() {
        if (inFrame) NanoVG.nvgSave(ctx);
    }

    public void pop() {
        if (inFrame) NanoVG.nvgRestore(ctx);
    }

    public void translate(float x, float y) {
        if (inFrame) NanoVG.nvgTranslate(ctx, x, y);
    }

    public void scale(float x, float y) {
        if (inFrame) NanoVG.nvgScale(ctx, x, y);
    }

    public void rotate(float degrees) {
        if (inFrame) NanoVG.nvgRotate(ctx, (float) Math.toRadians(degrees));
    }

    public void alpha(float alpha) {
        if (inFrame) NanoVG.nvgGlobalAlpha(ctx, Math.max(0.0f, Math.min(1.0f, alpha)));
    }

    public void scissor(float x, float y, float w, float h) {
        if (inFrame) NanoVG.nvgScissor(ctx, x, y, w, h);
    }

    public void resetScissor() {
        if (inFrame) NanoVG.nvgResetScissor(ctx);
    }

    // ------------------------------------------------------------------ text

    public void text(String text, float x, float y, float size, Font font, Color c, int align) {
        if (!inFrame || text == null || text.isEmpty()) return;

        NanoVG.nvgFontFaceId(ctx, fontHandles[font.ordinal()]);
        NanoVG.nvgFontSize(ctx, size);
        NanoVG.nvgTextAlign(ctx, align);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgText(ctx, x, y, text);
    }

    public void text(String text, float x, float y, float size, Font font, Color c) {
        text(text, x, y, size, font, c, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_MIDDLE);
    }

    public void textCentered(String text, float cx, float y, float size, Font font, Color c) {
        text(text, cx, y, size, font, c, NanoVG.NVG_ALIGN_CENTER | NanoVG.NVG_ALIGN_MIDDLE);
    }

    public void textRight(String text, float right, float y, float size, Font font, Color c) {
        text(text, right, y, size, font, c, NanoVG.NVG_ALIGN_RIGHT | NanoVG.NVG_ALIGN_MIDDLE);
    }

    public void letterSpacing(float spacing) {
        if (inFrame) NanoVG.nvgTextLetterSpacing(ctx, spacing);
    }

    public void textBlur(float blur) {
        if (inFrame) NanoVG.nvgFontBlur(ctx, blur);
    }

    public float textWidth(String text, float size, Font font) {
        if (!inFrame || text == null || text.isEmpty()) return 0.0f;

        NanoVG.nvgFontFaceId(ctx, fontHandles[font.ordinal()]);
        NanoVG.nvgFontSize(ctx, size);
        NanoVG.nvgTextBounds(ctx, 0.0f, 0.0f, text, bounds);
        return bounds[2] - bounds[0];
    }
}
