package com.auracademic.backend.controller;

import com.auracademic.backend.model.ChatMessage;
import com.auracademic.backend.model.ChatRoom;
import com.auracademic.backend.model.User;
import com.auracademic.backend.repository.ChatMessageRepository;
import com.auracademic.backend.repository.ChatRoomRepository;
import com.auracademic.backend.repository.UserRepository;
import com.auracademic.backend.security.UserPrincipal;
import com.auracademic.backend.service.SettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SettingService settingService;

    /**
     * Lấy danh sách tất cả phòng chat đang hoạt động (Admin Only).
     * Trả về danh sách được xếp hạng theo thời gian nhận tin nhắn cuối cùng giảm dần.
     */
    @GetMapping("/rooms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ChatRoom>> getAllRooms() {
        return ResponseEntity.ok(roomRepository.findAllByOrderByLastMessageTimeDesc());
    }

    /**
     * Lấy lại toàn bộ lịch sử trò chuyện chi tiết của một phòng nhất định.
     * Tự động xác thực quyền truy cập: Người dùng chỉ được xem phòng của mình, Admin xem được tất cả.
     */
    @GetMapping("/history/{roomId}")
    public ResponseEntity<List<ChatMessage>> getChatHistory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String roomId) {

        // Kiểm định bảo mật vòng ngoài
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase("ROLE_ADMIN"));

        if (!isAdmin) {
            Optional<ChatRoom> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isPresent()) {
                ChatRoom room = roomOpt.get();
                // Chặn đứng trường hợp Student cố tình đọc lén lịch sử chat phòng khác qua PathVariable
                if (!room.getUserId().equals(principal.getId())) {
                    return ResponseEntity.status(403).build();
                }
            }
        }

        List<ChatMessage> messages = messageRepository.findByRoomIdOrderByTimestampAsc(roomId);
        return ResponseEntity.ok(messages);
    }

    /**
     * Trả về thông tin phòng chat hiện hành hoặc tự động khởi tạo phòng mới cho user đăng nhập.
     * Endpoint này giúp Student/Teacher lấy chính xác roomId duy nhất của mình để kết nối socket.
     */
    @GetMapping("/my-room")
    public ResponseEntity<ChatRoom> getOrCreateMyRoom(@AuthenticationPrincipal UserPrincipal principal) {
        String userId = principal.getId();
        Optional<ChatRoom> roomOpt = roomRepository.findByUserId(userId);

        if (roomOpt.isPresent()) {
            return ResponseEntity.ok(roomOpt.get());
        }

        // Trường hợp lần đầu tiên user tương tác với Chatbox -> Sinh thực thể phòng chat mới
        Optional<User> userOpt = userRepository.findById(userId);
        String fullName = "Thành viên Hệ thống";
        String role = "student";
        
        if (userOpt.isPresent()) {
            fullName = userOpt.get().getFullName();
            role = userOpt.get().getRole();
        }

        ChatRoom newRoom = new ChatRoom(userId, fullName, role);
        newRoom.setLastMessage("Chào mừng bạn đến với hệ thống hỗ trợ AuraAcademic.");
        newRoom.setLastMessageTime(LocalDateTime.now());
        
        return ResponseEntity.ok(roomRepository.save(newRoom));
    }

    /**
     * Đánh dấu toàn bộ tin nhắn trong phòng hiện tại đã được đọc (seen = true).
     * Thường kích hoạt khi Admin mở ô cửa sổ chat, giúp xóa bay nhãn badge thông báo đỏ.
     */
    @PostMapping("/read/{roomId}")
    public ResponseEntity<Map<String, Object>> markAsRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String roomId) {

        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase("ROLE_ADMIN"));

        // 1. Nếu là Admin đọc -> Reset UnreadCount của phòng về 0 ngay lập tức
        if (isAdmin) {
            Optional<ChatRoom> roomOpt = roomRepository.findById(roomId);
            if (roomOpt.isPresent()) {
                ChatRoom room = roomOpt.get();
                room.setUnreadCount(0);
                roomRepository.save(room);
            }
        }

        // 2. Đánh dấu trạng thái 'seen' cho toàn bộ tin nhắn mà user đối diện đã gửi
        List<ChatMessage> messages = messageRepository.findByRoomIdOrderByTimestampAsc(roomId);
        boolean hasChanged = false;
        for (ChatMessage msg : messages) {
            if (!msg.isSeen()) {
                // Kiểm tra người gửi có phải là người hiện tại không, nếu không phải -> Cập nhật đã đọc
                boolean isSelf = msg.getSenderId().equals(principal.getId());
                if (!isSelf) {
                    msg.setSeen(true);
                    messageRepository.save(msg);
                    hasChanged = true;
                }
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("updated", hasChanged);
        return ResponseEntity.ok(res);
    }

    /**
     * Lấy cấu hình cờ hoạt động của AI Trả lời tự động toàn cục.
     */
    @GetMapping("/ai/status")
    public ResponseEntity<Map<String, Object>> getAiStatus() {
        boolean isEnabled = settingService.getBoolean(SettingService.CHAT_AI_ENABLED, true);
        Map<String, Object> res = new HashMap<>();
        res.put("enabled", isEnabled);
        return ResponseEntity.ok(res);
    }

    /**
     * Đảo ngược hoặc cấu hình cụ thể trạng thái bật/tắt AI tự động (Admin Only).
     */
    @PostMapping("/ai/toggle")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> toggleAiStatus(@RequestBody Map<String, Boolean> payload) {
        Boolean targetState = payload.get("enabled");
        if (targetState == null) {
            boolean current = settingService.getBoolean(SettingService.CHAT_AI_ENABLED, true);
            targetState = !current;
        }

        settingService.saveSetting(SettingService.CHAT_AI_ENABLED, targetState.toString(), "Global AI Chat auto-respond toggle status");

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("enabled", targetState);
        return ResponseEntity.ok(res);
    }
}
