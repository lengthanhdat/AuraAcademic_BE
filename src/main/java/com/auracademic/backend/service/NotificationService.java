package com.auracademic.backend.service;

import com.auracademic.backend.model.Notification;
import com.auracademic.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Subscribe to real-time notification push (SSE)
     */
    public SseEmitter subscribe(String userId) {
        // Emitter timeout set to 1 hour (3600000ms)
        SseEmitter emitter = new SseEmitter(3600000L);
        
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Remove emitter on completion, timeout, or error
        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(e -> removeEmitter(userId, emitter));

        // Send initial connection establish message
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECT")
                    .data("Đã kết nối thành công kênh thông báo thời gian thực"));
        } catch (IOException e) {
            removeEmitter(userId, emitter);
        }

        return emitter;
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
    }

    /**
     * Get all notifications for a specific user (including personal and global ones)
     */
    public List<Notification> getNotificationsForUser(String userId) {
        return notificationRepository.findByUserIdOrUserIdOrderByCreatedAtDesc(userId, "ALL");
    }

    /**
     * Create, save, and broadcast a notification in real-time
     */
    public Notification createAndSend(String userId, String title, String content, String type) {
        Notification notification = new Notification(userId, title, content, type);
        Notification saved = notificationRepository.save(notification);

        // Broadcast real-time via SSE
        if ("ALL".equalsIgnoreCase(userId)) {
            // Broadcast to all active users globally
            emitters.forEach((uid, userEmitters) -> {
                sendToEmitters(userEmitters, saved);
            });
        } else {
            // Broadcast to specific user
            List<SseEmitter> userEmitters = emitters.get(userId);
            if (userEmitters != null) {
                sendToEmitters(userEmitters, saved);
            }
        }

        return saved;
    }

    private void sendToEmitters(List<SseEmitter> userEmitters, Notification notification) {
        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : userEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("NOTIFICATION")
                        .data(notification));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }
        userEmitters.removeAll(deadEmitters);
    }

    /**
     * Mark a notification as read
     */
    public Notification markAsRead(String id, String userId) {
        Notification notification = notificationRepository.findById(id).orElse(null);
        if (notification == null) return null;

        if ("ALL".equalsIgnoreCase(notification.getUserId())) {
            // Global notification: add user to readBy list
            if (!notification.getReadBy().contains(userId)) {
                notification.getReadBy().add(userId);
                notificationRepository.save(notification);
            }
        } else {
            // Personal notification: verify owner and mark read
            if (userId.equals(notification.getUserId())) {
                notification.setRead(true);
                notificationRepository.save(notification);
            }
        }
        return notification;
    }

    /**
     * Mark all notifications as read for a user
     */
    public void markAllAsRead(String userId) {
        List<Notification> notifications = getNotificationsForUser(userId);
        for (Notification notification : notifications) {
            if ("ALL".equalsIgnoreCase(notification.getUserId())) {
                if (!notification.getReadBy().contains(userId)) {
                    notification.getReadBy().add(userId);
                }
            } else {
                notification.setRead(true);
            }
        }
        notificationRepository.saveAll(notifications);
    }

    /**
     * Delete a notification
     */
    public boolean deleteNotification(String id, String userId, boolean isAdmin) {
        Notification notification = notificationRepository.findById(id).orElse(null);
        if (notification == null) return false;

        // Security check: only allow deletion if the user is owner or is Admin
        if (isAdmin || userId.equals(notification.getUserId())) {
            notificationRepository.delete(notification);
            return true;
        }
        return false;
    }
}
