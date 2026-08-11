package com.ktb.chatapp.service;

import com.ktb.chatapp.storage.PresignedUpload;
import org.springframework.web.multipart.MultipartFile;

public interface FileService {

    FileUploadResult uploadFile(MultipartFile file, String uploaderId);

    /**
     * 파일을 저장하고 <b>스토리지 key</b>({@code <subDirectory>/<name>})를 반환한다. URL 조립은 응답
     * 경계의 몫이므로 여기서는 하지 않는다.
     */
    String storeFile(MultipartFile file, String subDirectory);

    boolean deleteFile(String fileId, String requesterId);

    /**
     * 채팅 첨부용 사전서명 업로드 URL을 발급한다. 클라이언트가 이 URL로 S3에 직접 업로드한다.
     */
    PresignedUpload issuePresignedUpload(String originalFilename, String contentType, long size);

    /**
     * 클라이언트가 사전서명 URL로 업로드를 마친 뒤 그 결과를 등록한다({@link #uploadFile}과 동일한 메타데이터를
     * 만들지만, 서버가 바이트를 받지 않았으므로 클라이언트가 보낸 메타데이터를 신뢰한다).
     */
    FileUploadResult confirmUpload(String key, String originalFilename, String contentType, long size, String uploaderId);
}

