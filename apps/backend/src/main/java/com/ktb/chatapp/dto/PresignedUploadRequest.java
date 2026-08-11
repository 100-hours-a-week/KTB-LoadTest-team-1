package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class PresignedUploadRequest {
    @NotBlank
    private String filename;

    @NotBlank
    private String contentType;

    @Positive
    private long size;
}
