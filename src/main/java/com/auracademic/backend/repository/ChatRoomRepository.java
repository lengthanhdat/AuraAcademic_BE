package com.auracademic.backend.repository;

import com.auracademic.backend.model.ChatRoom;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatRoomRepository extends MongoRepository<ChatRoom, String> {
    Optional<ChatRoom> findByUserId(String userId);
    List<ChatRoom> findAllByOrderByLastMessageTimeDesc();
}
