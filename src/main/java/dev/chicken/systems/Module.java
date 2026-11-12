package dev.chicken.systems;

import dev.chicken.settings.Settings;
import dev.chicken.util.Chat;
import net.minecraft.client.Minecraft;

/** Base class of every Chicken Client module. */
public abstract class Module {
    protected static final Minecraft mc = Minecraft.getInstance();

    public final Category category;
    public final String name;
    public final String description;
    public final Settings settings = new Settings();

    private boolean active;

    protected Module(Category category, String name, String description) {
        this.category = category;
        this.name = name;
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    /** Called by the module manager; use {@code toggle()} instead of this. */
    void setActive(boolean state) {
        if (active == state) return;
        active = state;

        if (state) {
            onActivate();
        } else {
            onDeactivate();
        }
    }

    public void toggle() {
        Modules.get().setActive(this, !active);
    }

    public void enable() {
        Modules.get().setActive(this, true);
    }

    public void disable() {
        Modules.get().setActive(this, false);
    }

    protected void onActivate() {
    }

    protected void onDeactivate() {
    }

    /** Short text shown next to the module name in the HUD, or {@code null}. */
    public String getInfoString() {
        return null;
    }

    public String title() {
        StringBuilder sb = new StringBuilder();

        for (String part : name.split("-")) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return sb.toString();
    }

    protected void info(String format, Object... args) {
        Chat.info(format, args);
    }

    protected void warning(String format, Object... args) {
        Chat.warning(format, args);
    }

    protected void error(String format, Object... args) {
        Chat.error(format, args);
    }
}
