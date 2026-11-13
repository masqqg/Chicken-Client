package dev.chicken.render;

import dev.chicken.ChickenClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The client's 2D renderer.
 *
 * <p>On a normal desktop driver everything the client draws itself (HUD, click GUI, module
 * overlays) goes through NanoVG, so the interface is resolution independent, anti-aliased and
 * free of the pixel look of the vanilla font.</p>
 *
 * <p>NanoVG's desktop GL3 backend talks to the driver through its own GL entry points, which the
 * GL-to-GLES translators used by Android launchers (ANGLE, MobileGlues, GL4ES, ...) do not
 * survive - there it took the whole game down with a native SIGSEGV while the context was being
 * created. On those stacks the same drawing calls are replayed through {@link GuiGraphics}
 * instead, so the interface still works without touching NanoVG at all.</p>
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

    private enum Backend {
        /** Nothing drawn yet, so nothing has been decided. */
        Undecided,
        NanoVG,
        /** Plain {@link GuiGraphics} drawing for drivers NanoVG cannot run on. */
        Vanilla,
        /** Switched off from the config folder. */
        Disabled
    }

    /** GL renderer/vendor substrings that mark a translated (non desktop) stack. */
    private static final String[] TRANSLATORS = {
        "ANGLE", "MobileGlues", "GL4ES", "gl4es", "LTW", "SwiftShader", "VirGL", "Zink"
    };

    private static final Renderer2D INSTANCE = new Renderer2D();

    private Backend backend = Backend.Undecided;

    private long ctx;
    private boolean inFrame;

    private final int[] fontHandles = new int[Font.values().length];
    private final NVGColor colorA = NVGColor.create();
    private final NVGColor colorB = NVGColor.create();
    private final NVGPaint paint = NVGPaint.create();
    private final float[] bounds = new float[4];

    // Vanilla fallback state: a small 2D transform stack on top of GuiGraphics.
    private GuiGraphics graphics;
    private final Deque<State> states = new ArrayDeque<>();
    private float shiftX;
    private float shiftY;
    private float scaleX = 1.0f;
    private float scaleY = 1.0f;
    private float globalAlpha = 1.0f;
    private boolean scissor;
    private int scissorX1;
    private int scissorY1;
    private int scissorX2;
    private int scissorY2;

    private Renderer2D() {
    }

    public static Renderer2D get() {
        return INSTANCE;
    }

    /** Whether anything will be drawn at all; the backend itself is picked on the first frame. */
    public boolean isReady() {
        return backend != Backend.Disabled;
    }

    /** Which renderer draws the interface, for the log and the HUD. */
    public String backendName() {
        return switch (backend) {
            case NanoVG -> "NanoVG";
            case Vanilla -> "Vanilla";
            case Disabled -> "Disabled";
            case Undecided -> "Undecided";
        };
    }

    /**
     * Starts a frame. Width and height are GUI scaled units, exactly the space the client draws
     * in, and {@code graphics} is what the vanilla fallback draws into.
     */
    public void begin(GuiGraphics graphics, float width, float height) {
        if (!ensureBackend()) return;

        this.graphics = graphics;
        resetTransform();

        if (backend == Backend.Vanilla) {
            inFrame = true;
            return;
        }

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

        if (backend == Backend.Vanilla) {
            // Never leave a scissor behind: it would clip the rest of the game's interface.
            if (graphics != null) graphics.disableScissor();
            graphics = null;
            resetTransform();
            return;
        }

        NanoVG.nvgEndFrame(ctx);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);
    }

    // ------------------------------------------------------------------ backend selection

    private boolean ensureBackend() {
        if (backend == Backend.Disabled) return false;
        if (backend != Backend.Undecided) return true;

        if (Files.exists(ChickenClient.FOLDER.resolve("disable-hud"))) {
            backend = Backend.Disabled;
            ChickenClient.LOG.warn("HUD disabled by config/chicken-client/disable-hud");
            return false;
        }

        String reason = unsupportedReason();
        if (reason != null) {
            backend = Backend.Vanilla;
            ChickenClient.LOG.warn("NanoVG skipped ({}); drawing the interface with the vanilla renderer. "
                + "Create config/chicken-client/force-nanovg to use NanoVG on this driver anyway.", reason);
            return true;
        }

        if (!createNanoVg()) {
            backend = Backend.Vanilla;
            ChickenClient.LOG.warn("NanoVG could not be started; drawing the interface with the vanilla renderer.");
        }

        return true;
    }

    /**
     * NanoVG is only safe on a real desktop GL driver. The check can be overruled with an empty
     * {@code config/chicken-client/force-nanovg} file.
     */
    private String unsupportedReason() {
        if (Files.exists(ChickenClient.FOLDER.resolve("force-nanovg"))) return null;

        if (glString(GL11.GL_VERSION).contains("OpenGL ES")) return "OpenGL ES context";

        String renderer = glString(GL11.GL_RENDERER);
        String vendor = glString(GL11.GL_VENDOR);

        for (String translator : TRANSLATORS) {
            if (renderer.contains(translator) || vendor.contains(translator)) {
                return "GL translator " + translator;
            }
        }

        return null;
    }

    private static String glString(int name) {
        try {
            String value = GL11.glGetString(name);
            return value == null ? "" : value;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean createNanoVg() {
        try {
            // Antialiasing only: NVG_STENCIL_STROKES takes exotic stencil paths that some
            // mobile GL translators (ANGLE/MobileGlues) do not handle reliably.
            ctx = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS);
            if (ctx == 0L) ctx = NanoVGGL3.nvgCreate(0);

            if (ctx == 0L) return false;

            int loaded = 0;
            for (Font font : Font.values()) {
                fontHandles[font.ordinal()] = loadFont(font);
                if (fontHandles[font.ordinal()] >= 0) loaded++;
            }

            if (loaded == 0) {
                NanoVGGL3.nvgDelete(ctx);
                ctx = 0L;
                return false;
            }

            backend = Backend.NanoVG;
            ChickenClient.LOG.info("NanoVG HUD ready ({} fonts loaded)", loaded);
            return true;
        } catch (Throwable t) {
            ChickenClient.LOG.error("NanoVG initialisation failed", t);
            ctx = 0L;
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

    private int argb(Color c) {
        return c.toArgb((int) (c.a * globalAlpha));
    }

    /** Horizontal position of a drawing coordinate, through the fallback transform stack. */
    private float tx(float x) {
        return shiftX + x * scaleX;
    }

    /** Vertical position of a drawing coordinate, through the fallback transform stack. */
    private float ty(float y) {
        return shiftY + y * scaleY;
    }

    private void fill(float x1, float y1, float x2, float y2, Color c) {
        if (graphics == null) return;

        int ax = (int) Math.floor(tx(Math.min(x1, x2)));
        int ay = (int) Math.floor(ty(Math.min(y1, y2)));
        int bx = (int) Math.ceil(tx(Math.max(x1, x2)));
        int by = (int) Math.ceil(ty(Math.max(y1, y2)));

        if (bx <= ax || by <= ay) return;
        graphics.fill(ax, ay, bx, by, argb(c));
    }

    // ------------------------------------------------------------------ shapes

    public void rect(float x, float y, float w, float h, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(x, y, x + w, y + h, c);
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRect(ctx, x, y, w, h);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    public void roundRect(float x, float y, float w, float h, float radius, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(x, y, x + w, y + h, c);
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    /** Vertical gradient, top to bottom. */
    public void roundRectGradient(float x, float y, float w, float h, float radius, Color top, Color bottom) {
        if (!inFrame || w <= 0 || h <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(x, y, x + w, y + h, Color.lerp(top, bottom, 0.5f));
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx, x, y, x, y + h,
            nvgColor(top), NanoVG.nvgRGBA((byte) bottom.r, (byte) bottom.g, (byte) bottom.b, (byte) bottom.a, colorB), paint));
        NanoVG.nvgFill(ctx);
    }

    /** Horizontal gradient, left to right. */
    public void roundRectGradientH(float x, float y, float w, float h, float radius, Color left, Color right) {
        if (!inFrame || w <= 0 || h <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(x, y, x + w, y + h, Color.lerp(left, right, 0.5f));
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgFillPaint(ctx, NanoVG.nvgLinearGradient(ctx, x, y, x + w, y,
            nvgColor(left), NanoVG.nvgRGBA((byte) right.r, (byte) right.g, (byte) right.b, (byte) right.a, colorB), paint));
        NanoVG.nvgFill(ctx);
    }

    public void roundRectStroke(float x, float y, float w, float h, float radius, float width, Color c) {
        if (!inFrame || w <= 0 || h <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(x, y, x + w, y + width, c);
            fill(x, y + h - width, x + w, y + h, c);
            fill(x, y, x + width, y + h, c);
            fill(x + w - width, y, x + w, y + h, c);
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgRoundedRect(ctx, x, y, w, h, radius);
        NanoVG.nvgStrokeColor(ctx, nvgColor(c));
        NanoVG.nvgStrokeWidth(ctx, width);
        NanoVG.nvgStroke(ctx);
    }

    public void circle(float cx, float cy, float r, Color c) {
        if (!inFrame || r <= 0) return;

        if (backend == Backend.Vanilla) {
            fill(cx - r, cy - r, cx + r, cy + r, c);
            return;
        }

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgCircle(ctx, cx, cy, r);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    public void line(float x1, float y1, float x2, float y2, float width, Color c) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            float half = Math.max(0.5f, width * 0.5f);

            if (Math.abs(x2 - x1) >= Math.abs(y2 - y1)) {
                fill(Math.min(x1, x2), y1 - half, Math.max(x1, x2), y1 + half, c);
            } else {
                fill(x1 - half, Math.min(y1, y2), x1 + half, Math.max(y1, y2), c);
            }

            return;
        }

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

        // The glow is a fake, NanoVG-only lighting effect, so the flat fallback skips it.
        if (backend == Backend.Vanilla) return;

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

        // Triangles only exist in the NanoVG path; the fallback draws nothing for them.
        if (backend == Backend.Vanilla) return;

        NanoVG.nvgBeginPath(ctx);
        NanoVG.nvgMoveTo(ctx, x1, y1);
        NanoVG.nvgLineTo(ctx, x2, y2);
        NanoVG.nvgLineTo(ctx, x3, y3);
        NanoVG.nvgClosePath(ctx);
        NanoVG.nvgFillColor(ctx, nvgColor(c));
        NanoVG.nvgFill(ctx);
    }

    // ------------------------------------------------------------------ state

    private static final class State {
        float shiftX;
        float shiftY;
        float scaleX;
        float scaleY;
        float alpha;
        boolean scissor;
        int scissorX1;
        int scissorY1;
        int scissorX2;
        int scissorY2;
    }

    private State capture() {
        State state = new State();
        state.shiftX = shiftX;
        state.shiftY = shiftY;
        state.scaleX = scaleX;
        state.scaleY = scaleY;
        state.alpha = globalAlpha;
        state.scissor = scissor;
        state.scissorX1 = scissorX1;
        state.scissorY1 = scissorY1;
        state.scissorX2 = scissorX2;
        state.scissorY2 = scissorY2;
        return state;
    }

    private void applyScissor() {
        if (graphics == null) return;

        if (scissor) {
            graphics.enableScissor(scissorX1, scissorY1, scissorX2, scissorY2);
        } else {
            graphics.disableScissor();
        }
    }

    private void resetTransform() {
        states.clear();
        shiftX = 0.0f;
        shiftY = 0.0f;
        scaleX = 1.0f;
        scaleY = 1.0f;
        globalAlpha = 1.0f;
        scissor = false;
    }

    public void push() {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            states.push(capture());
            return;
        }

        NanoVG.nvgSave(ctx);
    }

    public void pop() {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            State state = states.poll();
            if (state == null) return;

            boolean hadScissor = scissor;
            shiftX = state.shiftX;
            shiftY = state.shiftY;
            scaleX = state.scaleX;
            scaleY = state.scaleY;
            globalAlpha = state.alpha;
            scissor = state.scissor;
            scissorX1 = state.scissorX1;
            scissorY1 = state.scissorY1;
            scissorX2 = state.scissorX2;
            scissorY2 = state.scissorY2;

            if (hadScissor || scissor) applyScissor();
            return;
        }

        NanoVG.nvgRestore(ctx);
    }

    public void translate(float x, float y) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            shiftX += x * scaleX;
            shiftY += y * scaleY;
            return;
        }

        NanoVG.nvgTranslate(ctx, x, y);
    }

    public void scale(float x, float y) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            scaleX *= x;
            scaleY *= y;
            return;
        }

        NanoVG.nvgScale(ctx, x, y);
    }

    public void rotate(float degrees) {
        if (!inFrame) return;

        // The flat fallback has no rotation; widgets are drawn upright instead.
        if (backend == Backend.Vanilla) return;

        NanoVG.nvgRotate(ctx, (float) Math.toRadians(degrees));
    }

    public void alpha(float alpha) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            globalAlpha = Math.max(0.0f, Math.min(1.0f, alpha));
            return;
        }

        NanoVG.nvgGlobalAlpha(ctx, Math.max(0.0f, Math.min(1.0f, alpha)));
    }

    public void scissor(float x, float y, float w, float h) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            scissor = true;
            scissorX1 = (int) Math.floor(tx(x));
            scissorY1 = (int) Math.floor(ty(y));
            scissorX2 = (int) Math.ceil(tx(x + w));
            scissorY2 = (int) Math.ceil(ty(y + h));
            applyScissor();
            return;
        }

        NanoVG.nvgScissor(ctx, x, y, w, h);
    }

    public void resetScissor() {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) {
            scissor = false;
            applyScissor();
            return;
        }

        NanoVG.nvgResetScissor(ctx);
    }

    // ------------------------------------------------------------------ text

    public void text(String text, float x, float y, float size, Font font, Color c, int align) {
        if (!inFrame || text == null || text.isEmpty()) return;

        if (backend == Backend.Vanilla) {
            Minecraft mc = Minecraft.getInstance();
            int width = mc.font.width(text);

            float drawX = x;
            if ((align & NanoVG.NVG_ALIGN_CENTER) != 0) drawX -= width * 0.5f;
            else if ((align & NanoVG.NVG_ALIGN_RIGHT) != 0) drawX -= width;

            float drawY = y - 4.0f;
            if ((align & NanoVG.NVG_ALIGN_TOP) != 0) drawY = y;
            else if ((align & NanoVG.NVG_ALIGN_BOTTOM) != 0) drawY = y - 8.0f;

            graphics.drawString(mc.font, text, (int) tx(drawX), (int) ty(drawY), argb(c), false);
            return;
        }

        int handle = fontHandles[font.ordinal()];
        if (handle < 0) return;

        NanoVG.nvgFontFaceId(ctx, handle);
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
        if (!inFrame) return;

        if (backend == Backend.Vanilla) return;

        NanoVG.nvgTextLetterSpacing(ctx, spacing);
    }

    public void textBlur(float blur) {
        if (!inFrame) return;

        if (backend == Backend.Vanilla) return;

        NanoVG.nvgFontBlur(ctx, blur);
    }

    public float textWidth(String text, float size, Font font) {
        if (!inFrame || text == null || text.isEmpty()) return 0.0f;

        if (backend == Backend.Vanilla) {
            // Keep layout proportional to the requested size even though the fallback always
            // draws the vanilla bitmap font.
            return Minecraft.getInstance().font.width(text) * Math.max(0.5f, size / 9.0f);
        }

        int handle = fontHandles[font.ordinal()];
        if (handle < 0) return 0.0f;

        NanoVG.nvgFontFaceId(ctx, handle);
        NanoVG.nvgFontSize(ctx, size);
        NanoVG.nvgTextBounds(ctx, 0.0f, 0.0f, text, bounds);
        return bounds[2] - bounds[0];
    }
}
