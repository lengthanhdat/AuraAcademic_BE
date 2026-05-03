package com.auracademic.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ExamEventService {

    // examCode -> list of SSE emitters (mỗi client 1 emitter)
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    /**
     * Tạo kết nối SSE mới cho một client (giáo viên hoặc học sinh)
     */
    public SseEmitter subscribe(String examCode) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        String key = examCode.toUpperCase();

        emitters.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> removeEmitter(key, emitter));
        emitter.onError(e -> removeEmitter(key, emitter));

        // Gửi ping ngay để xác nhận kết nối thành công
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ok\":true}"));
        } catch (IOException e) {
            removeEmitter(key, emitter);
        }

        return emitter;
    }

    /**
     * Broadcast một sự kiện đến tất cả client đang theo dõi phòng này
     */
    public void broadcast(String examCode, String eventName, Object data) {
        String key = examCode.toUpperCase();
        List<SseEmitter> roomEmitters = emitters.get(key);
        if (roomEmitters == null || roomEmitters.isEmpty()) return;

        // Dùng removeIf để vừa gửi vừa dọn emitter chết
        roomEmitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
                return false; // giữ lại
            } catch (IOException e) {
                return true; // xóa emitter lỗi
            }
        });
    }

    private void removeEmitter(String examCode, SseEmitter emitter) {
        List<SseEmitter> roomEmitters = emitters.get(examCode);
        if (roomEmitters != null) roomEmitters.remove(emitter);
    }
}
