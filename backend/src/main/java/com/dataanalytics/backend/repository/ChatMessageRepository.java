package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.ChatConversation;
import com.dataanalytics.backend.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    List<ChatMessage> findTop6ByConversationIdOrderByCreatedAtDescIdAsc(Long conversationId);

    void deleteByConversation(ChatConversation conversation);
}
