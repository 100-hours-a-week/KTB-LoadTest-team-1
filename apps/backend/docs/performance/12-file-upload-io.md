# 12. 파일 업로드가 이미 스레드가 부족한 Tomcat 위에서 동기 디스크 I/O

> **한줄 요약**: 파일 업로드가 스레드를 붙잡고 서버 디스크에 동기적으로 썼다. S3 presigned URL
> 방식으로 바꿔서 서버가 파일 바디 자체를 아예 거치지 않게 만들었다 — 스레드 21→3, 서버가
> 처리한 바이트 80MB→20KB, p99 3,980ms→35ms.

| 상태 | 영향 범위 | 분류 |
|---|---|---|
| ✅ 완료 | 파일 업로드가 포함된 부하테스트 시나리오 | Medium |

## 문제 (원래 상태)

`LocalStorage`/`FileService`가 로컬 디스크에 동기적으로 파일을 썼다. 클라이언트가 서버로
멀티파트 바디 전체를 업로드하고, 서버가 그걸 받아서 다시 디스크에 쓰는 구조라 파일 크기·개수에
비례해 요청이 오래 걸렸다. [1번 항목](01-tomcat-thread-pool.md)(Tomcat 스레드가 유한)과 겹치면,
파일 업로드 요청 하나가 스레드를 오래 붙잡아 다른 REST 요청까지 같이 지연될 수 있었다.

## 조치 ✅

S3 presigned URL 업로드로 전환했다. 흐름:

1. 클라이언트가 `POST /api/files/presigned-upload`(또는 프로필 이미지는
   `/api/users/presigned-upload/profile-image`)로 업로드하려는 파일 메타데이터(이름/타입/크기)를
   보낸다 — 서버는 파일 바이트를 전혀 받지 않고, `S3Presigner`로 PUT용 presigned URL만 발급한다.
2. 클라이언트가 그 URL로 **S3에 직접** 업로드한다(서버를 거치지 않음).
3. 클라이언트가 업로드 완료를 서버에 알린다(`POST /api/files/upload`를
   `Content-Type: application/json`으로 — 기존 멀티파트 업로드와 **같은 URL**을 재사용하도록
   설계했다. 이유는 아래 "e2e 호환성" 참고).

`StoragePort`(`LocalStorage`/`S3Storage`)로 구현을 추상화해뒀고, `file.storage.type=s3`로
켠다(로컬 개발은 `local` 기본값 그대로 디스크 저장).

### e2e 호환성 — URL을 바꾸지 않고 `consumes`로 분기

`e2e/`는 수정 대상이 아니다(하드 제약). 그런데 기존 e2e가 `url().includes('/api/files/upload')`,
`url().includes('/api/users/profile-image') && method === 'POST'` 형태로 **업로드 완료 요청의
URL 문자열을 하드코딩**하고 있어서, presigned 흐름으로 바꾸면서 엔드포인트 URL을 새로 만들면 e2e가
깨질 상황이었다.

해결: 업로드 "완료 확인" 엔드포인트를 기존 멀티파트 업로드와 **동일한 URL**에 두고,
`consumes`(Content-Type)로 두 핸들러를 구분했다.

| 엔드포인트 | Content-Type | 동작 |
|---|---|---|
| `POST /api/files/upload` | `multipart/form-data` | 기존 방식(서버가 파일을 직접 받아 저장) |
| `POST /api/files/upload` | `application/json` | 신규 방식(S3 업로드 완료 확인, 메타데이터만) |
| `POST /api/users/profile-image` | `multipart/form-data` | 기존 방식 |
| `POST /api/users/profile-image` | `application/json` | 신규 방식(완료 확인) |

프론트가 어느 방식을 쓰든 e2e의 URL assertion은 그대로 매치된다. 자세한 프론트 연동 가이드는
[S3_PRESIGNED_UPLOAD_FRONTEND_HANDOFF.md](../S3_PRESIGNED_UPLOAD_FRONTEND_HANDOFF.md) 참고.

## 측정 결과

`upload-flow-compare.js`로 동시 20개 2MB 파일 업로드를 비교(기존 멀티파트 vs presigned S3):

| 지표 | 기존(멀티파트, 서버 경유) | S3 presigned |
|---|---|---|
| 서버가 점유한 스레드 | 21 | **3** |
| 서버가 실제로 처리한 바이트 | 80MB | **20KB**(메타데이터만) |
| p99 지연시간 | 3,980ms | **35ms** |

서버 입장에서는 파일 바이트 자체를 거의 안 보게 되면서, 업로드 요청이 다른 REST 요청(로그인,
방 목록 등)과 스레드를 다투지 않게 됐다.

## 검증

- 실제 S3 버킷에 대해 curl로 presigned URL 발급→PUT 업로드→완료 확인 전체 플로우 확인.
- Playwright e2e 전체 스위트 재실행, 업로드 관련 테스트 포함 **18/18 통과**(코드 변경 없이 e2e
  그대로).
