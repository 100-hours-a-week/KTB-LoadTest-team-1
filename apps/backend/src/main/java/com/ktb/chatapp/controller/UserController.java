package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.ConfirmUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadRequest;
import com.ktb.chatapp.dto.PresignedUploadResponse;
import com.ktb.chatapp.dto.StandardResponse;
import com.ktb.chatapp.dto.ProfileImageResponse;
import com.ktb.chatapp.dto.UpdateProfileRequest;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.service.UserService;
import com.ktb.chatapp.storage.PresignedUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;

@Tag(name = "사용자 (Users)", description = "사용자 프로필 관리 API - 프로필 조회, 수정, 이미지 업로드, 회원 탈퇴")
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/users")
@Slf4j
public class UserController {

    private final UserService userService;

    /**
     * 현재 사용자 프로필 조회
     */
    @Operation(summary = "내 프로필 조회", description = "현재 로그인한 사용자의 프로필 정보를 조회합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "프로필 조회 성공",
            content = @Content(schema = @Schema(implementation = UserApiResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"사용자를 찾을 수 없습니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @GetMapping("/profile")
    public ResponseEntity<?> getCurrentUserProfile(Principal principal) {
        try {
            UserResponse response = userService.getCurrentUserProfile(principal.getName());
            return ResponseEntity.ok(new UserApiResponse(response));
        } catch (UsernameNotFoundException e) {
            log.error("사용자 프로필 조회 실패: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("사용자 프로필 조회 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("프로필 조회 중 오류가 발생했습니다."));
        }
    }

    /**
     * 현재 사용자 프로필 업데이트
     */
    @Operation(summary = "내 프로필 수정", description = "현재 로그인한 사용자의 프로필 정보를 수정합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "프로필 수정 성공",
            content = @Content(schema = @Schema(implementation = UserUpdateResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 입력값",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PutMapping("/profile")
    public ResponseEntity<?> updateCurrentUserProfile(
            Principal principal,
            @Valid @RequestBody UpdateProfileRequest updateRequest) {

        try {
            UserResponse response = userService.updateUserProfile(principal.getName(), updateRequest);
            return ResponseEntity.ok(new UserUpdateResponse("프로필이 업데이트되었습니다.", response));
        } catch (UsernameNotFoundException e) {
            log.error("사용자 프로필 업데이트 실패: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("사용자 프로필 업데이트 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("프로필 업데이트 중 오류가 발생했습니다."));
        }
    }

    /**
     * 프로필 이미지 업로드
     */
    @Operation(summary = "프로필 이미지 업로드", description = "프로필 이미지를 업로드합니다. 최대 5MB까지 가능합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 업로드 성공",
            content = @Content(schema = @Schema(implementation = ProfileImageResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 파일 형식",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"지원하지 않는 파일 형식입니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "413", description = "파일 크기 초과",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"code\":\"FILE_TOO_LARGE\",\"message\":\"파일 크기는 5MB를 초과할 수 없습니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadProfileImage(
            Principal principal,
            @RequestParam("profileImage") MultipartFile file) {

        try {
            ProfileImageResponse response = userService.uploadProfileImage(principal.getName(), file);
            return ResponseEntity.ok(response);
        } catch (UsernameNotFoundException e) {
            log.error("프로필 이미지 업로드 실패 - 사용자 없음: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (IllegalArgumentException e) {
            log.error("프로필 이미지 업로드 실패 - 잘못된 입력: {}", e.getMessage());
            return ResponseEntity.badRequest().body(StandardResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("프로필 이미지 업로드 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("이미지 업로드 중 오류가 발생했습니다."));
        }
    }

    /**
     * 프로필 이미지 사전서명 업로드 URL 발급
     */
    @Operation(summary = "프로필 이미지 사전서명 업로드 URL 발급", description = "클라이언트가 S3에 직접 업로드할 수 있는 사전서명 URL을 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "발급 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/presigned-upload/profile-image")
    public ResponseEntity<?> issueProfileImagePresignedUpload(
            Principal principal,
            @Valid @RequestBody PresignedUploadRequest request) {

        try {
            PresignedUpload presigned = userService.issueProfileImagePresignedUpload(
                    principal.getName(), request.getFilename(), request.getContentType(), request.getSize());
            return ResponseEntity.ok(new PresignedUploadResponse(
                    true, presigned.uploadUrl().toString(), presigned.key()));
        } catch (UsernameNotFoundException e) {
            log.error("사전서명 업로드 발급 실패 - 사용자 없음: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (IllegalArgumentException e) {
            log.error("사전서명 업로드 발급 실패 - 잘못된 입력: {}", e.getMessage());
            return ResponseEntity.badRequest().body(StandardResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("사전서명 업로드 발급 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("사전서명 업로드 발급 중 오류가 발생했습니다."));
        }
    }

    /**
     * 프로필 이미지 사전서명 업로드 완료 확인.
     *
     * <p>{@code /profile-image}(멀티파트 업로드)와 경로를 공유하고 {@code consumes}로만 구분한다 —
     * e2e가 업로드 응답을 {@code url().includes('/api/users/profile-image')}로 감청하고 있어, 별도
     * 경로를 쓰면 프론트가 이 방식으로 전환할 때 그 감청 로직이 깨진다. e2e는 고칠 수 없는 제약이라
     * API 쪽에서 맞췄다.
     */
    @Operation(summary = "프로필 이미지 사전서명 업로드 완료 확인", description = "S3에 직접 업로드된 프로필 이미지를 등록합니다. "
            + "/profile-image와 같은 경로를 쓰고 Content-Type(application/json)으로 멀티파트 업로드와 구분된다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "등록 성공",
            content = @Content(schema = @Schema(implementation = ProfileImageResponse.class))),
        @ApiResponse(responseCode = "400", description = "잘못된 요청",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping(value = "/profile-image", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> completeProfileImagePresignedUpload(
            Principal principal,
            @Valid @RequestBody ConfirmUploadRequest request) {

        try {
            ProfileImageResponse response = userService.confirmProfileImageUpload(
                    principal.getName(), request.getKey(), request.getFilename(), request.getContentType(),
                    request.getSize());
            return ResponseEntity.ok(response);
        } catch (UsernameNotFoundException e) {
            log.error("사전서명 업로드 확인 실패 - 사용자 없음: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (IllegalArgumentException e) {
            log.error("사전서명 업로드 확인 실패 - 잘못된 입력: {}", e.getMessage());
            return ResponseEntity.badRequest().body(StandardResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("사전서명 업로드 확인 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("사전서명 업로드 확인 중 오류가 발생했습니다."));
        }
    }

    /**
     * 프로필 이미지 삭제
     */
    @Operation(summary = "프로필 이미지 삭제", description = "현재 프로필 이미지를 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "이미지 삭제 성공",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":true,\"message\":\"프로필 이미지가 삭제되었습니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @DeleteMapping("/profile-image")
    public ResponseEntity<?> deleteProfileImage(Principal principal) {
        try {
            userService.deleteProfileImage(principal.getName());
            return ResponseEntity.ok(StandardResponse.success("프로필 이미지가 삭제되었습니다."));
        } catch (UsernameNotFoundException e) {
            log.error("프로필 이미지 삭제 실패 - 사용자 없음: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("프로필 이미지 삭제 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("프로필 이미지 삭제 중 오류가 발생했습니다."));
        }
    }

    /**
     * 회원 탈퇴
     */
    @Operation(summary = "회원 탈퇴", description = "현재 로그인한 사용자의 계정을 영구적으로 삭제합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "회원 탈퇴 성공",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":true,\"message\":\"회원 탈퇴가 완료되었습니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @DeleteMapping("/account")
    public ResponseEntity<?> deleteAccount(Principal principal) {
        try {
            userService.deleteUserAccount(principal.getName());
            return ResponseEntity.ok(StandardResponse.success("회원 탈퇴가 완료되었습니다."));
        } catch (UsernameNotFoundException e) {
            log.error("회원 탈퇴 실패 - 사용자 없음: {}", e.getMessage());
            return ResponseEntity.status(404).body(StandardResponse.error("사용자를 찾을 수 없습니다."));
        } catch (Exception e) {
            log.error("회원 탈퇴 처리 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(StandardResponse.error("회원 탈퇴 처리 중 오류가 발생했습니다."));
        }
    }

    /**
     * API 상태 확인
     */
    @Operation(summary = "User API 상태 확인", description = "User API의 동작 상태를 확인합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "API 정상 동작",
            content = @Content(schema = @Schema(implementation = StatusResponse.class)))
    })
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "")
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        return ResponseEntity.ok(new StatusResponse("User API is running"));
    }
    
}
