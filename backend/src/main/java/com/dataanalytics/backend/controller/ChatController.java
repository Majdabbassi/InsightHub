package com.dataanalytics.backend.controller;

import com.dataanalytics.backend.dto.ChatDtos.ChatAskRequest;
import com.dataanalytics.backend.dto.ChatDtos.ChatMessageResponse;
import com.dataanalytics.backend.model.Project;
import com.dataanalytics.backend.service.ChatService;
import com.dataanalytics.backend.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI assistant chat for a project. One conversation per project; answers are
 * generated locally by Ollama and grounded in a pre-computed context bundle.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ProjectService projectService;

    @PostMapping
    public ResponseEntity<ChatMessageResponse> ask(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @Valid @RequestBody ChatAskRequest request) {
        Project project = projectService.findOwnedProject(
                userDetails.getUsername(), projectId);
        return ResponseEntity.ok(chatService.ask(project, request.message()));
    }

    @GetMapping("/history")
    public ResponseEntity<Page<ChatMessageResponse>> history(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId,
            @PageableDefault(size = 50, sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable) {
        Project project = projectService.findOwnedProject(
                userDetails.getUsername(), projectId);
        return ResponseEntity.ok(chatService.history(project, pageable));
    }

    @DeleteMapping("/history")
    public ResponseEntity<Void> clearHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long projectId) {
        Project project = projectService.findOwnedProject(
                userDetails.getUsername(), projectId);
        chatService.clearHistory(project);
        return ResponseEntity.noContent().build();
    }
}
