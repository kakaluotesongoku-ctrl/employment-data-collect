package com.yunnan.datacollect.web;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class SystemEventBroadcaster {

    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, ClientContext> contexts = new ConcurrentHashMap<>();

    public SystemEventBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session, ClientContext context) {
        sessions.add(session);
        contexts.put(session.getId(), context);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
        contexts.remove(session.getId());
    }

    public void broadcast(String type, Object payload) {
        broadcast(type, payload, EventAudience.all());
    }

    public void broadcast(String type, Object payload, EventAudience audience) {
        String message;
        try {
            message = objectMapper.writeValueAsString(java.util.Map.of(
                    "type", type,
                    "payload", payload));
        } catch (IOException ex) {
            return;
        }
        TextMessage textMessage = new TextMessage(message);
        for (WebSocketSession session : sessions) {
            try {
                ClientContext context = contexts.get(session.getId());
                if (session.isOpen() && context != null && audience.matches(context)) {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                }
            } catch (IOException ex) {
                unregister(session);
            }
        }
    }

    public record ClientContext(long userId, String role, String cityName, Long enterpriseId) {
    }

    public record EventAudience(Set<String> allowedRoles, Set<String> cityNames, Set<Long> enterpriseIds) {
        public static EventAudience all() {
            return new EventAudience(Set.of(), Set.of(), Set.of());
        }

        public static EventAudience provinceOnly() {
            return new EventAudience(Set.of("PROVINCE"), Set.of(), Set.of());
        }

        public static EventAudience cityAndEnterprise(String cityName, Long enterpriseId) {
            Set<String> cities = cityName == null || cityName.isBlank() ? Set.of() : Set.of(cityName);
            return new EventAudience(Set.of(), normalizeCities(cities), enterpriseId == null ? Set.of() : Set.of(enterpriseId));
        }

        public static EventAudience cities(Set<String> cityNames) {
            return new EventAudience(Set.of(), normalizeCities(cityNames), Set.of());
        }

        private static Set<String> normalizeCities(Set<String> cityNames) {
            if (cityNames == null || cityNames.isEmpty()) {
                return Set.of();
            }
            return cityNames.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
        }

        public boolean matches(ClientContext context) {
            if (context == null || context.role() == null) {
                return false;
            }
            String role = context.role().toUpperCase(Locale.ROOT);
            if (!allowedRoles.isEmpty() && !allowedRoles.contains(role)) {
                return false;
            }
            if ("PROVINCE".equals(role)) {
                return true;
            }
            if ("CITY".equals(role)) {
                if (cityNames.isEmpty() && enterpriseIds.isEmpty()) {
                    return true;
                }
                if (cityNames.isEmpty()) {
                    return false;
                }
                return context.cityName() != null && cityNames.contains(context.cityName().trim().toLowerCase(Locale.ROOT));
            }
            if ("ENTERPRISE".equals(role)) {
                if (cityNames.isEmpty() && enterpriseIds.isEmpty()) {
                    return true;
                }
                if (!enterpriseIds.isEmpty()) {
                    return context.enterpriseId() != null && enterpriseIds.contains(context.enterpriseId());
                }
                if (!cityNames.isEmpty()) {
                    return context.cityName() != null && cityNames.contains(context.cityName().trim().toLowerCase(Locale.ROOT));
                }
                return false;
            }
            return false;
        }
    }
}