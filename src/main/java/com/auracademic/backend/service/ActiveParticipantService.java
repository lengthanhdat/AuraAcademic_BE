package com.auracademic.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ActiveParticipantService {

    /**
     * Lưu: examCode -> studentId -> [timestamp, status]
     * status: "LOBBY" (phòng chờ) hoặc "EXAM" (đang làm bài)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, long[]>> activeParticipants = new ConcurrentHashMap<>();
    // long[0] = timestamp, long[1] = 0 (LOBBY) hoặc 1 (EXAM)

    private static final long HEARTBEAT_TTL_MS = 45_000; // 45 giây
    private static final long STATUS_LOBBY = 0L;
    private static final long STATUS_EXAM  = 1L;

    @Autowired
    private ExamEventService examEventService;

    /**
     * Heartbeat từ phòng chờ (Lobby)
     */
    public void heartbeat(String examCode, String studentId) {
        heartbeat(examCode, studentId, "LOBBY");
    }

    /**
     * Heartbeat với trạng thái cụ thể: "LOBBY" hoặc "EXAM"
     */
    public void heartbeat(String examCode, String studentId, String status) {
        long statusCode = "EXAM".equalsIgnoreCase(status) ? STATUS_EXAM : STATUS_LOBBY;
        activeParticipants
            .computeIfAbsent(examCode.toUpperCase(), k -> new ConcurrentHashMap<>())
            .put(studentId, new long[]{ System.currentTimeMillis(), statusCode });
    }

    /**
     * Xóa học sinh khỏi danh sách active (nộp bài hoặc rời phòng)
     */
    public void removeParticipant(String examCode, String studentId) {
        ConcurrentHashMap<String, long[]> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants != null) {
            participants.remove(studentId);
        }
    }

    /**
     * Tổng số học sinh online (lobby + exam)
     */
    public long getActiveCount(String examCode) {
        ConcurrentHashMap<String, long[]> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants == null) return 0;
        return participants.size();
    }

    /**
     * Số học sinh đang trong phòng chờ (LOBBY)
     */
    public long getLobbyCount(String examCode) {
        ConcurrentHashMap<String, long[]> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants == null) return 0;
        return participants.values().stream().filter(v -> v[1] == STATUS_LOBBY).count();
    }

    /**
     * Số học sinh đang làm bài (EXAM)
     */
    public long getExamCount(String examCode) {
        ConcurrentHashMap<String, long[]> participants = activeParticipants.get(examCode.toUpperCase());
        if (participants == null) return 0;
        return participants.values().stream().filter(v -> v[1] == STATUS_EXAM).count();
    }

    /**
     * Dọn dẹp session không heartbeat sau TTL.
     * Chạy mỗi 15 giây, broadcast count mới qua SSE.
     */
    @Scheduled(fixedDelay = 15000)
    public void cleanupInactiveSessions() {
        long cutoff = System.currentTimeMillis() - HEARTBEAT_TTL_MS;

        for (Map.Entry<String, ConcurrentHashMap<String, long[]>> entry : activeParticipants.entrySet()) {
            String examCode = entry.getKey();
            ConcurrentHashMap<String, long[]> participants = entry.getValue();

            long sizeBefore = participants.size();
            participants.entrySet().removeIf(e -> e.getValue()[0] < cutoff);
            long sizeAfter = participants.size();

            if (sizeBefore != sizeAfter) {
                examEventService.broadcast(examCode, "count", Map.of(
                    "activeCount", sizeAfter,
                    "lobbyCount",  getLobbyCount(examCode),
                    "examCount",   getExamCount(examCode)
                ));
            }
        }
    }
}
