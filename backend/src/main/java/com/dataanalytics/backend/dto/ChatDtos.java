package com.dataanalytics.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class ChatDtos {

    private ChatDtos() {
    }

    public record ChatAskRequest(
            @NotBlank @Size(max = 4000) String message) {
    }

    public record ChatMessageResponse(
            Long id,
            String role,
            String content,
            String usedSql) {
    }
}
