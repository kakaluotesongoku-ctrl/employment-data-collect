package com.yunnan.datacollect.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.yunnan.datacollect.service.PlatformService;

@Component
public class UpdateWebSocketHandler extends TextWebSocketHandler {

    private final PlatformService platformService;
    private final SystemEventBroadcaster broadcaster;

    public UpdateWebSocketHandler(PlatformService platformService, SystemEventBroadcaster broadcaster) {
        this.platformService = platformService;
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session.getUri());
        try {
            platformService.currentUser(token);
        } catch (RuntimeException ex) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid token"));
            throw ex;
        }
        broadcaster.register(session);
        session.sendMessage(new TextMessage("{\"type\":\"connected\",\"payload\":{\"message\":\"WebSocket connected\"}}"));
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String token = extractToken(session.getUri());
        platformService.currentUser(token);
        session.sendMessage(new TextMessage("{\"type\":\"echo\",\"payload\":{\"message\":\"" + escape(message.getPayload()) + "\"}}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }

    private String extractToken(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        for (String part : uri.getQuery().split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && "token".equals(pair[0])) {
                return pair[1];
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }

    private String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}