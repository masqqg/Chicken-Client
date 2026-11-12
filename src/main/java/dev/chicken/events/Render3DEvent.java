package dev.chicken.events;

import dev.chicken.render.Renderer3D;

/** Posted once per frame while the world is drawn. */
public final class Render3DEvent {
    public final Renderer3D renderer;

    public Render3DEvent(Renderer3D renderer) {
        this.renderer = renderer;
    }
}
