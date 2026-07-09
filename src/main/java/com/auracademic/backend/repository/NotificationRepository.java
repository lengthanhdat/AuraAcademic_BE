package com.auracademic.backend.repository;

import com.auracademic.backend.model.Notification;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends MongoRepository<Notification, String> {
    // Find all notifications for a specific user OR global ones, ordered by creation date desc
    List<Notification> findByUserIdOrUserIdOrderByCreatedAtDesc(String userId, String globalId);
}
