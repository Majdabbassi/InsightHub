package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.ChatConversation;
import com.dataanalytics.backend.model.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAscIdAsc(Long conversationId);

    List<ChatMessage> findTop6ByConversationIdOrderByCreatedAtDescIdAsc(Long conversationId);

    Page<ChatMessage> findByConversationId(Long conversationId, Pageable pageable);

    void deleteByConversation(ChatConversation conversation);
}
