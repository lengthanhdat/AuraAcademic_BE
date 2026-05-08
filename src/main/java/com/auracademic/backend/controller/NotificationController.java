package com.auracademic.backend.controller;

import com.auracademic.backend.model.Notification;
import com.auracademic.backend.security.UserPrincipal;
import com.auracademic.backend.service.NotificationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Subscribe to real-time notification push (SSE stream)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@AuthenticationPrincipal UserPrincipal principal) {
        return notificationService.subscribe(principal.getId());
    }

    /**
     * Get paginated, filtered, and searched notifications list for the logged-in user
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String readState, // "read", "unread", "all"
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int limit) {

        String userId = principal.getId();
        List<Notification> rawList = notificationService.getNotificationsForUser(userId);

        // Apply filters in-memory for maximum simplicity and high performance
        List<Notification> filteredList = rawList.stream()
                .filter(n -> {
                    // Filter by type
                    if (type != null && !type.isBlank() && !type.equalsIgnoreCase("all")) {
                        return type.equalsIgnoreCase(n.getType());
                    }
                    return true;
                })
                .filter(n -> {
                    // Filter by read/unread state
                    boolean isRead = "ALL".equalsIgnoreCase(n.getUserId()) 
                            ? n.getReadBy().contains(userId) 
                            : n.isRead();

                    if ("read".equalsIgnoreCase(readState)) {
                        return isRead;
                    } else if ("unread".equalsIgnoreCase(readState)) {
                        return !isRead;
                    }
                    return true;
                })
                .filter(n -> {
                    // Filter by search query
                    if (query != null && !query.isBlank()) {
                        String q = query.toLowerCase();
                        return (n.getTitle() != null && n.getTitle().toLowerCase().contains(q)) ||
                               (n.getContent() != null && n.getContent().toLowerCase().contains(q));
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // Paginate results
        int totalItems = filteredList.size();
        int fromIndex = Math.min(page * limit, totalItems);
        int toIndex = Math.min(fromIndex + limit, totalItems);
        List<Notification> paginatedList = filteredList.subList(fromIndex, toIndex);

        // Map with isRead helper calculated for the client
        List<Map<String, Object>> items = paginatedList.stream().map(n -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", n.getId());
            map.put("userId", n.getUserId());
            map.put("title", n.getTitle());
            map.put("content", n.getContent());
            map.put("type", n.getType());
            map.put("createdAt", n.getCreatedAt());
            map.put("read", "ALL".equalsIgnoreCase(n.getUserId()) 
                    ? n.getReadBy().contains(userId) 
                    : n.isRead());
            return map;
        }).collect(Collectors.toList());

        // Calculate unread count globally for un-read personal and un-read global notifications
        long unreadCount = rawList.stream()
                .filter(n -> "ALL".equalsIgnoreCase(n.getUserId()) 
                        ? !n.getReadBy().contains(userId) 
                        : !n.isRead())
                .count();

        Map<String, Object> response = new HashMap<>();
        response.put("items", items);
        response.put("totalItems", totalItems);
        response.put("unreadCount", unreadCount);
        response.put("page", page);
        response.put("limit", limit);

        return ResponseEntity.ok(response);
    }

    /**
     * Mark a single notification as read
     */
    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        notificationService.markAsRead(id, principal.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Mark all notifications as read for current user
     */
    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllAsRead(principal.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Delete a single notification
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteNotification(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable String id) {
        boolean isAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase("ROLE_ADMIN"));
        
        boolean deleted = notificationService.deleteNotification(id, principal.getId(), isAdmin);
        if (deleted) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.badRequest().build();
    }

    /**
     * Create and broadcast global system-wide notification (Admin Only)
     */
    @PostMapping("/system")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Notification> sendSystemNotification(
            @RequestBody Map<String, String> body) {
        String title = body.get("title");
        String content = body.get("content");
        String type = body.getOrDefault("type", "SYSTEM");

        if (title == null || title.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề và nội dung thông báo không được để trống");
        }

        Notification notification = notificationService.createAndSend("ALL", title, content, type);
        return ResponseEntity.ok(notification);
    }
}
