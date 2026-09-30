package com.yizhaoqi.docmind.config;

import com.yizhaoqi.docmind.handler.ChatWebSocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer { //注册 WebSocket 端点， 绑定WebSocket处理器

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/chat/{token}")
                .setAllowedOrigins("*"); // 允许所有来源访问，生产环境应该限制
    }
}
