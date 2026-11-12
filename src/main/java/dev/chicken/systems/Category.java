package dev.chicken.systems;

/** A group of modules shown as one tab in the GUI. */
public final class Category {
    public final String name;
    public final String icon;
    public final String description;

    public Category(String name, String icon, String description) {
        this.name = name;
        this.icon = icon;
        this.description = description;
    }
}
