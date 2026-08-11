# S3 presigned-upload 프론트엔드 연동

백엔드(apps/backend)에 S3 presigned URL 방식 업로드가 추가됐다. 기존 방식(POST /api/files/upload, POST
/api/users/profile-image 멀티파트)은 그대로 살아있고 삭제되지 않았다 — 이번 작업은 새 엔드포인트를 프론트에
연결하는 것이다. 완전 교체할지, 새 플로우만 추가할지는 네가 판단해도 되지만, 기존 엔드포인트를 지우지는 마라
(백엔드가 롤백 가능하게 유지 중).

## 새로 생긴 3단계 업로드 플로우

기존: 클라이언트 → (파일 바이트 포함) → 우리 서버 → 우리 서버가 저장
신규: 클라이언트 → 우리 서버(발급) → 클라이언트가 S3에 직접 PUT → 클라이언트 → 우리 서버(확인)

### 1) 채팅 파일 첨부

**발급**: `POST /api/files/presigned-upload` (기존 인증된 axios 인스턴스 그대로 사용)
```json
// request
{ "filename": "photo.png", "contentType": "image/png", "size": 123456 }
// response 200
{ "success": true, "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/chat/...png?X-Amz-...", "key": "chat/1700000000000_abcdef1234567890.png" }
// 400: {"success": false, "message": "..."} (허용 안 되는 MIME/확장자/용량 초과 등)
```

**S3 업로드**: 위에서 받은 `uploadUrl`에 **PUT**으로 직접 요청.
- Header: `Content-Type: <발급 때 보낸 contentType과 정확히 동일>` — 다르면 S3가 signature mismatch로 403.
- Body: raw 파일 바이트 (FormData 아님, multipart 아님)
- **주의**: 이 요청은 우리 서버를 거치지 않고 S3로 직접 나간다. baseURL이나 인증 인터셉터가 걸린 axios 인스턴스로 호출하면 안 됨 — 별도의 plain axios/fetch로 절대 URL(`uploadUrl`) 호출해야 함. (우리 쪽 Authorization 헤더가 같이 나가면 S3가 거부할 수 있음)
- 업로드 진행률(progress)이 필요하면 이 PUT 요청에 onUploadProgress를 걸어야 함 — 예전엔 POST 한 번에 걸었지만, 이제 "발급"과 "S3 업로드"가 분리됐으니 progress는 이 단계에서만 의미 있음.

**확인**: `POST /api/files/presigned-upload/complete`
```json
// request
{ "key": "chat/1700000000000_abcdef1234567890.png", "filename": "photo.png", "contentType": "image/png", "size": 123456 }
// response 200 — 기존 POST /api/files/upload 응답과 동일한 shape
{
  "success": true,
  "message": "파일 업로드 성공",
  "file": { "_id": "...", "filename": "...", "originalname": "...", "mimetype": "...", "size": 123456, "uploadDate": "..." }
}
```

### 2) 프로필 이미지 (동일 패턴)

- 발급: `POST /api/users/profile-image/presigned-upload` → `{ filename, contentType, size }` → `{ success, uploadUrl, key }`
- S3 PUT: 위와 동일
- 확인: `POST /api/users/profile-image/presigned-upload/complete` → `{ key, filename, contentType, size }` → 기존 프로필 이미지 응답과 동일:
  `{ "success": true, "message": "프로필 이미지가 업데이트되었습니다.", "imageUrl": "/api/files/profiles/..." }`

### 공통 규칙
- key 프리픽스가 강제됨: 채팅 첨부는 `chat/...`, 프로필 이미지는 `profiles/...`. confirm 단계에서 안 맞으면 400.
- 허용 MIME/확장자/용량(5MB) 검증은 발급·확인 양쪽에서 서버가 동일하게 체크함. 클라이언트 사전 검증(즉시 피드백용)은 기존처럼 유지하는 게 UX상 낫다.
- `data-testid` 속성은 절대 건드리지 마라 — e2e/Artillery 시나리오가 그대로 참조한다.

## 고쳐야 할 파일 (레포 탐색 결과, 실제 경로)

1. **`apps/frontend/services/fileService.js`** — `uploadFile(file, onProgress, token, sessionId)`를 위 3단계로 재작성. 반환 값 shape을 지금과 동일하게 맞추면 호출부(`useChatFileUpload`, `useFileHandling`) 수정을 최소화할 수 있다.
2. **`apps/frontend/features/chat/files/useChatFileUpload.js`** — `fileService.uploadFile` 호출부. 1번에서 반환 shape 유지하면 안 건드려도 될 수 있음.
3. **`apps/frontend/features/chat/room/useFileHandling.js`** — 위와 별개로 `fileService.uploadFile`을 또 호출하는 병행 훅이 있다. 1번만 고치고 이걸 놓치면 절반은 여전히 구식 방식으로 남는다 — 둘 다 확인해라.
4. **`apps/frontend/components/ProfileImageUpload.js`** — `handleFileSelect` 안에 inline으로 `api.post('/api/users/profile-image', formData, ...)`가 있음. 이걸 3단계 플로우로 교체.

## 테스트
- `apps/frontend/services/__tests__/fileService.test.js`, `apps/frontend/features/chat/files/__tests__/useChatFileUpload.test.js` — 업데이트 필요.
- `ProfileImageUpload.js`는 기존 테스트가 없다 — 새로 작성 권장.
- **e2e 주의**: `e2e/tests/chat.spec.js`, `e2e/artillery/scenarios/chat.scenario.js`가
  `page.waitForResponse(response => response.url().includes('/api/files/upload') && ...)`로
  업로드 API 응답을 감청한다. presigned 플로우로 전환하면 이 URL 매처를
  `/api/files/presigned-upload/complete`(또는 실제로 쓰는 confirm 엔드포인트)로 바꿔야 한다 —
  안 바꾸면 타임아웃으로 테스트가 깨진다. 단, confirm 응답의 `file.mimetype` 등 JSON shape은
  기존 `/api/files/upload` 응답과 동일하게 맞춰뒀으므로 URL 매처 외의 `expect(responseData.file...)`
  검증 로직은 그대로 재사용 가능하다. 같은 패턴이 `e2e/actions/profile.actions.js`가 건드리는
  프로필 이미지 업로드 쪽에도 적용될 수 있으니 같이 확인할 것.

## 참고
- 백엔드 소스: `apps/backend/src/main/java/com/ktb/chatapp/controller/FileController.java`, `UserController.java`
- 궁금한 거 있으면 백엔드 담당자한테 확인해라.
