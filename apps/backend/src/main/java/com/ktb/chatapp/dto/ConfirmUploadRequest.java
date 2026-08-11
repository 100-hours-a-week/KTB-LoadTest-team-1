package com.ktb.chatapp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 클라이언트가 S3에 직접 업로드를 마친 뒤, 그 결과를 서버에 알릴 때 보내는 요청. */
@Data
public class ConfirmUploadRequest {
    @NotBlank
    private String key;

    @NotBlank
    private String filename;

    @NotBlank
    private String contentType;

    @Positive
    private long size;
}
