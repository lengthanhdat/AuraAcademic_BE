package com.auracademic.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Kích hoạt in-memory Message Broker để chuyển tiếp tin nhắn
        // /topic: Dùng cho các kênh broadcast rộng rãi (ví dụ: cập nhật trạng thái)
        // /queue: Dùng cho luồng chat trực tiếp riêng tư (Point-to-Point)
        config.enableSimpleBroker("/topic", "/queue");

        // Chỉ định tiền tố dẫn hướng cho tin nhắn từ Client gửi lên Server xử lý
        // Mọi tin gửi lên endpoint @MessageMapping sẽ có tiền tố là /app
        config.setApplicationDestinationPrefixes("/app");

        // Quy định tiền tố dành cho việc định tuyến trực tiếp tới User cụ thể
        // Ví dụ: Khi Server gửi tin cho Client thông qua convertAndSendToUser()
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Đăng ký cổng WebSocket chính (/ws)
        // setAllowedOriginPatterns("*") cho phép Frontend kết nối từ bất kỳ domain nào
        // withSockJS() bật tính năng dự phòng mượt mà cho trình duyệt cũ không hỗ trợ WS
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
