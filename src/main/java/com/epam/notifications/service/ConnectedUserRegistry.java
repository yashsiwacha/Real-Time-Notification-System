package com.epam.notifications.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ConnectedUserRegistry {

    private final StringRedisTemplate redisTemplate;
    private final String onlineUsersKey;
    private final String userSessionsPrefix;
    private final String sessionToUserPrefix;

    public ConnectedUserRegistry(StringRedisTemplate redisTemplate,
                                 @Value("${notification.queue.key-prefix:notification}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.onlineUsersKey = keyPrefix + ":presence:users";
        this.userSessionsPrefix = keyPrefix + ":presence:user-sessions:";
        this.sessionToUserPrefix = keyPrefix + ":presence:session-user:";
    }

    public void markConnected(String userId) {
        // Backward-compatible fallback for legacy call-sites.
        markConnected(userId, "legacy-session");
    }

    public void markConnected(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) {
            return;
        }
        redisTemplate.opsForSet().add(userSessionsKey(userId), sessionId);
        redisTemplate.opsForSet().add(onlineUsersKey, userId);
        redisTemplate.opsForValue().set(sessionToUserPrefix + sessionId, userId);
    }

    public void markDisconnected(String userId) {
        if (isBlank(userId)) {
            return;
        }
        redisTemplate.delete(userSessionsKey(userId));
        redisTemplate.opsForSet().remove(onlineUsersKey, userId);
    }

    public void markDisconnected(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) {
            return;
        }

        redisTemplate.opsForSet().remove(userSessionsKey(userId), sessionId);
        redisTemplate.delete(sessionToUserPrefix + sessionId);

        Long activeSessions = redisTemplate.opsForSet().size(userSessionsKey(userId));
        if (activeSessions == null || activeSessions == 0) {
            redisTemplate.delete(userSessionsKey(userId));
            redisTemplate.opsForSet().remove(onlineUsersKey, userId);
        }
    }

    public boolean isConnected(String userId) {
        if (isBlank(userId)) {
            return false;
        }
        Long activeSessions = redisTemplate.opsForSet().size(userSessionsKey(userId));
        return activeSessions != null && activeSessions > 0;
    }

    public int connectedCount() {
        Long size = redisTemplate.opsForSet().size(onlineUsersKey);
        return size == null ? 0 : Math.toIntExact(size);
    }

    public String userBySession(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        return redisTemplate.opsForValue().get(sessionToUserPrefix + sessionId);
    }

    private String userSessionsKey(String userId) {
        return userSessionsPrefix + userId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || Objects.equals(value, "null");
    }
}
