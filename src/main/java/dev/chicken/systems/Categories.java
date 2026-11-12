package dev.chicken.systems;

/** The categories every module is filed under. */
public final class Categories {
    public static final Category WORLD = new Category("World", "globe", "Scanners and world information");
    public static final Category RENDER = new Category("Render", "layers", "ESP and visual overrides");
    public static final Category VISUALS = new Category("Visuals", "knife", "Cosmetic visuals");

    private Categories() {
    }
}
