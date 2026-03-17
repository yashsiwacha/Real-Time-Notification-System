package com.epam.notifications.service;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
public class WebSocketPresenceListener {

    private final ConnectedUserRegistry connectedUserRegistry;

    public WebSocketPresenceListener(ConnectedUserRegistry connectedUserRegistry) {
        this.connectedUserRegistry = connectedUserRegistry;
    }

    @EventListener
    public void onConnect(SessionConnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String sessionId = accessor.getSessionId();
        if (user != null && sessionId != null) {
            connectedUserRegistry.markConnected(user.getName(), sessionId);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String sessionId = accessor.getSessionId();
        String resolvedUserId = user != null ? user.getName() : connectedUserRegistry.userBySession(sessionId);
        if (resolvedUserId != null && sessionId != null) {
            connectedUserRegistry.markDisconnected(resolvedUserId, sessionId);
        }
    }
}
