package dev.chicken.events;

/** Posted once before and after every client tick. */
public abstract class TickEvent {
    public static final class Pre extends TickEvent {
    }

    public static final class Post extends TickEvent {
    }
}
