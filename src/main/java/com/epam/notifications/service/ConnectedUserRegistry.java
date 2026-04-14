package com.epam.notifications.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ConnectedUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectedUserRegistry.class);

    private final StringRedisTemplate redisTemplate;
    private final String onlineUsersKey;
    private final String userSessionsPrefix;
    private final String sessionToUserPrefix;
    private final ConcurrentMap<String, Set<String>> localUserSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> localSessionToUser = new ConcurrentHashMap<>();

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

        localUserSessions.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);
        localSessionToUser.put(sessionId, userId);

        try {
            redisTemplate.opsForSet().add(userSessionsKey(userId), sessionId);
            redisTemplate.opsForSet().add(onlineUsersKey, userId);
            redisTemplate.opsForValue().set(sessionToUserPrefix + sessionId, userId);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable for markConnected, using local presence fallback: {}", exception.getMessage());
        }
    }

    public void markDisconnected(String userId) {
        if (isBlank(userId)) {
            return;
        }

        Set<String> sessions = localUserSessions.remove(userId);
        if (sessions != null) {
            sessions.forEach(localSessionToUser::remove);
        }

        try {
            redisTemplate.delete(userSessionsKey(userId));
            redisTemplate.opsForSet().remove(onlineUsersKey, userId);
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable for markDisconnected, using local presence fallback: {}", exception.getMessage());
        }
    }

    public void markDisconnected(String userId, String sessionId) {
        if (isBlank(userId) || isBlank(sessionId)) {
            return;
        }

        Set<String> sessions = localUserSessions.get(userId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                localUserSessions.remove(userId);
            }
        }
        localSessionToUser.remove(sessionId);

        try {
            redisTemplate.opsForSet().remove(userSessionsKey(userId), sessionId);
            redisTemplate.delete(sessionToUserPrefix + sessionId);

            Long activeSessions = redisTemplate.opsForSet().size(userSessionsKey(userId));
            if (activeSessions == null || activeSessions == 0) {
                redisTemplate.delete(userSessionsKey(userId));
                redisTemplate.opsForSet().remove(onlineUsersKey, userId);
            }
        } catch (RuntimeException exception) {
            log.warn("Redis unavailable for markDisconnected(session), using local presence fallback: {}", exception.getMessage());
        }
    }

    public boolean isConnected(String userId) {
        if (isBlank(userId)) {
            return false;
        }

        try {
            Long activeSessions = redisTemplate.opsForSet().size(userSessionsKey(userId));
            return activeSessions != null && activeSessions > 0;
        } catch (RuntimeException exception) {
            Set<String> localSessions = localUserSessions.get(userId);
            return localSessions != null && !localSessions.isEmpty();
        }
    }

    public int connectedCount() {
        try {
            Long size = redisTemplate.opsForSet().size(onlineUsersKey);
            return size == null ? 0 : Math.toIntExact(size);
        } catch (RuntimeException exception) {
            return localUserSessions.size();
        }
    }

    public String userBySession(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }

        try {
            return redisTemplate.opsForValue().get(sessionToUserPrefix + sessionId);
        } catch (RuntimeException exception) {
            return localSessionToUser.get(sessionId);
        }
    }

    private String userSessionsKey(String userId) {
        return userSessionsPrefix + userId;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || Objects.equals(value, "null");
    }
}
