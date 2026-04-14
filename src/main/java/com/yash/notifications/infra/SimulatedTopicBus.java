package com.yash.notifications.infra;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class SimulatedTopicBus {

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    public <T> void publish(String topic, T event) {
        List<Consumer<Object>> handlers = subscribers.getOrDefault(topic, List.of());
        for (Consumer<Object> handler : handlers) {
            try {
                handler.accept(event);
            } catch (Exception exception) {
                // A single consumer failure should not break the event bus.
                System.err.printf("[topic:%s] consumer failed: %s%n", topic, exception.getMessage());
            }
        }
    }

    public <T> void subscribe(String topic, Class<T> payloadType, Consumer<T> handler) {
        subscribers
                .computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>())
                .add(event -> {
                    if (payloadType.isInstance(event)) {
                        handler.accept(payloadType.cast(event));
                    }
                });
    }
}
