package com.auracademic.backend.service;

import com.auracademic.backend.model.ChatMessage;
import com.auracademic.backend.model.ChatRoom;
import com.auracademic.backend.repository.ChatMessageRepository;
import com.auracademic.backend.repository.ChatRoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private GroqService groqService;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Xử lý sinh phản hồi AI bất đồng bộ (@Async).
     * Chạy trên một thread phụ để giải phóng Websocket thread lập tức cho Client.
     */
    @Async
    public void processAiResponseAsync(String userMessage, String userName, String roomId) {
        try {
            log.info("[AiChatService Async] Đang khởi tạo luồng xử lý AI cho phòng {}", roomId);
            
            // Tạo độ trễ tự nhiên 1.5 giây giả lập thao tác "Trợ lý AI đang gõ..."
            Thread.sleep(1500);
            
            String aiAnswer = getAiResponse(userMessage, userName);
            
            ChatMessage aiMsg = new ChatMessage(roomId, "ai", "Trợ lý AI", "ai", aiAnswer);
            aiMsg.setSeen(true);
            ChatMessage savedAiMsg = messageRepository.save(aiMsg);
            
            // Cập nhật thông tin cuối cùng của phòng chat
            roomRepository.findById(roomId).ifPresent(r -> {
                r.setLastMessage(aiAnswer);
                r.setLastMessageTime(LocalDateTime.now());
                roomRepository.save(r);
            });
            
            // Phát tin nhắn AI đã sinh lên Topic phòng chat
            messagingTemplate.convertAndSend("/topic/chat/" + roomId, savedAiMsg);
            
            // Kích hoạt cập nhật hàng đợi phòng chat cho Admin Dashboard
            messagingTemplate.convertAndSend("/topic/rooms", roomRepository.findAllByOrderByLastMessageTimeDesc());
            
            log.info("[AiChatService Async] Hoàn thành phát tin nhắn phản hồi AI thành công.");
        } catch (Exception ex) {
            log.error("[AiChatService Async] Lỗi nghiêm trọng khi phát tán tin nhắn AI: {}", ex.getMessage());
        }
    }

    /**
     * Nhận tin nhắn từ người dùng, xây dựng context prompt học thuật và gọi LLM sinh phản hồi.
     * Sử dụng chiến lược Ưu tiên Gemini 2.5 Flash ➡️ Tự động Fallback sang Groq nếu bị lỗi/hết quota.
     */
    public String getAiResponse(String userMessage, String userName) {
        String prompt = buildChatPrompt(userMessage, userName);
        try {
            log.info("[AiChatService] Đang yêu cầu phản hồi AI cho người dùng: {}", userName);
            return geminiService.generateChatResponse(prompt);
        } catch (Exception ex) {
            log.warn("[AiChatService] Gemini chính gặp sự cố, tiến hành fallback sang Groq. Chi tiết: {}", ex.getMessage());
            try {
                return groqService.generateChatResponse(prompt);
            } catch (Exception e) {
                log.error("[AiChatService] Cả 2 hệ thống AI đều tê liệt! Lỗi Groq: {}", e.getMessage());
                return "Trợ lý AI của AuraAcademic tạm thời bận hoặc gặp sự cố đường truyền. Ban quản trị (Admin) đã được thông báo và sẽ trực tiếp trả lời bạn sớm nhất có thể. Cảm ơn bạn!";
            }
        }
    }

    private String buildChatPrompt(String message, String userName) {
        return String.format(
            "BỐI CẢNH HỆ THỐNG:\n" +
            "Bạn là 'Trợ lý Học thuật AI' tích hợp chính thức của nền tảng giáo dục AuraAcademic. " +
            "Bạn đang đại diện cho Ban quản trị (Admin) để hỗ trợ người dùng thời gian thực qua Chatbox.\n\n" +
            "THÔNG TIN NGƯỜI DÙNG ĐANG CHAT:\n" +
            "- Tên: %s\n\n" +
            "QUY TẮC ỨNG XỬ:\n" +
            "1. LUÔN XƯNG HÔ LỄ PHÉP, LỊCH SỰ, mang tính giáo dục và chuyên nghiệp cao (Ví dụ: xưng 'Trợ lý AI', gọi người dùng bằng 'bạn' hoặc '%s').\n" +
            "2. Trả lời RÕ RÀNG, đúng trọng tâm và mang tính xây dựng.\n" +
            "3. KHUYẾN KHÍCH sử dụng định dạng Markdown (in đậm, danh sách gạch đầu dòng, bảng biểu đơn giản) để câu trả lời dễ đọc.\n" +
            "4. Nếu là câu hỏi kỹ thuật/học tập, hãy trả lời cặn kẽ. Nếu câu hỏi vượt quá khả năng, hãy khuyên người dùng đợi Admin vào trả lời thủ công.\n" +
            "5. Tuyệt đối NGHIÊM CẤM ngôn ngữ tục tĩu, bạo lực, phân biệt vùng miền, chính trị hoặc các chủ đề nhạy cảm.\n\n" +
            "NỘI DUNG CÂU HỎI:\n" +
            "\"%s\"\n\n" +
            "HÃY PHẢN HỒI NGAY:",
            userName, userName, message
        );
    }
}
