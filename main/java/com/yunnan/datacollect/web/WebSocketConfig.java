package com.yunnan.datacollect.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final UpdateWebSocketHandler updateWebSocketHandler;

    public WebSocketConfig(UpdateWebSocketHandler updateWebSocketHandler) {
        this.updateWebSocketHandler = updateWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(updateWebSocketHandler, "/ws/updates").setAllowedOrigins("*");
    }
}