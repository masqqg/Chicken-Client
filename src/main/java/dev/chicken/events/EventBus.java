package dev.chicken.events;

import dev.chicken.ChickenClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's own event bus. Listeners are methods annotated with
 * {@link EventHandler} that take exactly one event argument.
 */
public final class EventBus {
    private final Map<Class<?>, List<Listener>> listeners = new HashMap<>();

    public void subscribe(Object target) {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(EventHandler.class)) continue;
            if (method.getParameterCount() != 1) {
                ChickenClient.LOG.warn("Ignoring {}.{}(): event handlers take one argument",
                    target.getClass().getSimpleName(), method.getName());
                continue;
            }

            method.setAccessible(true);
            Class<?> type = method.getParameterTypes()[0];
            listeners.computeIfAbsent(type, k -> new ArrayList<>()).add(new Listener(target, method));
        }
    }

    public void unsubscribe(Object target) {
        for (List<Listener> list : listeners.values()) {
            list.removeIf(listener -> listener.target == target);
        }
    }

    public void post(Object event) {
        // Exact class first, then superclasses so a TickEvent listener also sees TickEvent.Post.
        for (Class<?> type = event.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            List<Listener> list = listeners.get(type);
            if (list == null) continue;

            for (Listener listener : list.toArray(new Listener[0])) {
                listener.invoke(event);
            }
        }
    }

    private record Listener(Object target, Method method) {
        void invoke(Object event) {
            try {
                method.invoke(target, event);
            } catch (Throwable t) {
                ChickenClient.LOG.error("Event handler {}.{} failed for {}",
                    target.getClass().getSimpleName(), method.getName(), event.getClass().getSimpleName(), t);
            }
        }
    }
}
