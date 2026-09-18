package com.dataanalytics.backend.repository;

import com.dataanalytics.backend.model.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByProjectId(Long projectId);
}
