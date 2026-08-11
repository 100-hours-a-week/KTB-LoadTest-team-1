package com.ktb.chatapp.service;

import com.ktb.chatapp.model.File;
import com.ktb.chatapp.repository.FileRepository;
import com.ktb.chatapp.storage.PresignedUpload;
import com.ktb.chatapp.storage.StorageKey;
import com.ktb.chatapp.storage.StoragePort;
import com.ktb.chatapp.util.FileUtil;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class LocalFileService implements FileService {

    private final StoragePort storagePort;
    private final FileRepository fileRepository;

    @Value("${aws.s3.presign-upload-ttl-seconds}")
    private long presignUploadTtlSeconds;

    public LocalFileService(StoragePort storagePort, FileRepository fileRepository) {
        this.storagePort = storagePort;
        this.fileRepository = fileRepository;
    }

    @Override
    public FileUploadResult uploadFile(MultipartFile file, String uploaderId) {
        try {
            // 파일 보안 검증
            FileUtil.validateFile(file);

            // 안전한 파일명 생성
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "file";
            }
            originalFilename = StringUtils.cleanPath(originalFilename);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);

            // 파일 저장 (채팅 첨부는 chat/ key로 저장)
            String key = StorageKey.chat(safeFileName);
            storagePort.put(file.getInputStream(), key, file.getContentType(), file.getSize());

            log.info("파일 저장 완료: {}", safeFileName);

            // 원본 파일명 정규화
            String normalizedOriginalname = FileUtil.normalizeOriginalFilename(originalFilename);

            // 메타데이터 생성 및 저장
            File fileEntity = File.builder()
                    .filename(safeFileName)
                    .originalname(normalizedOriginalname)
                    .mimetype(file.getContentType())
                    .size(file.getSize())
                    .path(key)
                    .user(uploaderId)
                    .uploadDate(LocalDateTime.now())
                    .build();

            File savedFile = fileRepository.save(fileEntity);

            return FileUploadResult.builder()
                    .success(true)
                    .file(savedFile)
                    .build();

        } catch (Exception e) {
            log.error("파일 업로드 처리 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 업로드에 실패했습니다: " + e.getMessage(), e);
        }
    }

    @Override
    public String storeFile(MultipartFile file, String subDirectory) {
        try {
            // 파일 보안 검증
            FileUtil.validateFile(file);

            // 안전한 파일명 생성
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null) {
                originalFilename = "file";
            }
            originalFilename = StringUtils.cleanPath(originalFilename);
            String safeFileName = FileUtil.generateSafeFileName(originalFilename);

            // key 조립 (서브디렉토리 포함, 예: profiles)
            String key = (subDirectory != null && !subDirectory.trim().isEmpty())
                    ? subDirectory + "/" + safeFileName
                    : safeFileName;

            // 파일 저장
            storagePort.put(file.getInputStream(), key, file.getContentType(), file.getSize());

            log.info("파일 저장 완료: {}", safeFileName);

            return key;

        } catch (IOException ex) {
            log.error("파일 저장 실패: {}", ex.getMessage(), ex);
            throw new RuntimeException("파일 저장에 실패했습니다: " + ex.getMessage(), ex);
        }
    }

    @Override
    public PresignedUpload issuePresignedUpload(String originalFilename, String contentType, long size) {
        FileUtil.validateFileMetadata(originalFilename, contentType, size);

        String cleanedFilename = StringUtils.cleanPath(originalFilename);
        String safeFileName = FileUtil.generateSafeFileName(cleanedFilename);
        String key = StorageKey.chat(safeFileName);

        return storagePort.createUploadUrl(key, contentType, Duration.ofSeconds(presignUploadTtlSeconds))
                .orElseThrow(() -> new RuntimeException("현재 스토리지에서는 사전서명 업로드를 지원하지 않습니다."));
    }

    @Override
    public FileUploadResult confirmUpload(
            String key, String originalFilename, String contentType, long size, String uploaderId) {
        FileUtil.validateFileMetadata(originalFilename, contentType, size);
        if (!StorageKey.isChat(key)) {
            throw new RuntimeException("잘못된 파일 key 입니다.");
        }

        String normalizedOriginalname = FileUtil.normalizeOriginalFilename(StringUtils.cleanPath(originalFilename));

        File fileEntity = File.builder()
                .filename(StorageKey.nameOf(key))
                .originalname(normalizedOriginalname)
                .mimetype(contentType)
                .size(size)
                .path(key)
                .user(uploaderId)
                .uploadDate(LocalDateTime.now())
                .build();

        File savedFile = fileRepository.save(fileEntity);

        log.info("사전서명 업로드 확인 완료: {}", key);

        return FileUploadResult.builder()
                .success(true)
                .file(savedFile)
                .build();
    }

    @Override
    public boolean deleteFile(String fileId, String requesterId) {
        try {
            File fileEntity = fileRepository.findById(fileId)
                    .orElseThrow(() -> new RuntimeException("파일을 찾을 수 없습니다."));

            // 삭제 권한 검증 (업로더만 삭제 가능)
            if (!fileEntity.getUser().equals(requesterId)) {
                throw new RuntimeException("파일을 삭제할 권한이 없습니다.");
            }

            // 물리적 파일 삭제
            storagePort.delete(fileEntity.getPath());

            // 데이터베이스에서 제거
            fileRepository.delete(fileEntity);

            log.info("파일 삭제 완료: {} (사용자: {})", fileId, requesterId);
            return true;

        } catch (Exception e) {
            log.error("파일 삭제 실패: {}", e.getMessage(), e);
            throw new RuntimeException("파일 삭제 중 오류가 발생했습니다.", e);
        }
    }
}
