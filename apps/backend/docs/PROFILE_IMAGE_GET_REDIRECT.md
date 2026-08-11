# `GET /api/files/profiles/{filename}` 응답 방식 변경 (프론트 확인용)

## 무엇이 바뀌었나

엔드포인트 자체(`GET /api/files/profiles/{filename}`)는 **그대로**다. 응답 방식만 바뀐다.

- **배포 환경(S3 스토리지)**: 이제 이미지 바이트를 서버가 직접 응답하지 않고, **`302 Found` + `Location` 헤더**로 presigned S3 URL을 내려준다. 브라우저가 그 URL로 다시 요청해서 S3에서 직접 이미지를 받는다.
- **로컬 개발 환경**(`file.storage.type=local`): 기존과 동일하게 `200 OK` + 이미지 바이트를 그대로 응답한다. 변경 없음.

즉 실제 응답 코드가 배포 환경에서만 `200` → `302`로 바뀐다.

## 왜 바꿨나

기존엔 프로필 이미지를 볼 때마다 **서버가 S3에서 읽어서 그대로 중계**하고 있었다 — 채팅 파일 다운로드(`/api/files/download`, `/api/files/view`)는 이미 오래전부터 presigned URL로 리다이렉트하는데, 프로필 이미지만 이 처리를 안 받고 남아있던 것. 요청마다 서버 스레드를 붙잡고 있던 걸 없앴다.

## 프론트에서 확인해야 할 것

**`<img src="...">` 태그로 쓰고 있다면 아무것도 안 해도 된다.** 브라우저는 `<img>` 요청에서 3xx 리다이렉트를 자동으로 따라가므로 화면에 보이는 결과는 동일하다.

**확인이 필요한 경우**: 이 엔드포인트를 `fetch`/`axios` 등으로 직접 호출하면서 응답 코드를 검사하는 코드가 있다면 체크해봐야 한다.
- `fetch`/`axios` 기본 설정은 리다이렉트를 자동으로 따라가므로 최종 응답(S3의 `200`)만 보게 되어 대부분 문제없다.
- 다만 `fetch(url, { redirect: 'manual' })`처럼 리다이렉트를 수동 처리하도록 설정했거나, `axios`에 `maxRedirects: 0`을 준 코드가 있다면 그 부분만 `302`를 인지하도록 손봐야 한다.

## 예시 (배포 환경 기준)

```
GET /api/files/profiles/1700000000000_abcd1234.jpg
→ 302 Found
  Location: https://<bucket>.s3.<region>.amazonaws.com/profiles/1700000000000_abcd1234.jpg?X-Amz-...
```
