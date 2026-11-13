package dev.chicken.gui;

import dev.chicken.ChickenClient;
import dev.chicken.render.Color;
import dev.chicken.render.Renderer2D;
import dev.chicken.settings.BoolSetting;
import dev.chicken.settings.ColorSetting;
import dev.chicken.settings.DoubleSetting;
import dev.chicken.settings.EnumSetting;
import dev.chicken.settings.IntSetting;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import dev.chicken.settings.StringSetting;
import dev.chicken.systems.Categories;
import dev.chicken.systems.Category;
import dev.chicken.systems.Module;
import dev.chicken.systems.Modules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.nanovg.NanoVG;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.Map;

/**
 * The Chicken Client click GUI. Opened with Right Shift.
 *
 * <p>Everything is drawn with NanoVG: glass panels, gradient accents, spring animations and
 * a live preview of the theme. Interaction is immediate-mode: widgets register a hotspot
 * while they draw and the mouse handlers pick the topmost one.</p>
 */
public class ClickGui extends Screen {
    private static final float PANEL_WIDTH = 522.0f;
    private static final float PANEL_HEIGHT = 332.0f;
    private static final float SIDEBAR_WIDTH = 138.0f;
    private static final float HEADER_HEIGHT = 46.0f;
    private static final float CARD_HEIGHT = 44.0f;
    private static final float CARD_GAP = 6.0f;

    private static final Color[] PRESETS = {
        new Color(255, 138, 61, 255),
        new Color(255, 176, 103, 255),
        new Color(240, 92, 46, 255),
        new Color(255, 208, 128, 255),
        new Color(126, 208, 255, 255),
        new Color(150, 255, 190, 255),
        new Color(255, 110, 160, 255),
        new Color(220, 220, 235, 255),
    };

    private final Anim open = new Anim(0.0);
    private final Anim sidebar = new Anim(0.0);
    private final Map<String, Anim> toggles = new HashMap<>();
    private final Map<String, Anim> expands = new HashMap<>();
    private final Map<String, Anim> hovers = new HashMap<>();
    private final List<Hotspot> hotspots = new ArrayList<>();

    private Category selected = Categories.WORLD;
    private String search = "";
    private boolean searchFocused;
    private float scroll;
    private float scrollTarget;
    private long lastFrameNanos;
    private float cursorBlink;

    private Hotspot dragging;
    private float dragStartX;

    public ClickGui() {
        super(Component.literal(ChickenClient.NAME));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Renderer2D r = Renderer2D.get();
        if (!r.isReady()) {
            super.render(graphics, mouseX, mouseY, partialTick);
            return;
        }

        float delta = frameDelta();
        open.set(1.0);
        open.update(delta);
        sidebar.set(1.0);
        sidebar.update(delta);
        cursorBlink += delta;

        float appear = Anim.clamp01(open.f());
        float eased = Anim.easeOutCubic(appear);

        // The screen already gets vanilla's world blur when it opens; blurring here as well
        // throws "Can only blur once per frame".
        r.begin(graphics, width, height);
        hotspots.clear();

        drawBackdrop(r, eased);

        float panelWidth = PANEL_WIDTH;
        float panelHeight = PANEL_HEIGHT;

        if (width < PANEL_WIDTH + 20.0f) panelWidth = width - 20.0f;
        if (height < PANEL_HEIGHT + 20.0f) panelHeight = height - 20.0f;

        float px = (width - panelWidth) * 0.5f;
        float py = (height - panelHeight) * 0.5f;

        float scale = 0.94f + 0.06f * eased;
        r.alpha(eased);
        r.push();

        try {
            r.translate(px + panelWidth * 0.5f, py + panelHeight * 0.5f);
            r.scale(scale, scale);
            r.translate(-(px + panelWidth * 0.5f), -(py + panelHeight * 0.5f));

            drawPanel(r, px, py, panelWidth, panelHeight, mouseX, mouseY, delta);
        } catch (Throwable t) {
            ChickenClient.LOG.error("Click GUI render failed", t);
        } finally {
            r.pop();
            r.end();
        }
    }

    private float frameDelta() {
        long now = System.nanoTime();
        float delta = lastFrameNanos == 0L ? 0.016f : (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        return Math.min(0.05f, delta);
    }

    private void drawBackdrop(Renderer2D r, float appear) {
        r.roundRectGradient(0, 0, width, height, 0.0f,
            Theme.withAlpha(Theme.BACKDROP, (int) (200 * appear)),
            Theme.withAlpha(new Color(6, 6, 9, 232), (int) (232 * appear)));

        // Slow drifting accent orbs for depth.
        float t = cursorBlink;
        float ox = width * 0.5f + (float) Math.sin(t * 0.35f) * 90.0f;
        float oy = height * 0.35f + (float) Math.cos(t * 0.28f) * 60.0f;
        r.glow(ox - 160.0f, oy - 160.0f, 320.0f, 320.0f, 160.0f, 120.0f,
            Theme.withAlpha(Theme.ACCENT, (int) (10 * appear)));
    }

    private void drawPanel(Renderer2D r, float x, float y, float w, float h, int mouseX, int mouseY, float delta) {
        // Shadow + glass body + accent rim
        r.glow(x + 2.0f, y + 6.0f, w - 4.0f, h, 16.0f, 26.0f, Theme.withAlpha(Theme.SHADOW, 190));
        r.roundRectGradient(x, y, w, h, 16.0f, Theme.WINDOW_TOP, Theme.WINDOW);
        r.roundRectStroke(x, y, w, h, 16.0f, 1.0f, Theme.STROKE);

        // Top accent hairline
        r.roundRectGradientH(x + 16.0f, y + 0.5f, w - 32.0f, 1.5f, 0.75f,
            Theme.withAlpha(Theme.ACCENT, 0), Theme.withAlpha(Theme.ACCENT, 190));

        drawSidebar(r, x, y, h, mouseX, mouseY, delta);
        drawContent(r, x + SIDEBAR_WIDTH, y, w - SIDEBAR_WIDTH, h, mouseX, mouseY, delta);
    }

    private void drawSidebar(Renderer2D r, float x, float y, float h, int mouseX, int mouseY, float delta) {
        float w = SIDEBAR_WIDTH;

        r.roundRect(x + 1.0f, y + 1.0f, w - 1.0f, h - 2.0f, 16.0f, Theme.SIDEBAR);
        r.line(x + w, y + 14.0f, x + w, y + h - 14.0f, 1.0f, Theme.withAlpha(Theme.STROKE, 22));

        // Brand
        Logo.glowCloud(r, x + 16.0f, y + 17.0f, 17.0f);
        r.text("CHICKEN", x + 39.0f, y + 25.0f, 11.5f, Renderer2D.Font.Display, Theme.TEXT);
        r.text("CLIENT", x + 39.0f, y + 36.0f, 8.0f, Renderer2D.Font.Display, Theme.ACCENT_LIGHT);

        // Categories
        float cy = y + 62.0f;
        for (Category category : Modules.get().getCategories()) {
            boolean active = category == selected;
            String key = "cat-" + category.name;

            Anim hover = hovers.computeIfAbsent(key, k -> new Anim(0.0));
            boolean over = inside(mouseX, mouseY, x + 8.0f, cy, w - 18.0f, 28.0f);
            hover.set(over || active ? 1.0 : 0.0);
            float hv = hover.update(delta);

            if (active) {
                r.roundRect(x + 8.0f, cy, w - 18.0f, 28.0f, 9.0f, Theme.PANEL_ACTIVE);
                r.roundRectGradient(x + 8.0f, cy + 7.0f, 2.5f, 14.0f, 1.25f, Theme.ACCENT_LIGHT, Theme.ACCENT_DEEP);
                r.glow(x + 8.0f, cy, w - 18.0f, 28.0f, 9.0f, 14.0f, Theme.withAlpha(Theme.ACCENT, 22));
            } else if (hv > 0.01f) {
                r.roundRect(x + 8.0f, cy, w - 18.0f, 28.0f, 9.0f, Theme.withAlpha(Theme.PANEL_HOVER, (int) (200 * hv)));
            }

            Color iconColor = active ? Theme.ACCENT_LIGHT : Theme.withAlpha(Theme.TEXT_DIM, (int) (170 + 60 * hv));
            Icons.draw(r, category.icon, x + 16.0f, cy + 7.0f, 14.0f, iconColor);
            r.text(category.name, x + 38.0f, cy + 14.5f, 9.8f, Renderer2D.Font.SemiBold,
                active ? Theme.TEXT : Theme.withAlpha(Theme.TEXT_DIM, 230));

            int count = Modules.get().get(category).size();
            r.textRight(String.valueOf(count), x + w - 16.0f, cy + 14.5f, 8.5f, Renderer2D.Font.Medium,
                Theme.TEXT_FAINT);

            float rowY = cy;
            addHotspot(x + 8.0f, rowY, w - 18.0f, 28.0f, () -> selected = category);
            cy += 32.0f;
        }

        // Search field
        float searchY = y + h - 46.0f;
        r.roundRect(x + 10.0f, searchY, w - 20.0f, 26.0f, 8.0f, Theme.withAlpha(Theme.PANEL, 230));
        r.roundRectStroke(x + 10.0f, searchY, w - 20.0f, 26.0f, 8.0f, 1.0f,
            searchFocused ? Theme.withAlpha(Theme.ACCENT, 120) : Theme.withAlpha(Theme.STROKE, 30));
        Icons.search(r, x + 16.0f, searchY + 6.0f, 13.0f, Theme.TEXT_FAINT);

        String shown = search.isEmpty() && !searchFocused ? "Search modules" : search;
        Color searchColor = search.isEmpty() && !searchFocused ? Theme.TEXT_FAINT : Theme.TEXT;
        r.text(shown, x + 34.0f, searchY + 13.5f, 9.0f, Renderer2D.Font.Regular, searchColor);

        if (searchFocused && ((int) (cursorBlink * 2.0f)) % 2 == 0) {
            float cx = x + 34.0f + r.textWidth(search, 9.0f, Renderer2D.Font.Regular) + 1.0f;
            r.line(cx, searchY + 7.0f, cx, searchY + 19.0f, 1.0f, Theme.ACCENT);
        }

        addHotspot(x + 10.0f, searchY, w - 20.0f, 26.0f, () -> searchFocused = true);

        // Hint
        r.text("RIGHT SHIFT", x + 16.0f, y + h - 12.0f, 7.5f, Renderer2D.Font.Medium, Theme.TEXT_FAINT);
        r.textRight("CLOSE", x + w - 16.0f, y + h - 12.0f, 7.5f, Renderer2D.Font.Medium, Theme.TEXT_FAINT);
    }

    private void drawContent(Renderer2D r, float x, float y, float w, float h, int mouseX, int mouseY, float delta) {
        List<Module> list = filtered();

        r.text(selected.name, x + 18.0f, y + 25.0f, 13.5f, Renderer2D.Font.SemiBold, Theme.TEXT);
        r.text(selected.description, x + 18.0f, y + 38.0f, 8.5f, Renderer2D.Font.Regular, Theme.TEXT_FAINT);
        r.textRight(list.size() + " modules", x + w - 18.0f, y + 25.0f, 8.5f, Renderer2D.Font.Medium,
            Theme.TEXT_DIM);

        // Header divider
        r.roundRectGradientH(x + 18.0f, y + HEADER_HEIGHT, w - 36.0f, 1.0f, 0.5f,
            Theme.withAlpha(Theme.ACCENT, 90), Theme.withAlpha(Theme.STROKE, 24));

        float viewTop = y + HEADER_HEIGHT + 10.0f;
        float viewBottom = y + h - 12.0f;
        float viewHeight = viewBottom - viewTop;
        float contentWidth = w - 36.0f;

        float totalHeight = 0.0f;
        for (Module module : list) totalHeight += cardHeight(module) + CARD_GAP;
        float maxScroll = Math.max(0.0f, totalHeight - viewHeight);

        scrollTarget = Math.max(0.0f, Math.min(maxScroll, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1.0f, delta * 16.0f);

        r.push();
        r.scissor(x + 12.0f, viewTop, w - 24.0f, viewHeight);

        float cy = viewTop - scroll;

        for (Module module : list) {
            float cardH = cardHeight(module);
            if (cy + cardH > viewTop - 40.0f && cy < viewBottom + 40.0f) {
                drawCard(r, module, x + 18.0f, cy, contentWidth, cardH, mouseX, mouseY, delta);
            }
            cy += cardH + CARD_GAP;
        }

        r.pop();

        // Scrollbar
        if (maxScroll > 0.5f) {
            float trackX = x + w - 8.0f;
            float thumbHeight = Math.max(24.0f, viewHeight * (viewHeight / Math.max(totalHeight, 1.0f)));
            float t = maxScroll <= 0.0f ? 0.0f : scroll / maxScroll;
            float thumbY = viewTop + (viewHeight - thumbHeight) * t;
            r.roundRect(trackX, viewTop, 3.0f, viewHeight, 1.5f, Theme.withAlpha(Theme.TRACK, 40));
            r.roundRect(trackX, thumbY, 3.0f, thumbHeight, 1.5f, Theme.withAlpha(Theme.ACCENT, 170));
        }
    }

    private void drawCard(Renderer2D r, Module module, float x, float y, float w, float h,
                          int mouseX, int mouseY, float delta) {
        String key = module.name;
        boolean over = inside(mouseX, mouseY, x, y, w, CARD_HEIGHT);

        Anim hover = hovers.computeIfAbsent("card-" + key, k -> new Anim(0.0));
        hover.set(over ? 1.0 : 0.0);
        float hv = Anim.easeOutCubic(hover.update(delta));

        Anim toggle = toggles.computeIfAbsent(key, k -> new Anim(module.isActive() ? 1.0 : 0.0));
        toggle.set(module.isActive() ? 1.0 : 0.0);
        float tv = toggle.update(delta);

        Anim expand = expands.computeIfAbsent(key, k -> new Anim(0.0));
        expand.set(expanded.contains(key) ? 1.0 : 0.0);
        float ev = Anim.easeOutCubic(expand.update(delta));

        // Card body
        Color top = Color.lerp(Theme.PANEL, Theme.PANEL_HOVER, hv * 0.85f);
        Color bottom = Color.lerp(Theme.PANEL, Theme.PANEL_ACTIVE, tv * 0.55f);
        r.roundRectGradient(x + 1.0f, y + 1.0f + hv * -1.0f, w - 2.0f, CARD_HEIGHT - 2.0f, 10.0f, top, bottom);

        if (tv > 0.01f) {
            r.roundRectStroke(x + 1.0f, y + 1.0f, w - 2.0f, CARD_HEIGHT - 2.0f, 10.0f, 1.0f,
                Theme.withAlpha(Theme.ACCENT, (int) (110 * tv)));
            r.roundRectGradient(x + 1.0f, y + 10.0f, 2.5f, CARD_HEIGHT - 20.0f, 1.25f,
                Theme.withAlpha(Theme.ACCENT_LIGHT, (int) (255 * tv)),
                Theme.withAlpha(Theme.ACCENT_DEEP, (int) (255 * tv)));
        } else {
            r.roundRectStroke(x + 1.0f, y + 1.0f, w - 2.0f, CARD_HEIGHT - 2.0f, 10.0f, 1.0f,
                Theme.withAlpha(Theme.STROKE, (int) (24 + 20 * hv)));
        }

        r.text(module.title(), x + 15.0f, y + 16.0f, 10.2f, Renderer2D.Font.SemiBold,
            tv > 0.5f ? Theme.TEXT : Theme.withAlpha(Theme.TEXT, 235));
        r.text(module.description, x + 15.0f, y + 29.0f, 8.2f, Renderer2D.Font.Regular, Theme.TEXT_FAINT);

        // Toggle pill
        float switchW = 26.0f;
        float switchH = 14.0f;
        float sx = x + w - switchW - 16.0f;
        float sy = y + 15.0f;

        Color off = Theme.withAlpha(Theme.TRACK, 60);
        Color on = Theme.ACCENT;
        r.roundRect(sx, sy, switchW, switchH, switchH * 0.5f, Color.lerp(off, on, tv));
        if (tv > 0.35f) {
            r.glow(sx, sy, switchW, switchH, switchH * 0.5f, 10.0f, Theme.withAlpha(Theme.ACCENT, (int) (60 * tv)));
        }
        float knobX = sx + 2.0f + (switchW - switchH) * tv;
        r.circle(knobX + switchH * 0.5f - 2.0f, sy + switchH * 0.5f, 4.4f,
            Color.lerp(Theme.TEXT_DIM, Theme.TEXT, tv));

        // Expand chevron
        float chevX = x + w - switchW - 44.0f;
        r.push();
        r.translate(chevX + 6.0f, y + 22.0f);
        r.rotate(90.0f * ev);
        Icons.chevron(r, -6.0f, -6.0f, 12.0f, Theme.withAlpha(Theme.TEXT_DIM, 200));
        r.pop();

        boolean hasSettings = !Modules.get().groups(module).isEmpty();

        addHotspot(x, y, w - (hasSettings ? 34.0f : 0.0f), CARD_HEIGHT, module::toggle);

        if (hasSettings) {
            addHotspot(x + w - 40.0f, y, 26.0f, CARD_HEIGHT, () -> {
                if (!expanded.remove(key)) expanded.add(key);
            });
        }

        if (ev > 0.01f) {
            drawSettings(r, module, x, y + CARD_HEIGHT, w, ev, mouseX, mouseY, delta);
        }
    }

    private final List<String> expanded = new ArrayList<>();

    private void drawSettings(Renderer2D r, Module module, float x, float y, float w, float visible,
                              int mouseX, int mouseY, float delta) {
        float height = settingsHeight(module);

        r.push();
        r.scissor(x, y, w, Math.max(0.0f, height * visible));
        r.alpha(Anim.clamp01(visible * 1.4f - 0.2f));

        r.roundRect(x + 1.0f, y - 2.0f, w - 2.0f, height * visible + 4.0f, 10.0f, Theme.withAlpha(Theme.SIDEBAR, 120));

        float cy = y + 6.0f;
        float rowX = x + 14.0f;
        float rowW = w - 28.0f;

        for (SettingGroup group : Modules.get().groups(module)) {
            if (Modules.get().groups(module).size() > 1) {
                r.text(group.name.toUpperCase(Locale.ROOT), rowX, cy + 6.0f, 7.2f, Renderer2D.Font.Bold,
                    Theme.withAlpha(Theme.ACCENT_LIGHT, 190));
                cy += 14.0f;
            }

            for (Setting<?> setting : group.settings) {
                if (!setting.isVisible()) continue;
                cy = drawSetting(r, setting, rowX, cy, rowW, mouseX, mouseY, delta);
                cy += 2.0f;
            }

            cy += 4.0f;
        }

        r.pop();
    }

    private float drawSetting(Renderer2D r, Setting<?> setting, float x, float y, float w,
                              int mouseX, int mouseY, float delta) {
        String label = setting.title();

        if (setting instanceof BoolSetting bool) {
            r.text(label, x, y + 7.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);

            float switchW = 22.0f;
            float switchH = 12.0f;
            float sx = x + w - switchW;
            float sy = y + 1.0f;
            Anim anim = hovers.computeIfAbsent("bool-" + label, k -> new Anim(bool.get() ? 1.0 : 0.0));
            anim.set(bool.get() ? 1.0 : 0.0);
            float v = anim.update(delta);

            r.roundRect(sx, sy, switchW, switchH, switchH * 0.5f,
                Color.lerp(Theme.withAlpha(Theme.TRACK, 60), Theme.ACCENT, v));
            r.circle(sx + 2.0f + (switchW - switchH) * v + switchH * 0.5f - 2.0f, sy + switchH * 0.5f, 3.8f,
                Color.lerp(Theme.TEXT_DIM, Theme.TEXT, v));

            addHotspot(x, y - 1.0f, w, 15.0f, bool::toggle);
            return y + 16.0f;
        }

        if (setting instanceof IntSetting intSetting) {
            float t = (float) (intSetting.get() - intSetting.min) / Math.max(1.0f, intSetting.max - intSetting.min);
            r.text(label, x, y + 6.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);
            r.textRight(String.valueOf(intSetting.get()), x + w, y + 6.0f, 8.6f, Renderer2D.Font.SemiBold, Theme.TEXT);

            drawSlider(r, x, y + 13.0f, w, t, mouseX, mouseY);
            addSlider(x, y + 10.0f, w, 12.0f, value -> {
                int range = intSetting.max - intSetting.min;
                intSetting.set(intSetting.min + (int) Math.round(value * range));
            });
            return y + 24.0f;
        }

        if (setting instanceof DoubleSetting doubleSetting) {
            double range = doubleSetting.max - doubleSetting.min;
            float t = (float) ((doubleSetting.get() - doubleSetting.min) / (range == 0.0 ? 1.0 : range));
            r.text(label, x, y + 6.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);
            r.textRight(String.format(Locale.ROOT, "%.2f", doubleSetting.get()), x + w, y + 6.0f, 8.6f,
                Renderer2D.Font.SemiBold, Theme.TEXT);

            drawSlider(r, x, y + 13.0f, w, t, mouseX, mouseY);
            addSlider(x, y + 10.0f, w, 12.0f, value -> {
                double r2 = doubleSetting.max - doubleSetting.min;
                doubleSetting.set(doubleSetting.min + value * r2);
            });
            return y + 24.0f;
        }

        if (setting instanceof EnumSetting<?> enumSetting) {
            r.text(label, x, y + 7.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);
            String value = enumValue(enumSetting);
            float chipW = r.textWidth(value, 8.2f, Renderer2D.Font.Medium) + 14.0f;

            r.roundRect(x + w - chipW, y + 0.5f, chipW, 14.0f, 7.0f, Theme.withAlpha(Theme.ACCENT, 26));
            r.textRight(value, x + w - 7.0f, y + 7.5f, 8.2f, Renderer2D.Font.Medium, Theme.ACCENT_LIGHT);

            addHotspot(x, y - 1.0f, w, 15.0f, () -> cycleEnum(enumSetting, false));
            return y + 17.0f;
        }

        if (setting instanceof ColorSetting colorSetting) {
            r.text(label, x, y + 6.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);

            float swatchSize = 11.0f;
            float gap = 4.0f;
            float total = PRESETS.length * swatchSize + (PRESETS.length - 1) * gap;
            float startX = x + w - total;

            for (int i = 0; i < PRESETS.length; i++) {
                Color preset = PRESETS[i];
                float sx = startX + i * (swatchSize + gap);
                boolean active = colorSetting.get().r == preset.r && colorSetting.get().g == preset.g
                    && colorSetting.get().b == preset.b;

                r.roundRect(sx, y + 1.0f, swatchSize, swatchSize, 3.5f, preset);
                if (active) {
                    r.roundRectStroke(sx - 1.5f, y - 0.5f, swatchSize + 3.0f, swatchSize + 3.0f, 4.5f, 1.2f, Theme.TEXT);
                }

                addHotspot(sx, y - 2.0f, swatchSize + gap, 16.0f, () -> colorSetting.get().set(preset.r, preset.g, preset.b, 255));
            }

            return y + 16.0f;
        }

        if (setting instanceof StringSetting stringSetting) {
            r.text(label, x, y + 7.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);
            r.textRight(stringSetting.get().isEmpty() ? "-" : stringSetting.get(), x + w, y + 7.0f, 8.4f,
                Renderer2D.Font.Medium, Theme.TEXT);
            return y + 16.0f;
        }

        r.text(label, x, y + 7.0f, 8.6f, Renderer2D.Font.Medium, Theme.TEXT_DIM);
        r.textRight(String.valueOf(setting.get()), x + w, y + 7.0f, 8.4f, Renderer2D.Font.Medium, Theme.TEXT_FAINT);
        return y + 16.0f;
    }

    private void drawSlider(Renderer2D r, float x, float y, float w, float t, int mouseX, int mouseY) {
        float trackH = 3.0f;
        r.roundRect(x, y, w, trackH, trackH * 0.5f, Theme.withAlpha(Theme.TRACK, 55));
        r.roundRectGradientH(x, y, Math.max(2.0f, w * t), trackH, trackH * 0.5f, Theme.ACCENT_DEEP, Theme.ACCENT_LIGHT);

        float knobX = x + w * t;
        r.circle(knobX, y + trackH * 0.5f, 4.2f, Theme.TEXT);
        r.circle(knobX, y + trackH * 0.5f, 2.0f, Theme.ACCENT);
    }

    private void addSlider(float x, float y, float w, float h, DoubleConsumer consumer) {
        Hotspot hotspot = new Hotspot(x, y, w, h, null, value -> consumer.accept(Anim.clamp01((float) value)));
        hotspots.add(hotspot);
    }

    private String enumValue(EnumSetting<?> setting) {
        return enumTitle(setting);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String enumTitle(EnumSetting setting) {
        Object value = setting.get();
        return value == null ? "-" : setting.title((Enum) value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void cycleEnum(EnumSetting setting, boolean backwards) {
        setting.cycle(backwards);
    }

    private float cardHeight(Module module) {
        if (!expanded.contains(module.name)) return CARD_HEIGHT;
        return CARD_HEIGHT + settingsHeight(module);
    }

    private float settingsHeight(Module module) {
        List<SettingGroup> groups = Modules.get().groups(module);
        float height = 12.0f;
        boolean showGroupNames = groups.size() > 1;

        for (SettingGroup group : groups) {
            if (showGroupNames) height += 14.0f;

            for (Setting<?> setting : group.settings) {
                if (!setting.isVisible()) continue;
                if (setting instanceof IntSetting || setting instanceof DoubleSetting) height += 26.0f;
                else if (setting instanceof BoolSetting || setting instanceof EnumSetting) height += 18.0f;
                else if (setting instanceof ColorSetting) height += 18.0f;
                else height += 18.0f;
            }

            height += 4.0f;
        }

        return height;
    }

    private List<Module> filtered() {
        List<Module> list = new ArrayList<>();

        for (Module module : Modules.get().get(selected)) {
            if (search.isEmpty()) {
                list.add(module);
                continue;
            }

            String needle = search.toLowerCase(Locale.ROOT);
            if (module.name.contains(needle) || module.description.toLowerCase(Locale.ROOT).contains(needle)) {
                list.add(module);
            }
        }

        if (!search.isEmpty()) {
            // Search is global, not just the open category.
            for (Module module : Modules.get().getAll()) {
                if (list.contains(module)) continue;
                String needle = search.toLowerCase(Locale.ROOT);
                if (module.name.contains(needle) || module.description.toLowerCase(Locale.ROOT).contains(needle)) {
                    list.add(module);
                }
            }
        }

        return list;
    }

    // ------------------------------------------------------------------ input

    private record Hotspot(float x, float y, float w, float h, Runnable click,
                           DoubleConsumer drag) {
        Hotspot(float x, float y, float w, float h, Runnable click) {
            this(x, y, w, h, click, null);
        }
    }

    private void addHotspot(float x, float y, float w, float h, Runnable click) {
        hotspots.add(new Hotspot(x, y, w, h, click, null));
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x();
        double my = click.y();

        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            for (int i = hotspots.size() - 1; i >= 0; i--) {
                Hotspot hotspot = hotspots.get(i);

                if (!inside(mx, my, hotspot.x, hotspot.y, hotspot.w, hotspot.h)) continue;

                if (hotspot.drag != null) {
                    dragging = hotspot;
                    dragStartX = hotspot.x;
                    dragging.drag.accept((mx - hotspot.x) / Math.max(1.0f, hotspot.w));
                } else if (hotspot.click != null) {
                    hotspot.click.run();
                }

                return true;
            }

            searchFocused = false;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (dragging != null && dragging.drag != null) {
            dragging.drag.accept((click.x() - dragging.x) / Math.max(1.0f, dragging.w));
            return true;
        }

        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging != null) {
            dragging = null;
            return true;
        }

        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scrollTarget -= vertical * 28.0f;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }

        if (searchFocused) {
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                return true;
            }

            if (event.key() == GLFW.GLFW_KEY_ENTER) {
                searchFocused = false;
                return true;
            }
        }

        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchFocused && event.isAllowedChatCharacter()) {
            search += event.codepointAsString();
            return true;
        }

        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        Modules.get().save();
        if (minecraft != null) minecraft.setScreen(null);
    }
}
