package com.auracademic.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveParticipantService {

    // examCode -> Map<studentId, lastHeartbeatTimestamp>
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> activeParticipants = new ConcurrentHashMap<>();

    // TTL: học sinh không gửi heartbeat trong 30 giây → coi như đã thoát
    private static final long HEARTBEAT_TTL_MS = 30_000;

    @Autowired
    private ExamEventService examEventService;

    /**
     * Ghi nhận học sinh đang trong phòng chờ / làm bài (gọi khi heartbeat)
     */
    public void heartbeat(String examCode, String studentId) {
        activeParticipants
            .computeIfAbsent(examCode.toUpperCase(), k -> new ConcurrentHashMap<>())
            .put(studentId, System.currentTimeMillis());
    }

    /**
     * Xóa học sinh khỏi danh sách active (gọi khi nộp bài hoặc rời phòng)
     */
    public void removeParticipant(String examCode, String studentId) {
        ConcurrentHashMap<String, Long> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants != null) {
            participants.remove(studentId);
        }
    }

    /**
     * Đếm số học sinh đang chờ / làm bài
     */
    public long getActiveCount(String examCode) {
        ConcurrentHashMap<String, Long> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants == null) return 0;
        return participants.size();
    }

    /**
     * Tự động dọn dẹp các session không heartbeat sau 30 giây.
     * Chạy mỗi 15 giây — đủ nhanh để cập nhật realtime khi học sinh thoát.
     * Sau khi dọn, broadcast count mới qua SSE cho giáo viên.
     */
    @Scheduled(fixedDelay = 15000)
    public void cleanupInactiveSessions() {
        long cutoff = System.currentTimeMillis() - HEARTBEAT_TTL_MS;

        for (Map.Entry<String, ConcurrentHashMap<String, Long>> entry : activeParticipants.entrySet()) {
            String examCode = entry.getKey();
            ConcurrentHashMap<String, Long> participants = entry.getValue();

            long sizeBefore = participants.size();
            participants.entrySet().removeIf(e -> e.getValue() < cutoff);
            long sizeAfter = participants.size();

            // Chỉ broadcast nếu count thực sự thay đổi (tránh spam SSE)
            if (sizeBefore != sizeAfter) {
                examEventService.broadcast(examCode, "count", Map.of("activeCount", sizeAfter));
            }
        }
    }
}
