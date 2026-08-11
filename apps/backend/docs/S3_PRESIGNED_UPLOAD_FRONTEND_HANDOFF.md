# S3 presigned-upload 프론트엔드 연동

백엔드(apps/backend)에 S3 presigned URL 방식 업로드가 추가됐다. 기존 방식(POST /api/files/upload, POST
/api/users/profile-image 멀티파트)은 그대로 살아있고 삭제되지 않았다 — 이번 작업은 새 엔드포인트를 프론트에
연결하는 것이다. 완전 교체할지, 새 플로우만 추가할지는 네가 판단해도 되지만, 기존 엔드포인트를 지우지는 마라
(백엔드가 롤백 가능하게 유지 중).

## e2e를 고칠 수 없다는 제약이 URL 설계에 반영돼 있다

`e2e/tests/chat.spec.js`, `e2e/artillery/scenarios/chat.scenario.js`는 업로드 완료 응답을
`page.waitForResponse(response => response.url().includes('/api/files/upload') && response.status() === 200)`로
감청하고, `e2e/tests/profile.spec.js`는 `url().includes('/api/users/profile-image') && method === 'POST'`로
감청한다. 이 e2e는 수정 대상이 아니므로, **presigned 업로드의 "확인(confirm)" 엔드포인트는 기존 업로드
엔드포인트와 정확히 같은 URL을 재사용**하고 `Content-Type`으로만 두 핸들러(멀티파트 vs JSON)를 구분한다.
그래서 프론트가 어떤 방식으로 업로드하든 e2e가 보는 URL은 항상 동일하다 — e2e는 한 줄도 안 고쳐도 된다.

이 설계 때문에 "발급(issue)" 엔드포인트는 confirm과 다른 경로에 있고, confirm 엔드포인트는 기존 업로드
경로와 **완전히 동일**하다(아래 계약 참고). 프론트에서 이 두 엔드포인트를 헷갈리지 않도록 주의할 것 — 특히
confirm 요청은 `Content-Type: application/json`을 반드시 명시해야 멀티파트 핸들러가 아니라 JSON 핸들러로
라우팅된다.

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

**확인**: `POST /api/files/upload` (기존 멀티파트 업로드와 **같은 URL**, `Content-Type: application/json`으로 구분)
```json
// request
{ "key": "chat/1700000000000_abcdef1234567890.png", "filename": "photo.png", "contentType": "image/png", "size": 123456 }
// response 200 — 기존 POST /api/files/upload(멀티파트) 응답과 완전히 동일한 shape
{
  "success": true,
  "message": "파일 업로드 성공",
  "file": { "_id": "...", "filename": "...", "originalname": "...", "mimetype": "...", "size": 123456, "uploadDate": "..." }
}
```

### 2) 프로필 이미지 (동일 패턴)

- 발급: `POST /api/users/presigned-upload/profile-image` → `{ filename, contentType, size }` → `{ success, uploadUrl, key }`
  (기존 `/api/users/profile-image`와 다른 경로 — e2e가 그 URL 문자열을 감청하기 때문에 발급 응답이 거기서
  안 잡히도록 일부러 분리했다)
- S3 PUT: 위와 동일
- 확인: `POST /api/users/profile-image` (기존 멀티파트 업로드와 **같은 URL**, `Content-Type: application/json`으로 구분)
  → `{ key, filename, contentType, size }` → 기존 프로필 이미지 응답과 완전히 동일:
  `{ "success": true, "message": "프로필 이미지가 업데이트되었습니다.", "imageUrl": "/api/files/profiles/..." }`

### 공통 규칙
- key 프리픽스가 강제됨: 채팅 첨부는 `chat/...`, 프로필 이미지는 `profiles/...`. confirm 단계에서 안 맞으면 400.
- 허용 MIME/확장자/용량(5MB) 검증은 발급·확인 양쪽에서 서버가 동일하게 체크함. 클라이언트 사전 검증(즉시 피드백용)은 기존처럼 유지하는 게 UX상 낫다.
- confirm 요청은 반드시 `Content-Type: application/json`으로 보낼 것 — 헤더를 빼먹으면 같은 URL의 멀티파트
  핸들러가 걸려서(`@RequestParam("file")`/`@RequestParam("profileImage")` 기대) 400/415가 난다.
- `data-testid` 속성은 절대 건드리지 마라 — e2e/Artillery 시나리오가 그대로 참조한다.

## 고쳐야 할 파일 (레포 탐색 결과, 실제 경로)

1. **`apps/frontend/services/fileService.js`** — `uploadFile(file, onProgress, token, sessionId)`를 위 3단계로 재작성. 반환 값 shape을 지금과 동일하게 맞추면 호출부(`useChatFileUpload`, `useFileHandling`) 수정을 최소화할 수 있다.
2. **`apps/frontend/features/chat/files/useChatFileUpload.js`** — `fileService.uploadFile` 호출부. 1번에서 반환 shape 유지하면 안 건드려도 될 수 있음.
3. **`apps/frontend/features/chat/room/useFileHandling.js`** — 위와 별개로 `fileService.uploadFile`을 또 호출하는 병행 훅이 있다. 1번만 고치고 이걸 놓치면 절반은 여전히 구식 방식으로 남는다 — 둘 다 확인해라.
4. **`apps/frontend/components/ProfileImageUpload.js`** — `handleFileSelect` 안에 inline으로 `api.post('/api/users/profile-image', formData, ...)`가 있음. 이걸 3단계 플로우로 교체(단, confirm 단계는 URL이 지금과 동일하니 axios 호출 자체는 유지하고 body/헤더만 바꾸는 셈).

## 테스트
- `apps/frontend/services/__tests__/fileService.test.js`, `apps/frontend/features/chat/files/__tests__/useChatFileUpload.test.js` — 업데이트 필요.
- `ProfileImageUpload.js`는 기존 테스트가 없다 — 새로 작성 권장.
- e2e(`chat.spec.js`, `profile.spec.js`, artillery 시나리오)는 위 URL 설계 덕분에 **수정 불필요** — 다만
  실제로 프론트를 presigned 플로우로 전환한 뒤 한 번은 꼭 돌려서 확인할 것.

## 검증 이력
백엔드에서 실제 S3 버킷에 대고 발급 → PUT 업로드 → confirm(같은 URL, JSON) 전체 플로우와, 기존 멀티파트
업로드 회귀 테스트를 curl로 직접 실행해서 확인했다. e2e가 보는 정확한 predicate(URL substring, method,
status, response shape)를 그대로 재현해서 통과함을 확인함.

## 참고
- 백엔드 소스: `apps/backend/src/main/java/com/ktb/chatapp/controller/FileController.java`, `UserController.java`
- 궁금한 거 있으면 백엔드 담당자한테 확인해라.
