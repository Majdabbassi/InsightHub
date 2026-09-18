package com.dataanalytics.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class UserResponse {

    private Long id;

    private String email;

    @JsonProperty("fullName")
    private String fullName;

    private String role;

    private LocalDateTime createdAt;
}
