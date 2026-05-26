package com.auracademic.backend.controller;

import com.auracademic.backend.model.ChatMessage;
import com.auracademic.backend.model.ChatRoom;
import com.auracademic.backend.repository.ChatMessageRepository;
import com.auracademic.backend.repository.ChatRoomRepository;
import com.auracademic.backend.service.AiChatService;
import com.auracademic.backend.service.SettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.time.LocalDateTime;
import java.util.Optional;

@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private SettingService settingService;

    @Autowired
    private AiChatService aiChatService;

    /**
     * Xử lý tiếp nhận và chuyển phát tin nhắn thời gian thực từ Client qua WebSocket.
     * Endpoint nhận tin nhắn: /app/chat.send
     */
    @MessageMapping("/chat.send")
    public void handleMessage(ChatMessage msg) {
        log.info("[WebSocket] Tin nhắn mới nhận từ {} (Vai trò: {}): {}", msg.getSenderName(), msg.getSenderRole(), msg.getContent());

        // 1. Đảm bảo tin nhắn có Id phòng chat hợp lệ (tìm hoặc khởi tạo tự động nếu client chưa truyền)
        String currentRoomId = msg.getRoomId();
        if (currentRoomId == null || currentRoomId.isBlank()) {
            // Tìm phòng dựa trên UserID người gửi (mỗi user student/teacher có một phòng 1-1 duy nhất với admin)
            String searchUserId = "admin".equalsIgnoreCase(msg.getSenderRole()) ? "unknown_admin" : msg.getSenderId();
            Optional<ChatRoom> roomOpt = roomRepository.findByUserId(searchUserId);
            ChatRoom room = roomOpt.orElseGet(() -> {
                ChatRoom newRoom = new ChatRoom(searchUserId, msg.getSenderName(), msg.getSenderRole());
                return roomRepository.save(newRoom);
            });
            currentRoomId = room.getId();
            msg.setRoomId(currentRoomId);
        }

        // Đặt nhãn thời gian thực tế của hệ thống
        msg.setTimestamp(LocalDateTime.now());
        msg.setSeen("admin".equalsIgnoreCase(msg.getSenderRole())); // Admin gửi coi như seen, Học sinh gửi cần Admin vào check

        // 2. Ghi vết lịch sử vào MongoDB
        ChatMessage savedMsg = messageRepository.save(msg);

        // 3. Đồng bộ hóa thông tin nhanh (preview) lên ChatRoom để tối ưu hiển thị danh sách
        Optional<ChatRoom> roomOpt = roomRepository.findById(currentRoomId);
        if (roomOpt.isPresent()) {
            ChatRoom room = roomOpt.get();
            room.setLastMessage(msg.getContent());
            room.setLastMessageTime(LocalDateTime.now());
            if (!"admin".equalsIgnoreCase(msg.getSenderRole())) {
                // Tăng số tin nhắn chưa đọc lên nếu đó là tin từ học sinh/giáo viên gửi đến Admin
                room.setUnreadCount(room.getUnreadCount() + 1);
            } else {
                // Nếu là Admin chủ động gửi, hãy xóa toàn bộ cờ chưa đọc của phòng này
                room.setUnreadCount(0);
            }
            roomRepository.save(room);
        }

        // 4. Kích hoạt Broadcast thời gian thực (Publish-Subscribe)
        // - Broadcast đến phòng cụ thể: client subscribe /topic/chat/{roomId} để nhận ngay
        messagingTemplate.convertAndSend("/topic/chat/" + currentRoomId, savedMsg);

        // - Gửi tín hiệu cập nhật sắp xếp danh sách phòng chat đến Admin Dashboard: client subscribe /topic/rooms
        messagingTemplate.convertAndSend("/topic/rooms", roomRepository.findAllByOrderByLastMessageTimeDesc());

        // 5. Cơ chế Chặn bắt AI (AI Interception Agent)
        // - Điều kiện kích hoạt: Người gửi KHÔNG phải Admin & Cờ cấu hình chat_ai_enabled = true
        boolean isAiEnabled = settingService.getBoolean(SettingService.CHAT_AI_ENABLED, true);
        if (!"admin".equalsIgnoreCase(msg.getSenderRole()) && isAiEnabled) {
            // Kích hoạt luồng Async bất đồng bộ đã dựng sẵn để sinh phản hồi AI mà không block Main-Thread
            aiChatService.processAiResponseAsync(msg.getContent(), msg.getSenderName(), currentRoomId);
        }
    }

    @Autowired
    private com.auracademic.backend.repository.ClassroomMessageRepository classroomMessageRepository;

    /**
     * Xử lý WebSocket group chat của Lớp học
     */
    @MessageMapping("/classroom.send")
    public void handleClassroomMessage(com.auracademic.backend.model.ClassroomMessage msg) {
        if (msg.getClassroomId() == null) return;
        msg.setTimestamp(LocalDateTime.now());
        com.auracademic.backend.model.ClassroomMessage saved = classroomMessageRepository.save(msg);
        messagingTemplate.convertAndSend("/topic/classroom/" + msg.getClassroomId(), saved);
    }
}
