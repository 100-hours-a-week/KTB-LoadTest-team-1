package com.ktb.chatapp.controller;

import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로필 이미지만을 익명에게 서빙한다 — 앱 전체에서 인가 없이 읽히는 유일한 파일 표면이다.
 *
 * <p>이미 공개된 리소스라 오프로딩 URL 유출을 걱정할 필요가 없어서, 채팅 첨부(짧은 TTL,
 * {@link com.ktb.chatapp.service.FileAccessService}) 보다 훨씬 긴 TTL을 쓴다.
 */
@Tag(name = "파일 (Files)", description = "프로필 이미지 공개 서빙")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/files/profiles")
public class ProfileImageController {

    private static final Duration OFFLOAD_URL_TTL = Duration.ofHours(1);

    private final StoragePort storagePort;

    @Operation(summary = "프로필 이미지 조회", description = "프로필 이미지를 반환합니다. 인증이 필요하지 않습니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 조회 성공"),
        @ApiResponse(responseCode = "302", description = "스토리지가 오프로딩 URL을 지원하는 경우, 그 URL로 리다이렉트"),
        @ApiResponse(responseCode = "400", description = "잘못된 파일명"),
        @ApiResponse(responseCode = "404", description = "이미지를 찾을 수 없음")
    })
    @SecurityRequirement(name = "")
    @GetMapping("/{filename:.+}")
    public ResponseEntity<Resource> getProfileImage(
            @Parameter(description = "조회할 프로필 이미지 파일명") @PathVariable String filename) {

        if (FileUtil.containsPathTraversal(filename)) {
            return ResponseEntity.badRequest().build();
        }

        String key = StorageKey.profile(filename);

        Optional<URI> offloadUrl = storagePort.offloadUrl(
                key, OFFLOAD_URL_TTL, ContentDisposition.inline().filename(filename).build());
        if (offloadUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(offloadUrl.get()).build();
        }

        return storagePort.open(key)
                .map(resource -> ResponseEntity.ok()
                        .contentType(contentTypeOf(filename))
                        .body(resource))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MediaType contentTypeOf(String filename) {
        return MediaTypeFactory.getMediaType(filename).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
