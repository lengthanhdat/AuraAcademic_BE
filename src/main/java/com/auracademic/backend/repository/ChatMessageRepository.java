package com.auracademic.backend.repository;

import com.auracademic.backend.model.ChatMessage;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {
    List<ChatMessage> findByRoomIdOrderByTimestampAsc(String roomId);
    long countByRoomIdAndSeenFalseAndSenderRoleNot(String roomId, String senderRole);
}
